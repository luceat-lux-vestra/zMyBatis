#!/usr/bin/env python3
"""Required/staged-context drift check for merge-gate-policy.yml."""

from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_trust_boundary import Job, indent_of, workflow_has_pull_request_trigger, split_jobs  # noqa: E402

ENTRY_PATTERN = re.compile(
    r"^\s*-\s*context:\s*(?P<context>.+?)\s*\n"
    r"\s*producedBy:\s*(?P<produced_by>\S+)\s*\n"
    r"\s*job:\s*(?P<job>\S+)\s*"
    r"(?:\n\s*trigger:\s*(?P<trigger>\S+)\s*)?$",
    re.MULTILINE,
)


def section_entries(policy_text: str, section_name: str) -> list[dict[str, str]]:
    marker = f"{section_name}:"
    try:
        start = policy_text.index(marker)
    except ValueError:
        return []
    rest = policy_text[start + len(marker):]
    end_match = re.search(r"^\S[^\n]*:\s*$", rest, re.MULTILINE)
    section = rest[:end_match.start()] if end_match else rest
    return [m.groupdict() for m in ENTRY_PATTERN.finditer(section)]


def required_entries(policy_text: str) -> list[dict[str, str]]:
    """Public helper retained for live-settings policy reconciliation."""
    return section_entries(policy_text, "requiredStatusChecks")


def staged_required_entries(policy_text: str) -> list[dict[str, str]]:
    return section_entries(policy_text, "stagedRequiredChecks")


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
    baseline = next((indent_of(line) for line in job.lines if line.strip()), None)
    if baseline is None:
        return False
    return any(
        indent_of(line) == baseline and line.strip().startswith("if:")
        for line in job.lines
        if line.strip()
    )


def trigger_has_path_filter(all_lines: list[str], trigger: str) -> bool:
    try:
        index = next(i for i, line in enumerate(all_lines) if line.strip() == f"{trigger}:")
    except StopIteration:
        return False
    trigger_indent = indent_of(all_lines[index])
    for line in all_lines[index + 1:]:
        if not line.strip():
            continue
        if indent_of(line) <= trigger_indent:
            break
        if re.match(r"^\s*paths(-ignore)?:", line):
            return True
    return False


def has_target_trigger(all_lines: list[str]) -> bool:
    return any(re.match(r"^\s*pull_request_target:\s*(?:#.*)?$", line) for line in all_lines)


def check_entry(entry: dict[str, str], repo_root: Path, classification: str) -> list[str]:
    failures: list[str] = []
    context, produced_by, job_id = entry["context"], entry["produced_by"], entry["job"]
    workflow_path = repo_root / produced_by
    if not workflow_path.is_file():
        return [f"{classification} '{context}': producedBy workflow {produced_by} does not exist"]

    all_lines = workflow_path.read_text(encoding="utf-8").splitlines()
    job = find_job(all_lines, job_id)
    if job is None:
        return [f"{classification} '{context}': no job '{job_id}' found in {produced_by}"]

    name = job_name_field(job)
    if name != context:
        failures.append(f"'{context}': job '{job_id}' in {produced_by} has name '{name}', expected '{context}'")

    if job_has_if(job):
        failures.append(f"'{context}': job '{job_id}' in {produced_by} has an 'if:' condition")

    trigger = entry.get("trigger") or "pull_request"
    if trigger not in {"pull_request", "pull_request_target"}:
        failures.append(f"'{context}': unsupported required-context trigger '{trigger}'")
        return failures
    if trigger == "pull_request_target":
        failures.append(f"'{context}': required and staged gates must use unprivileged pull_request")
        return failures

    has_trigger = workflow_has_pull_request_trigger(all_lines) if trigger == "pull_request" else has_target_trigger(all_lines)
    if not has_trigger:
        failures.append(f"'{context}': {produced_by} has no {trigger} trigger")
    elif trigger_has_path_filter(all_lines, trigger):
        failures.append(f"'{context}': {produced_by}'s {trigger} trigger has a paths/paths-ignore filter")

    return failures


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: check_required_contexts.py <merge-gate-policy.yml> <repo-root>", file=sys.stderr)
        return 2

    policy_path = Path(argv[0])
    repo_root = Path(argv[1])
    policy_text = policy_path.read_text(encoding="utf-8")
    required = required_entries(policy_text)
    staged = staged_required_entries(policy_text)
    if not required:
        print(f"no requiredStatusChecks entries found in {policy_path}", file=sys.stderr)
        return 2

    failures: list[str] = []
    for entry in required:
        failures.extend(check_entry(entry, repo_root, "required"))
    for entry in staged:
        failures.extend(check_entry(entry, repo_root, "staged"))

    if failures:
        print("merge-gate-policy.yml required-context drift:")
        for failure in failures:
            print(f"  {failure}")
        return 1

    print(f"OK: {len(required)} required and {len(staged)} staged-required contexts match their producing workflows.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
