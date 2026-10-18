#!/usr/bin/env python3
"""Fail-closed contract for Qodana applicability and `Inspect code` evidence.

PR-time Qodana may be non-applicable only when a classifier loaded from the
trusted PR base proves every changed path safe. PR source must never authorize
its own exemption. When Qodana is applicable, findings still fail the job.
Repository source must not be hidden behind Qodana exclusions. The only
maintained exclusion is Qodana's own generated workspace.

This checker deliberately validates a small repository-specific contract without
a YAML dependency, matching the other workflow-policy checkers.

Usage:
    check_qodana_gate.py <build-workflow.yml> <qodana.yml>
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_trust_boundary import split_jobs  # noqa: E402

EXPECTED_LINTER = "jetbrains/qodana-jvm-community:2026.2"
ACTION_PATTERN = re.compile(
    r"^\s*uses:\s*JetBrains/qodana-action@[0-9a-f]{40}(?:\s*#.*)?$",
    re.MULTILINE,
)
EXPECTED_EXCLUSIONS: dict[str, tuple[str, ...]] = {
    "All": (".qodana",),
}


def fail(message: str, failures: list[str]) -> None:
    failures.append(message)
    print(f"ERROR: {message}", file=sys.stderr)


def workflow_job(workflow_text: str, name: str):
    jobs = split_jobs(workflow_text.splitlines())
    return next((job for job in jobs if job.name == name), None)


def inspect_job(workflow_text: str):
    return workflow_job(workflow_text, "inspectCode")


def require_field(job_text: str, field: str, expected: str, failures: list[str]) -> None:
    pattern = re.compile(
        rf"^\s*{re.escape(field)}:\s*{re.escape(expected)}\s*$",
        re.MULTILINE,
    )
    if not pattern.search(job_text):
        fail(f"Inspect code must set `{field}: {expected}`", failures)


def parse_exclusions(qodana_text: str, failures: list[str]) -> dict[str, tuple[str, ...]]:
    lines = qodana_text.splitlines()
    try:
        start = next(i for i, line in enumerate(lines) if line == "exclude:")
    except StopIteration:
        fail("qodana.yml must exclude only the generated .qodana workspace", failures)
        return {}

    parsed: dict[str, tuple[str, ...]] = {}
    current_name: str | None = None
    current_paths: list[str] = []
    saw_paths_header = False

    def finish_current() -> None:
        nonlocal current_name, current_paths, saw_paths_header
        if current_name is None:
            return
        if current_name in parsed:
            fail(f"qodana.yml duplicates exclusion `{current_name}`", failures)
        if not saw_paths_header or not current_paths:
            fail(f"qodana.yml exclusion `{current_name}` must have explicit paths", failures)
        if len(current_paths) != len(set(current_paths)):
            fail(f"qodana.yml exclusion `{current_name}` contains duplicate paths", failures)
        parsed[current_name] = tuple(current_paths)
        current_name = None
        current_paths = []
        saw_paths_header = False

    for line in lines[start + 1 :]:
        if line and not line.startswith(" "):
            break
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue

        name_match = re.fullmatch(r"- name:\s*(\S+)", stripped)
        if name_match and line.startswith("  - name:"):
            finish_current()
            current_name = name_match.group(1)
            continue

        if current_name is not None and line == "    paths:":
            if saw_paths_header:
                fail(f"qodana.yml exclusion `{current_name}` repeats `paths:`", failures)
            saw_paths_header = True
            continue

        path_match = re.fullmatch(r"-\s+(.+)", stripped)
        if current_name is not None and saw_paths_header and line.startswith("      - ") and path_match:
            current_paths.append(path_match.group(1))
            continue

        fail(f"unsupported qodana.yml exclusion syntax: `{stripped}`", failures)

    finish_current()
    return parsed


def check(workflow_path: Path, qodana_path: Path) -> list[str]:
    failures: list[str] = []
    workflow_text = workflow_path.read_text(encoding="utf-8")
    qodana_text = qodana_path.read_text(encoding="utf-8")

    job = inspect_job(workflow_text)
    if job is None:
        fail("build workflow is missing the inspectCode job", failures)
        return failures

    job_text = "\n".join(job.lines)
    if not ACTION_PATTERN.search(job_text):
        fail("Inspect code must invoke Qodana from an immutable 40-hex action pin", failures)
    scope_job = workflow_job(workflow_text, "qodanaScope")
    if scope_job is None:
        fail("build workflow is missing the qodanaScope job", failures)
    else:
        scope_text = "\n".join(scope_job.lines)
        required_scope_fragments = (
            "ref: ${{ github.event.pull_request.base.sha }}",
            "path: .trusted-qodana-scope",
            "persist-credentials: false",
            'classifier=".trusted-qodana-scope/.github/workflow-policy/classify_qodana_scope.py"',
            'test "$(git -C .trusted-qodana-scope rev-parse HEAD)" = "$PR_BASE_SHA"',
            "--jq '.changed_files'",
            "gh api --paginate --slurp",
            'echo "qodana_required=true"',
            'echo "qodana_reason=metadata-api-failure"',
            'echo "qodana_reason=non-pr-full-scan"',
        )
        for fragment in required_scope_fragments:
            if fragment not in scope_text:
                fail(f"Qodana scope authority is missing trusted-base control `{fragment}`", failures)
        if "github.event.pull_request.head.sha" in scope_text:
            fail("Qodana scope authority must never load policy from PR head", failures)

    required_inspect_fragments = (
        "needs: [ qodanaScope ]",
        "Require valid Qodana scope decision",
        "needs.qodanaScope.outputs.qodana_required == 'false'",
        'test "$QODANA_REASON" = "all-paths-proven-non-qodana"',
        "needs.qodanaScope.outputs.qodana_required == 'true'",
    )
    for fragment in required_inspect_fragments:
        if fragment not in job_text:
            fail(f"Inspect code applicability gate is missing `{fragment}`", failures)

    require_field(job_text, "args", "--fail-threshold 0", failures)
    require_field(job_text, "pr-mode", "false", failures)
    require_field(job_text, "upload-result", "true", failures)

    if re.search(r"^\s*continue-on-error:\s*true\s*$", job_text, re.MULTILINE):
        fail("Inspect code must not continue on Qodana failure", failures)

    linter_match = re.search(r"^\s*linter:\s*(\S+)\s*$", qodana_text, re.MULTILINE)
    if linter_match is None:
        fail("qodana.yml must declare an explicit linter", failures)
    elif linter_match.group(1) != EXPECTED_LINTER:
        fail(
            f"qodana.yml linter must be `{EXPECTED_LINTER}`, got `{linter_match.group(1)}`",
            failures,
        )

    exclusions = parse_exclusions(qodana_text, failures)
    if exclusions != EXPECTED_EXCLUSIONS:
        missing = sorted(set(EXPECTED_EXCLUSIONS) - set(exclusions))
        unexpected = sorted(set(exclusions) - set(EXPECTED_EXCLUSIONS))
        changed = sorted(
            name
            for name in set(exclusions) & set(EXPECTED_EXCLUSIONS)
            if exclusions[name] != EXPECTED_EXCLUSIONS[name]
        )
        if missing:
            fail(f"qodana.yml is missing required tooling exclusions: {missing}", failures)
        if unexpected:
            fail(f"qodana.yml adds forbidden source/tooling exclusions: {unexpected}", failures)
        if changed:
            fail(f"qodana.yml changes the generated-workspace exclusion: {changed}", failures)

    return failures


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print("usage: check_qodana_gate.py <build-workflow.yml> <qodana.yml>", file=sys.stderr)
        return 2

    failures = check(Path(argv[1]), Path(argv[2]))
    if failures:
        return 1

    print("Qodana required gate is fail-closed with no repository-source allowlist.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
