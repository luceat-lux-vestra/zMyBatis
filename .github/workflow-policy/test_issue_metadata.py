#!/usr/bin/env python3
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
POLICY = ROOT / ".github" / "issue-metadata-policy.json"

def load():
    data = json.loads(POLICY.read_text())
    labels = {entry["name"] for entry in data["labels"]}
    rules = [(re.compile(entry["pattern"], re.I), entry["label"]) for entry in data["rules"]]
    return labels, rules

def classify(title, rules):
    value = title.strip()
    for pattern, label in rules:
        if pattern.search(value):
            return label
    return None

def main():
    labels, rules = load()
    cases = {
        "bug(editor): stale SQL": "type:bug",
        "feat(core): add capability": "type:feature",
        "security: harden execution": "type:security",
        "docs: update contract": "type:docs",
        "design(evaluation): freeze boundary": "type:research",
        "architecture: target model": "type:research",
        "task(statement): source snapshot": "type:task",
        "track(repo): hardening": "type:task",
        "epic(leap): platform": "type:task",
        "build: update dependency": "type:task",
        "hardening(reassessment): refresh controls": "type:task",
        "governance: classify failure signals": "type:task",
        "Investigate mapper behavior": None,
    }
    failures = []
    workflow = (ROOT / ".github" / "workflows" / "issue-metadata.yml").read_text()
    if not re.search(r"(?ms)^      dry_run:\n.*?^        default: true\s*$", workflow):
        failures.append("manual issue reconciliation must default dry_run=true")
    if not re.search(r"(?ms)^      backfill:\n.*?^        default: false\s*$", workflow):
        failures.append("manual issue reconciliation must default backfill=false")
    for fragment in (
        "const defaultBranchRef = `refs/heads/${context.payload.repository.default_branch}`;",
        'context.eventName === "workflow_dispatch" && backfill && !dryRun && context.ref !== defaultBranchRef',
        "Mutating backfill must run from",
        "persist-credentials: false",
        "ref: ${{ github.event.repository.default_branch }}",
        "const shouldEnsureLabels =",
        'context.eventName === "issues" ||',
        '(context.eventName === "workflow_dispatch" && backfill && !dryRun)',
    ):
        if fragment not in workflow:
            failures.append(f"issue metadata mutation boundary missing: {fragment}")
    if "if (!dryRun) await ensureLabels();" in workflow:
        failures.append("empty workflow_dispatch must not mutate canonical labels")

    for title, expected in cases.items():
        actual = classify(title, rules)
        if actual != expected:
            failures.append(f"{title!r}: expected {expected!r}, got {actual!r}")
    for _, label in rules:
        if label not in labels:
            failures.append(f"rule references undeclared label {label}")
    if failures:
        print("issue metadata policy violations:")
        for failure in failures:
            print(f"  - {failure}")
        return 1
    print("issue metadata policy: PASS")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
