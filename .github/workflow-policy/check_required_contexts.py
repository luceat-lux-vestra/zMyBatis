#!/usr/bin/env python3
"""Required-context drift check for merge-gate-policy.yml.

`merge-gate-policy.yml` names GitHub status-check contexts that are meant to
be configured as required in branch protection. That list is only
trustworthy if every entry:

1. names a job that actually exists in the workflow it claims to come from,
   whose `name:` is exactly the declared context (a rename of either side
   silently breaks required-check matching in GitHub's branch protection -
   the check simply never appears, and the PR sits blocked forever);
2. is not gated behind a job-level `if:` (a required check that can
   legitimately be skipped leaves an indefinitely pending PR); and
3. comes from a workflow whose `pull_request:` trigger has no
   `paths`/`paths-ignore` filter (a required check that some PRs never
   trigger is the same footgun as (2), just triggered by the diff instead
   of a condition).

This does not use a YAML parser, matching the rest of `.github/workflow-
policy/` - see check_trust_boundary.py's module docstring for why.

Usage:
    check_required_contexts.py <merge-gate-policy.yml> <repo-root>
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_trust_boundary import Job, indent_of, split_jobs  # noqa: E402

ENTRY_PATTERN = re.compile(
    r"^\s*-\s*context:\s*(?P<context>.+?)\s*\n"
    r"\s*producedBy:\s*(?P<produced_by>\S+)\s*\n"
    r"\s*job:\s*(?P<job>\S+)\s*$",
    re.MULTILINE,
)


def required_entries(policy_text: str) -> list[dict[str, str]]:
    try:
        start = policy_text.index("requiredStatusChecks:")
    except ValueError:
        return []
    end = policy_text.find("\nexcludedFromRequiredChecks:", start)
    section = policy_text[start:end if end != -1 else None]
    return [m.groupdict() for m in ENTRY_PATTERN.finditer(section)]


def find_job(all_lines: list[str], job_id: str) -> Job | None:
    jobs_index = next((i for i, line in enumerate(all_lines) if line.rstrip() == "jobs:"), None)
    if jobs_index is None:
        return None
    for job in split_jobs(all_lines):
        if job.name == job_id:
            job.start = next(
                i for i in range(jobs_index, len(all_lines))
                if all_lines[i].rstrip() == f"  {job_id}:"
            )
            return job
    return None


def job_name_field(job: Job) -> str | None:
    for line in job.lines:
        stripped = line.strip()
        if stripped.startswith("name:"):
            return stripped[len("name:"):].strip()
    return None


def job_has_if(job: Job) -> bool:
    """True if the job itself (not one of its steps) has an `if:` condition.

    A step-level `if:` (e.g. `if: ${{ failure() }}` on a cleanup step) is
    indented deeper than the job's own top-level keys (`name:`, `runs-on:`,
    `steps:`, ...) and must not trigger this check.
    """
    baseline = next((indent_of(line) for line in job.lines if line.strip()), None)
    if baseline is None:
        return False
    return any(
        indent_of(line) == baseline and line.strip().startswith("if:")
        for line in job.lines
        if line.strip()
    )


def pull_request_trigger_has_path_filter(all_lines: list[str]) -> bool:
    try:
        pr_index = next(i for i, line in enumerate(all_lines) if line.strip() == "pull_request:")
    except StopIteration:
        return False  # No pull_request trigger at all is a separate, caller-checked problem.
    pr_indent = indent_of(all_lines[pr_index])
    for line in all_lines[pr_index + 1:]:
        if not line.strip():
            continue
        if indent_of(line) <= pr_indent:
            break
        if re.match(r"^\s*paths(-ignore)?:", line):
            return True
    return False


def check_entry(entry: dict[str, str], repo_root: Path) -> list[str]:
    failures = []
    context, produced_by, job_id = entry["context"], entry["produced_by"], entry["job"]
    workflow_path = repo_root / produced_by
    if not workflow_path.is_file():
        return [f"'{context}': producedBy workflow {produced_by} does not exist"]

    all_lines = workflow_path.read_text(encoding="utf-8").splitlines()

    job = find_job(all_lines, job_id)
    if job is None:
        failures.append(f"'{context}': no job '{job_id}' found in {produced_by}")
        return failures

    name = job_name_field(job)
    if name != context:
        failures.append(
            f"'{context}': job '{job_id}' in {produced_by} has name '{name}', expected '{context}'"
        )

    if job_has_if(job):
        failures.append(
            f"'{context}': job '{job_id}' in {produced_by} has an 'if:' condition - a required "
            "context must run unconditionally on every pull request"
        )

    if pull_request_trigger_has_path_filter(all_lines):
        failures.append(
            f"'{context}': {produced_by}'s pull_request trigger has a paths/paths-ignore "
            "filter - a required context must not be skippable by diff shape"
        )

    return failures


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: check_required_contexts.py <merge-gate-policy.yml> <repo-root>", file=sys.stderr)
        return 2

    policy_path = Path(argv[0])
    repo_root = Path(argv[1])
    entries = required_entries(policy_path.read_text(encoding="utf-8"))
    if not entries:
        print(f"no requiredStatusChecks entries found in {policy_path}", file=sys.stderr)
        return 2

    failures: list[str] = []
    for entry in entries:
        failures.extend(check_entry(entry, repo_root))

    if failures:
        print("merge-gate-policy.yml required-context drift:")
        for failure in failures:
            print(f"  {failure}")
        return 1

    print(f"OK: all {len(entries)} requiredStatusChecks entries match their producing workflows.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
