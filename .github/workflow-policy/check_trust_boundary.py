#!/usr/bin/env python3
"""Fail-closed trust-boundary checks for GitHub Actions workflows.

This intentionally does not try to be a general-purpose GitHub Actions linter
(actionlint and zizmor already cover schema/expression/known-audit territory
- see workflow-lint.yml). It enforces two narrow, concrete rules that match
this repository's actual incident class: a validation job that checks out
pull-request-authored source while holding a mutating token.

Rule R1 - no privileged execution of untrusted PR content:
    A job must not both (a) hold `contents: write`, `checks: write`, or
    `pull-requests: write` (via a job-level `permissions:` block, or a
    workflow-level block the job does not override) and (b) check out
    `github.event.pull_request.head.*` (the PR-authored commit, as opposed to
    the safe synthetic merge ref). That combination is exactly what let the
    Qodana `Inspect code` job run PR-authored code with a writable token
    before this policy existed.

Rule R2 - explicit credential hygiene for validation jobs:
    A job that holds none of `contents: write`, `checks: write`, or
    `pull-requests: write` (i.e. it is read-only / a "validation job") must
    set `persist-credentials: false` on every `actions/checkout` step it
    uses. Relying on the default (`true`) leaves a usable repository token
    sitting in `.git/config` for the rest of that job for no reason.

This is line/regex based, matching the style of check_pins.py, rather than a
full YAML parser: workflow files in this repository are hand-authored with
consistent indentation and no anchors/aliases, and a regex-based checker is
easier to audit for correctness than a YAML+GitHub-Actions semantic model
would be. Block membership is always decided by comparing indentation levels
relative to the enclosing header line, never by an assumed absolute column,
so the same logic works for both the workflow-level and job-level blocks.

Usage:
    check_trust_boundary.py <workflow-file-or-directory> [...]
"""
from __future__ import annotations

import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

WRITE_PERMISSION = re.compile(r"^(contents|checks|pull-requests):\s*write\s*$")
WRITE_ALL = re.compile(r"^permissions:\s*write-all\s*$")
JOB_HEADER = re.compile(r"^  ([A-Za-z0-9_-]+):\s*$")
CHECKOUT_USES = re.compile(r"^\s*uses:\s*actions/checkout@")
PR_HEAD_REF = re.compile(r"github\.event\.pull_request\.head\.(sha|ref)")
PERSIST_CREDENTIALS_FALSE = re.compile(r"^\s*persist-credentials:\s*false\s*$")

WRITE_SCOPES = {"contents", "checks", "pull-requests"}


def indent_of(line: str) -> int:
    return len(line) - len(line.lstrip(" "))


@dataclass
class Job:
    name: str
    start: int
    lines: list[str] = field(default_factory=list)

    def text(self) -> str:
        return "\n".join(self.lines)


def split_jobs(lines: list[str]) -> list[Job]:
    """Split a workflow file into its top-level `jobs:` blocks.

    Assumes the conventional two-space job indentation used throughout this
    repository's workflows (`jobs:` at column 0, `  <job-id>:` at column 2,
    job body indented further). `job.lines` holds everything strictly inside
    the job (not the `  <job-id>:` header line itself).
    """
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


def extract_write_grants(lines: list[str], header_key: str) -> tuple[set[str], bool]:
    """Find `<header_key>:` in `lines` and collect write-scope children.

    Block membership is decided purely by indentation relative to the header
    line, so this works whether the header sits at column 0 (workflow-level
    `permissions:`) or deeper (job-level `permissions:`).

    Returns (grants, declared) - `declared` is False when no `<header_key>:`
    line exists at all, so callers can fall back to workflow-level defaults.
    """
    for index, line in enumerate(lines):
        stripped = line.strip()
        if stripped == f"{header_key}:":
            header_indent = indent_of(line)
            grants: set[str] = set()
            for child in lines[index + 1:]:
                if not child.strip():
                    continue
                if indent_of(child) <= header_indent:
                    break
                match = WRITE_PERMISSION.match(child.strip())
                if match:
                    grants.add(match.group(1))
            return grants, True
        if WRITE_ALL.match(stripped):
            return set(WRITE_SCOPES), True
    return set(), False


def workflow_level_permissions(lines: list[str]) -> set[str]:
    try:
        jobs_index = next(i for i, line in enumerate(lines) if line.rstrip() == "jobs:")
    except StopIteration:
        jobs_index = len(lines)
    grants, _declared = extract_write_grants(lines[:jobs_index], "permissions")
    return grants


def job_permissions(job: Job, workflow_defaults: set[str]) -> set[str]:
    grants, declared = extract_write_grants(job.lines, "permissions")
    return grants if declared else set(workflow_defaults)


def job_checks_out_pr_head(job: Job) -> bool:
    return bool(PR_HEAD_REF.search(job.text()))


def checkout_steps_missing_persist_credentials_false(job: Job) -> list[int]:
    """Return 1-based line numbers (within the file) of `actions/checkout`
    steps in this job that never set `persist-credentials: false`.
    """
    offending: list[int] = []
    lines = job.lines
    for i, line in enumerate(lines):
        if not CHECKOUT_USES.search(line):
            continue
        step_indent = indent_of(line)
        found = False
        for later in lines[i + 1:]:
            if not later.strip():
                continue
            if indent_of(later) < step_indent:
                break
            if PERSIST_CREDENTIALS_FALSE.match(later):
                found = True
                break
        if not found:
            offending.append(job.start + i + 1)
    return offending


def check_file(path: Path) -> list[str]:
    all_lines = path.read_text(encoding="utf-8").splitlines()
    defaults = workflow_level_permissions(all_lines)
    failures: list[str] = []

    # Re-split with absolute line numbers so `job.start` yields file-accurate
    # line numbers in diagnostics.
    jobs_index = next((i for i, line in enumerate(all_lines) if line.rstrip() == "jobs:"), None)
    if jobs_index is None:
        return failures
    for job in split_jobs(all_lines):
        # Locate this job's header line to know its absolute start offset.
        header_line = f"  {job.name}:"
        job.start = next(
            i for i in range(jobs_index, len(all_lines)) if all_lines[i].rstrip() == header_line
        )

        grants = job_permissions(job, defaults)
        privileged = grants & WRITE_SCOPES

        if privileged and job_checks_out_pr_head(job):
            failures.append(
                f"{path}: job '{job.name}' holds write permission(s) {sorted(privileged)} "
                "while checking out github.event.pull_request.head - a privileged job must "
                "never execute pull-request-authored source (R1)"
            )

        if not privileged:
            for line_number in checkout_steps_missing_persist_credentials_false(job):
                failures.append(
                    f"{path}:{line_number}: job '{job.name}' is a validation job (no write "
                    "permissions) but its actions/checkout step does not set "
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
        print("usage: check_trust_boundary.py <workflow-file-or-directory> [...]", file=sys.stderr)
        return 2

    files = iter_workflow_files(argv)
    if not files:
        print(f"no workflow files found under: {' '.join(argv)}", file=sys.stderr)
        return 2

    failures: list[str] = []
    for path in files:
        failures.extend(check_file(path))

    if failures:
        print("Trust-boundary violations:")
        for failure in failures:
            print(f"  {failure}")
        return 1

    print(f"OK: {len(files)} file(s) satisfy the R1/R2 trust-boundary rules.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
