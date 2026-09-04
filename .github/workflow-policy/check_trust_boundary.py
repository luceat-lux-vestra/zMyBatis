#!/usr/bin/env python3
"""Fail-closed trust-boundary checks for GitHub Actions workflows.

This checker enforces repository-specific invariants that complement actionlint
and zizmor.

R0 - explicit token authority:
    Every scanned job must have an explicit effective `permissions` declaration,
    either at workflow level or as a job-level override. Missing declarations
    must not fall back to mutable repository/organization defaults.

R1 - no privileged execution of PR-controlled source:
    In a workflow that runs on `pull_request`, a job that checks out source must
    not hold any effective `*: write` permission unless the job is provably
    excluded from pull-request execution by one exact, audited event-name
    condition. The default synthetic merge ref still contains PR-controlled
    changes, so this rule is about code provenance rather than one ref spelling.

R2 - explicit checkout credential hygiene:
    A job with no effective write permission must set
    `persist-credentials: false` on every `actions/checkout` step.

Unsupported permission syntax fails closed.
"""
from __future__ import annotations

import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

PERMISSION_ENTRY = re.compile(
    r"^(?P<scope>[A-Za-z0-9_-]+):\s*(?P<level>read|write|none)\s*$"
)
WRITE_ALL = re.compile(r"^permissions:\s*write-all\s*$")
READ_ALL = re.compile(r"^permissions:\s*read-all\s*$")
EMPTY_PERMISSIONS = re.compile(r"^permissions:\s*\{\s*\}\s*$")
INLINE_PERMISSIONS = re.compile(r"^permissions:\s*\{(?P<body>.*)\}\s*$")
JOB_HEADER = re.compile(r"^  ([A-Za-z0-9_-]+):\s*$")
CHECKOUT_USES = re.compile(r"^\s*uses:\s*actions/checkout@")
PERSIST_CREDENTIALS_FALSE = re.compile(
    r"^\s*persist-credentials:\s*false\s*$"
)

KNOWN_PERMISSION_SCOPES = {
    "actions",
    "attestations",
    "checks",
    "contents",
    "deployments",
    "discussions",
    "id-token",
    "issues",
    "models",
    "packages",
    "pages",
    "pull-requests",
    "security-events",
    "statuses",
}

SAFE_PR_EXCLUSIONS = {
    "github.event_name != 'pull_request'",
    'github.event_name != "pull_request"',
    "github.event_name == 'push'",
    'github.event_name == "push"',
}


def indent_of(line: str) -> int:
    return len(line) - len(line.lstrip(" "))


@dataclass
class Job:
    name: str
    start: int
    lines: list[str] = field(default_factory=list)


@dataclass(frozen=True)
class PermissionState:
    write_grants: frozenset[str]
    declared: bool
    unsupported: bool


def split_jobs(lines: list[str]) -> list[Job]:
    jobs: list[Job] = []
    in_jobs = False
    current: Job | None = None
    for line in lines:
        if line.rstrip() == "jobs:":
            in_jobs = True
            continue
        if not in_jobs:
            continue
        if line and not line.startswith(" "):
            break
        match = JOB_HEADER.match(line)
        if match:
            if current is not None:
                jobs.append(current)
            current = Job(name=match.group(1), start=0)
            continue
        if current is not None:
            current.lines.append(line)
    if current is not None:
        jobs.append(current)
    return jobs


def parse_permission_value(scope: str, level: str) -> tuple[set[str], bool]:
    if scope not in KNOWN_PERMISSION_SCOPES:
        return set(), True
    return ({scope} if level == "write" else set()), False


def extract_permissions(lines: list[str], header_key: str) -> PermissionState:
    for index, line in enumerate(lines):
        stripped = line.strip()

        if stripped == f"{header_key}:":
            header_indent = indent_of(line)
            write_grants: set[str] = set()
            unsupported = False
            saw_entry = False

            for child in lines[index + 1 :]:
                if not child.strip():
                    continue
                if indent_of(child) <= header_indent:
                    break
                if child.strip().startswith("#"):
                    continue

                saw_entry = True
                match = PERMISSION_ENTRY.match(child.strip())
                if not match:
                    unsupported = True
                    continue

                grants, bad = parse_permission_value(
                    match.group("scope"), match.group("level")
                )
                write_grants.update(grants)
                unsupported |= bad

            if not saw_entry:
                unsupported = True
            return PermissionState(
                frozenset(write_grants), declared=True, unsupported=unsupported
            )

        if WRITE_ALL.match(stripped):
            return PermissionState(
                frozenset(KNOWN_PERMISSION_SCOPES),
                declared=True,
                unsupported=False,
            )

        if READ_ALL.match(stripped) or EMPTY_PERMISSIONS.match(stripped):
            return PermissionState(frozenset(), declared=True, unsupported=False)

        inline = INLINE_PERMISSIONS.match(stripped)
        if inline:
            body = inline.group("body").strip()
            if not body:
                return PermissionState(
                    frozenset(), declared=True, unsupported=False
                )

            write_grants: set[str] = set()
            unsupported = False
            for item in body.split(","):
                match = PERMISSION_ENTRY.match(item.strip())
                if not match:
                    unsupported = True
                    continue
                grants, bad = parse_permission_value(
                    match.group("scope"), match.group("level")
                )
                write_grants.update(grants)
                unsupported |= bad

            return PermissionState(
                frozenset(write_grants), declared=True, unsupported=unsupported
            )

        if stripped.startswith("permissions:"):
            return PermissionState(
                frozenset(), declared=True, unsupported=True
            )

    return PermissionState(frozenset(), declared=False, unsupported=False)


def workflow_level_permissions(lines: list[str]) -> PermissionState:
    try:
        jobs_index = next(
            i for i, line in enumerate(lines) if line.rstrip() == "jobs:"
        )
    except StopIteration:
        jobs_index = len(lines)
    return extract_permissions(lines[:jobs_index], "permissions")


def effective_job_permissions(
    job: Job, workflow_permissions: PermissionState
) -> PermissionState:
    job_permissions = extract_permissions(job.lines, "permissions")
    if job_permissions.declared:
        return job_permissions
    return workflow_permissions


def workflow_has_pull_request_trigger(lines: list[str]) -> bool:
    """Recognize block, scalar, and flow-sequence pull_request trigger forms."""
    for index, line in enumerate(lines):
        stripped = line.strip()
        if indent_of(line) == 0 and re.match(
            r"^on:\s*(?:pull_request|\[.*\bpull_request\b.*\])\s*$",
            stripped,
        ):
            return True

        if indent_of(line) == 0 and stripped == "on:":
            for child in lines[index + 1 :]:
                if child.strip() and indent_of(child) == 0:
                    break
                if child.strip() in {"pull_request:", "pull_request"}:
                    return True

    return False


def normalize_if_expression(line: str) -> str | None:
    stripped = line.strip()
    if not stripped.startswith("if:"):
        return None

    expression = stripped[len("if:") :].strip()
    if expression.startswith("${{") and expression.endswith("}}"):
        expression = expression[3:-2].strip()
    return expression


def job_excluded_from_pull_request(job: Job) -> bool:
    """Accept only exact event-name exclusions; compound expressions fail closed."""
    baseline = next(
        (indent_of(line) for line in job.lines if line.strip()), None
    )
    if baseline is None:
        return False

    job_level_if = [
        normalize_if_expression(line)
        for line in job.lines
        if line.strip().startswith("if:") and indent_of(line) == baseline
    ]
    expressions = [expr for expr in job_level_if if expr is not None]

    return len(expressions) == 1 and expressions[0] in SAFE_PR_EXCLUSIONS


def checkout_steps_missing_persist_credentials_false(job: Job) -> list[int]:
    offending: list[int] = []
    lines = job.lines

    for i, line in enumerate(lines):
        if not CHECKOUT_USES.search(line):
            continue

        uses_indent = indent_of(line)
        found = False
        for later in lines[i + 1 :]:
            if not later.strip():
                continue
            if indent_of(later) < uses_indent:
                break
            if PERSIST_CREDENTIALS_FALSE.match(later):
                found = True
                break

        if not found:
            offending.append(job.start + i + 1)

    return offending


def check_file(path: Path) -> list[str]:
    all_lines = path.read_text(encoding="utf-8").splitlines()
    workflow_permissions = workflow_level_permissions(all_lines)
    failures: list[str] = []

    if workflow_permissions.unsupported:
        failures.append(
            f"{path}: workflow-level permissions use unsupported syntax; "
            "refusing to infer token authority"
        )

    jobs_index = next(
        (i for i, line in enumerate(all_lines) if line.rstrip() == "jobs:"),
        None,
    )
    if jobs_index is None:
        return failures

    is_pr_workflow = workflow_has_pull_request_trigger(all_lines)

    for job in split_jobs(all_lines):
        header_line = f"  {job.name}:"
        job.start = next(
            i
            for i in range(jobs_index, len(all_lines))
            if all_lines[i].rstrip() == header_line
        )

        effective = effective_job_permissions(job, workflow_permissions)

        if not effective.declared:
            failures.append(
                f"{path}: job '{job.name}' has no explicit effective permissions "
                "declaration; refusing to fall back to repository/organization "
                "GITHUB_TOKEN defaults (R0)"
            )
            continue

        if effective.unsupported:
            failures.append(
                f"{path}: job '{job.name}' uses unsupported effective permissions "
                "syntax; refusing to infer token authority"
            )
            continue

        write_grants = set(effective.write_grants)
        has_checkout = any(CHECKOUT_USES.search(line) for line in job.lines)

        if (
            write_grants
            and is_pr_workflow
            and not job_excluded_from_pull_request(job)
            and has_checkout
        ):
            failures.append(
                f"{path}: job '{job.name}' holds write permission(s) "
                f"{sorted(write_grants)} and checks out source in a pull_request "
                "workflow - privileged PR-controlled execution is forbidden (R1)"
            )

        if not write_grants:
            for line_number in checkout_steps_missing_persist_credentials_false(
                job
            ):
                failures.append(
                    f"{path}:{line_number}: job '{job.name}' has no write "
                    "permissions but its actions/checkout step does not set "
                    "persist-credentials: false (R2)"
                )

    return failures


def iter_workflow_files(targets: list[str]) -> list[Path]:
    files: list[Path] = []
    for target in targets:
        path = Path(target)
        if path.is_dir():
            files.extend(sorted(path.glob("*.yml")))
            files.extend(sorted(path.glob("*.yaml")))
        elif path.is_file():
            files.append(path)
    return files


def main(argv: list[str]) -> int:
    if not argv:
        print(
            "usage: check_trust_boundary.py <workflow-file-or-directory> [...]",
            file=sys.stderr,
        )
        return 2

    files = iter_workflow_files(argv)
    if not files:
        print(
            f"no workflow files found under: {' '.join(argv)}",
            file=sys.stderr,
        )
        return 2

    failures: list[str] = []
    for path in files:
        failures.extend(check_file(path))

    if failures:
        print("Trust-boundary violations:")
        for failure in failures:
            print(f"  {failure}")
        return 1

    print(
        f"OK: {len(files)} file(s) satisfy the R0/R1/R2 "
        "trust-boundary rules."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
