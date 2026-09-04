#!/usr/bin/env python3
"""Deterministic negative controls for the workflow static-analysis gate.

Every tool wired into `.github/workflows/workflow-lint.yml` (the two local
checkers here, plus actionlint and zizmor) must reject the known-bad
fixtures under `fixtures/bad/` and accept both the known-good fixture under
`fixtures/good/` and this repository's real workflows. Without this file,
someone could quietly weaken or delete a rule and `workflow-lint.yml` would
keep passing - a fail-open regression in the thing that is supposed to be
the fail-closed gate.

This intentionally runs as a plain script (`python3 test_policy.py`) with no
third-party test framework dependency, so it can run in the same step as the
checks it is validating.

Exit status: 0 if every expectation held, 1 otherwise (with a report of what
diverged).
"""
from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
POLICY_DIR = Path(__file__).resolve().parent
WORKFLOWS_DIR = REPO_ROOT / ".github" / "workflows"
BAD_FIXTURES = sorted((POLICY_DIR / "fixtures" / "bad").glob("*.yml"))
GOOD_FIXTURES = sorted((POLICY_DIR / "fixtures" / "good").glob("*.yml"))

# Must match the hardcoded file lists in workflow-lint.yml's
# "Require sound permission/credential trust boundaries", "actionlint", and
# "zizmor" steps. release.yml is deliberately excluded - see that file's
# scope note.
VALIDATION_WORKFLOWS = [
    WORKFLOWS_DIR / "build.yml",
    WORKFLOWS_DIR / "run-ui-tests.yml",
    WORKFLOWS_DIR / "workflow-lint.yml",
]


def resolve_actionlint() -> str | None:
    """Locate an actionlint executable.

    Prefers one already on PATH (the case in a developer sandbox). Falls
    back to the binary workflow-lint.yml's "Install actionlint" step
    extracts to the repository root, since that step does not put it on
    PATH within the job.
    """
    on_path = shutil.which("actionlint")
    if on_path:
        return on_path
    local_binary = REPO_ROOT / "actionlint"
    if local_binary.is_file():
        return str(local_binary)
    return None


def run(cmd: list[str]) -> int:
    result = subprocess.run(cmd, cwd=REPO_ROOT, capture_output=True, text=True)
    if result.returncode != 0:
        sys.stderr.write(result.stdout)
        sys.stderr.write(result.stderr)
    return result.returncode


def expect(label: str, condition: bool, failures: list[str]) -> None:
    print(f"{'ok  ' if condition else 'FAIL'} - {label}")
    if not condition:
        failures.append(label)


def check_pins_and_boundary(failures: list[str]) -> None:
    for fixture in BAD_FIXTURES:
        rc = run(["python3", str(POLICY_DIR / "check_pins.py"), str(fixture)])
        rc_boundary = run(["python3", str(POLICY_DIR / "check_trust_boundary.py"), str(fixture)])
        # A "bad" fixture only needs to be rejected by the check(s) it targets;
        # require at least one of the two local checkers to reject it.
        expect(
            f"local checkers reject {fixture.relative_to(REPO_ROOT)}",
            rc != 0 or rc_boundary != 0,
            failures,
        )

    for fixture in GOOD_FIXTURES:
        rc = run(["python3", str(POLICY_DIR / "check_pins.py"), str(fixture)])
        rc_boundary = run(["python3", str(POLICY_DIR / "check_trust_boundary.py"), str(fixture)])
        expect(f"check_pins.py accepts {fixture.relative_to(REPO_ROOT)}", rc == 0, failures)
        expect(f"check_trust_boundary.py accepts {fixture.relative_to(REPO_ROOT)}", rc_boundary == 0, failures)

    rc = run(["python3", str(POLICY_DIR / "check_pins.py"), str(WORKFLOWS_DIR)])
    expect("check_pins.py accepts the real .github/workflows (including release.yml)", rc == 0, failures)

    rc = run(["python3", str(POLICY_DIR / "check_trust_boundary.py")] + [str(p) for p in VALIDATION_WORKFLOWS])
    expect("check_trust_boundary.py accepts the real validation workflows", rc == 0, failures)

    rc = run([
        "python3", str(POLICY_DIR / "check_required_contexts.py"),
        str(REPO_ROOT / ".github" / "merge-gate-policy.yml"), str(REPO_ROOT),
    ])
    expect("check_required_contexts.py accepts merge-gate-policy.yml as-is", rc == 0, failures)


def check_actionlint(failures: list[str]) -> None:
    actionlint = resolve_actionlint()
    if not actionlint:
        expect("actionlint is available", False, failures)
        return

    # actionlint has no SHA-pinning or permissions-scope rule of its own (that is
    # what check_pins.py/check_trust_boundary.py/zizmor are for); it is exercised
    # here only for schema/expression/shellcheck coverage against known-good input.
    rc = run([actionlint] + [str(p) for p in GOOD_FIXTURES])
    expect("actionlint accepts the good fixture", rc == 0, failures)

    rc = run([actionlint] + [str(p) for p in VALIDATION_WORKFLOWS])
    expect("actionlint accepts the real validation workflows", rc == 0, failures)


def check_zizmor(failures: list[str]) -> None:
    if not shutil.which("zizmor"):
        expect("zizmor is available", False, failures)
        return

    for fixture in BAD_FIXTURES:
        rc = run(["zizmor", "--offline", "--persona", "regular", str(fixture)])
        expect(f"zizmor rejects {fixture.relative_to(REPO_ROOT)}", rc != 0, failures)

    for fixture in GOOD_FIXTURES:
        rc = run(["zizmor", "--offline", "--persona", "regular", str(fixture)])
        expect(f"zizmor accepts {fixture.relative_to(REPO_ROOT)}", rc == 0, failures)

    rc = run(["zizmor", "--offline", "--persona", "regular"] + [str(p) for p in VALIDATION_WORKFLOWS])
    expect("zizmor accepts the real validation workflows", rc == 0, failures)


def main() -> int:
    failures: list[str] = []
    check_pins_and_boundary(failures)
    check_actionlint(failures)
    check_zizmor(failures)

    if failures:
        print(f"\n{len(failures)} expectation(s) failed:")
        for failure in failures:
            print(f"  - {failure}")
        return 1

    print("\nAll negative/positive controls held.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
