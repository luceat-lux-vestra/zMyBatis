#!/usr/bin/env python3
"""Deterministic controls for the workflow static-analysis gate.

The local policy checkers must reject checked-in bad fixtures and accept the
real validation workflows. actionlint and zizmor provide complementary
schema/security coverage; repository-specific fixtures may intentionally be
local-checker-only when those tools do not model the invariant.
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
REQUIRED_CONTEXTS_BAD_FIXTURE = (
    POLICY_DIR / "fixtures" / "bad" / "required_contexts_missing_pull_request"
)

# Repository-specific controls that zizmor does not necessarily reject. The
# local checker is the authoritative oracle for these fixtures.
ZIZMOR_LOCAL_ONLY_FIXTURES = {
    "compound_pr_condition.yml",
    "missing_permissions.yml",
    "pwn_default_checkout.yml",
    "pwn_other_write_scope.yml",
}

VALIDATION_WORKFLOWS = [
    WORKFLOWS_DIR / "build.yml",
    WORKFLOWS_DIR / "run-ui-tests.yml",
    WORKFLOWS_DIR / "workflow-lint.yml",
]


def resolve_actionlint() -> str | None:
    on_path = shutil.which("actionlint")
    if on_path:
        return on_path

    local_binary = REPO_ROOT / "actionlint"
    if local_binary.is_file():
        return str(local_binary)
    return None


def run(cmd: list[str]) -> int:
    result = subprocess.run(
        cmd,
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        sys.stderr.write(result.stdout)
        sys.stderr.write(result.stderr)
    return result.returncode


def expect(label: str, condition: bool, failures: list[str]) -> None:
    print(f"{'ok  ' if condition else 'FAIL'} - {label}")
    if not condition:
        failures.append(label)


def check_local_policy(failures: list[str]) -> None:
    for fixture in BAD_FIXTURES:
        pins_rc = run(
            ["python3", str(POLICY_DIR / "check_pins.py"), str(fixture)]
        )
        boundary_rc = run(
            [
                "python3",
                str(POLICY_DIR / "check_trust_boundary.py"),
                str(fixture),
            ]
        )
        expect(
            f"local checkers reject {fixture.relative_to(REPO_ROOT)}",
            pins_rc != 0 or boundary_rc != 0,
            failures,
        )

    for fixture in GOOD_FIXTURES:
        pins_rc = run(
            ["python3", str(POLICY_DIR / "check_pins.py"), str(fixture)]
        )
        boundary_rc = run(
            [
                "python3",
                str(POLICY_DIR / "check_trust_boundary.py"),
                str(fixture),
            ]
        )
        expect(
            f"check_pins.py accepts {fixture.relative_to(REPO_ROOT)}",
            pins_rc == 0,
            failures,
        )
        expect(
            f"check_trust_boundary.py accepts {fixture.relative_to(REPO_ROOT)}",
            boundary_rc == 0,
            failures,
        )

    pins_rc = run(
        [
            "python3",
            str(POLICY_DIR / "check_pins.py"),
            str(WORKFLOWS_DIR),
        ]
    )
    expect(
        "check_pins.py accepts the real .github/workflows (including release.yml)",
        pins_rc == 0,
        failures,
    )

    boundary_rc = run(
        [
            "python3",
            str(POLICY_DIR / "check_trust_boundary.py"),
            *[str(path) for path in VALIDATION_WORKFLOWS],
        ]
    )
    expect(
        "check_trust_boundary.py accepts the real validation workflows",
        boundary_rc == 0,
        failures,
    )

    required_rc = run(
        [
            "python3",
            str(POLICY_DIR / "check_required_contexts.py"),
            str(REPO_ROOT / ".github" / "merge-gate-policy.yml"),
            str(REPO_ROOT),
        ]
    )
    expect(
        "check_required_contexts.py accepts merge-gate-policy.yml as-is",
        required_rc == 0,
        failures,
    )

    fixture_policy = REQUIRED_CONTEXTS_BAD_FIXTURE / "merge-gate-policy.yml"
    required_bad_rc = run(
        [
            "python3",
            str(POLICY_DIR / "check_required_contexts.py"),
            str(fixture_policy),
            str(REQUIRED_CONTEXTS_BAD_FIXTURE),
        ]
    )
    expect(
        "check_required_contexts.py rejects a producing workflow with no pull_request trigger",
        required_bad_rc != 0,
        failures,
    )


def check_actionlint(failures: list[str]) -> None:
    actionlint = resolve_actionlint()
    if not actionlint:
        expect("actionlint is available", False, failures)
        return

    good_rc = run([actionlint, *[str(path) for path in GOOD_FIXTURES]])
    expect("actionlint accepts the good fixture", good_rc == 0, failures)

    real_rc = run(
        [actionlint, *[str(path) for path in VALIDATION_WORKFLOWS]]
    )
    expect(
        "actionlint accepts the real validation workflows",
        real_rc == 0,
        failures,
    )


def check_zizmor(failures: list[str]) -> None:
    if not shutil.which("zizmor"):
        expect("zizmor is available", False, failures)
        return

    for fixture in BAD_FIXTURES:
        if fixture.name in ZIZMOR_LOCAL_ONLY_FIXTURES:
            continue
        rc = run(
            [
                "zizmor",
                "--offline",
                "--persona",
                "regular",
                str(fixture),
            ]
        )
        expect(
            f"zizmor rejects {fixture.relative_to(REPO_ROOT)}",
            rc != 0,
            failures,
        )

    for fixture in GOOD_FIXTURES:
        rc = run(
            [
                "zizmor",
                "--offline",
                "--persona",
                "regular",
                str(fixture),
            ]
        )
        expect(
            f"zizmor accepts {fixture.relative_to(REPO_ROOT)}",
            rc == 0,
            failures,
        )

    real_rc = run(
        [
            "zizmor",
            "--offline",
            "--persona",
            "regular",
            *[str(path) for path in VALIDATION_WORKFLOWS],
        ]
    )
    expect(
        "zizmor accepts the real validation workflows",
        real_rc == 0,
        failures,
    )


def main() -> int:
    failures: list[str] = []
    check_local_policy(failures)
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
