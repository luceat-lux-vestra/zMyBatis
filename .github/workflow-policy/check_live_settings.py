#!/usr/bin/env python3
"""Fail-closed scheduled readback of GitHub repository/ruleset policy."""
from __future__ import annotations

import argparse
import ast
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_required_contexts import required_entries  # noqa: E402

SECTION = "liveSettingsAudit"


def scalar(raw: str) -> Any:
    raw = raw.strip()
    if raw in ("true", "false"):
        return raw == "true"
    if re.fullmatch(r"-?\d+", raw):
        return int(raw)
    if raw.startswith("[") and raw.endswith("]"):
        body = raw[1:-1].strip()
        return [] if not body else [scalar(part) for part in body.split(",")]
    if raw[:1] in ("'", '"'):
        value = ast.literal_eval(raw)
        if not isinstance(value, str):
            raise ValueError(f"non-string quoted scalar: {raw}")
        return value
    return raw


def parse_policy(text: str) -> dict[str, Any]:
    lines = text.splitlines()
    try:
        start = next(i for i, line in enumerate(lines) if line.rstrip() == f"{SECTION}:")
    except StopIteration as exc:
        raise ValueError(f"missing {SECTION}:") from exc

    root: dict[str, Any] = {}
    stack: list[tuple[int, dict[str, Any]]] = [(-1, root)]
    for line in lines[start + 1 :]:
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        indent = len(line) - len(line.lstrip(" "))
        if indent == 0:
            break
        match = re.fullmatch(r"\s*([A-Za-z_][A-Za-z0-9_]*):(?:\s+(.*))?", line)
        if not match:
            raise ValueError(f"unsupported {SECTION} syntax: {line!r}")
        key, raw = match.groups()
        while stack[-1][0] >= indent:
            stack.pop()
        parent = stack[-1][1]
        if key in parent:
            raise ValueError(f"duplicate {SECTION} key: {key}")
        if raw is None:
            child: dict[str, Any] = {}
            parent[key] = child
            stack.append((indent, child))
        else:
            parent[key] = scalar(raw)
    return root


def mapping(parent: dict[str, Any], key: str) -> dict[str, Any]:
    value = parent.get(key)
    if not isinstance(value, dict):
        raise ValueError(f"{SECTION}.{key} must be a mapping")
    return value


def compare_fields(
    failures: list[str],
    prefix: str,
    actual: dict[str, Any],
    expected: dict[str, Any],
) -> None:
    for key, wanted in expected.items():
        if key not in actual:
            failures.append(f"{prefix}.{key}: missing")
        elif actual[key] != wanted:
            failures.append(f"{prefix}.{key}: expected {wanted!r}, got {actual[key]!r}")


def compare_live(
    policy: dict[str, Any],
    policy_text: str,
    repo: dict[str, Any],
    ruleset: dict[str, Any],
) -> list[str]:
    failures: list[str] = []
    compare_fields(failures, "repository", repo, mapping(policy, "repository"))

    expected = mapping(policy, "ruleset")
    scalar_keys = ("id", "name", "target", "source_type", "source", "enforcement")
    compare_fields(
        failures,
        "ruleset",
        ruleset,
        {key: expected[key] for key in scalar_keys},
    )

    ref_name = ruleset.get("conditions", {}).get("ref_name")
    if not isinstance(ref_name, dict):
        failures.append("ruleset.conditions.ref_name: missing")
    else:
        compare_fields(
            failures,
            "ruleset.conditions.ref_name",
            ref_name,
            mapping(expected, "ref_name"),
        )

    if "bypass_actors" not in ruleset:
        failures.append(
            "ruleset.bypass_actors: hidden by caller authority; insufficient evidence. "
            "Configure LIVE_SETTINGS_AUDIT_TOKEN with ruleset write visibility."
        )
    elif ruleset["bypass_actors"] != expected.get("bypass_actors"):
        failures.append(
            f"ruleset.bypass_actors: expected {expected.get('bypass_actors')!r}, "
            f"got {ruleset['bypass_actors']!r}"
        )

    rules = ruleset.get("rules")
    if not isinstance(rules, list):
        return failures + ["ruleset.rules: missing"]
    by_type: dict[str, dict[str, Any]] = {}
    for rule in rules:
        if not isinstance(rule, dict) or not isinstance(rule.get("type"), str):
            failures.append(f"ruleset.rules: malformed entry {rule!r}")
            continue
        if rule["type"] in by_type:
            failures.append(f"ruleset.rules: duplicate type {rule['type']!r}")
        by_type[rule["type"]] = rule

    wanted_types = expected.get("rule_types")
    if sorted(by_type) != sorted(wanted_types if isinstance(wanted_types, list) else []):
        failures.append(
            f"ruleset.rules types: expected {wanted_types!r}, got {sorted(by_type)!r}"
        )

    for rule_type in ("pull_request", "required_status_checks"):
        params = by_type.get(rule_type, {}).get("parameters")
        if not isinstance(params, dict):
            failures.append(f"ruleset.{rule_type}.parameters: missing")
            continue
        expected_params = mapping(expected, rule_type)
        compare_fields(
            failures,
            f"ruleset.{rule_type}",
            params,
            expected_params,
        )
        expected_keys = set(expected_params)
        if rule_type == "required_status_checks":
            expected_keys.add("required_status_checks")
        if set(params) != expected_keys:
            failures.append(
                f"ruleset.{rule_type} parameter keys: "
                f"expected {sorted(expected_keys)!r}, got {sorted(params)!r}"
            )

    status = by_type.get("required_status_checks", {}).get("parameters", {})
    required = status.get("required_status_checks")
    if not isinstance(required, list):
        failures.append("ruleset.required_status_checks.required_status_checks: missing")
    else:
        integration_id = expected.get("status_check_integration_id")
        actual = sorted(
            (item.get("context"), item.get("integration_id"))
            for item in required
            if isinstance(item, dict)
        )
        contexts = [item["context"] for item in required_entries(policy_text)]
        wanted = sorted((context, integration_id) for context in contexts)
        if actual != wanted:
            failures.append(
                "ruleset required checks: "
                f"expected {wanted!r}, got {actual!r}"
            )
    return failures


def on_block(text: str) -> list[str]:
    lines = text.splitlines()
    try:
        start = next(i for i, line in enumerate(lines) if line.rstrip() == "on:")
    except StopIteration:
        return []
    block: list[str] = []
    for line in lines[start + 1 :]:
        if line.strip() and len(line) - len(line.lstrip(" ")) == 0:
            break
        block.append(line)
    return block


def check_workflow(policy: dict[str, Any], text: str) -> list[str]:
    failures: list[str] = []
    block = on_block(text)
    triggers = {
        m.group(1)
        for line in block
        if (m := re.fullmatch(r"  ([A-Za-z_][A-Za-z0-9_]*):\s*", line))
    }
    if triggers != {"push", "schedule"}:
        failures.append(f"workflow triggers: expected push+schedule only, got {sorted(triggers)!r}")
    block_text = "\n".join(block)
    if not re.search(r"(?m)^  push:\s*\n    branches:\s*\[\s*main\s*\]\s*$", block_text):
        failures.append("workflow push trigger must target exactly [ main ]")
    crons = re.findall(r'''(?m)^\s*-\s*cron:\s*['"]([^'"]+)['"]\s*$''', block_text)
    if crons != [policy.get("scheduleCron")]:
        failures.append(f"workflow cron: expected {[policy.get('scheduleCron')]!r}, got {crons!r}")
    if re.search(r"(?m)^\s*continue-on-error\s*:", text):
        failures.append("workflow must not use continue-on-error")
    if "python3 .github/workflow-policy/check_live_settings.py .github/merge-gate-policy.yml" not in text:
        failures.append("workflow must invoke the canonical live-settings checker")
    return failures


def api_get(url: str, token: str | None) -> dict[str, Any]:
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "zMyBatis-live-settings-audit",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            value = json.load(response)
    except (urllib.error.URLError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"GitHub API read failed: {exc}") from exc
    if not isinstance(value, dict):
        raise RuntimeError(f"GitHub API returned non-object JSON from {url}")
    return value


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("policy", type=Path)
    parser.add_argument("--static-only", action="store_true")
    args = parser.parse_args(argv)

    text = args.policy.read_text(encoding="utf-8")
    try:
        policy = parse_policy(text)
        workflow_rel = policy["workflow"]
        if not isinstance(workflow_rel, str):
            raise ValueError("liveSettingsAudit.workflow must be a string")
    except (KeyError, ValueError) as exc:
        print(f"live-settings policy error: {exc}", file=sys.stderr)
        return 2

    repo_root = args.policy.resolve().parents[1]
    workflow_path = repo_root / workflow_rel
    if not workflow_path.is_file():
        print(f"live-settings workflow missing: {workflow_rel}", file=sys.stderr)
        return 1
    failures = check_workflow(policy, workflow_path.read_text(encoding="utf-8"))
    if failures:
        print("live-settings workflow drift:")
        for failure in failures:
            print(f"  - {failure}")
        return 1
    if args.static_only:
        print("OK: recurring live-settings workflow ownership is intact.")
        return 0

    repository = os.environ.get("GITHUB_REPOSITORY")
    if not repository or "/" not in repository:
        print("GITHUB_REPOSITORY is required", file=sys.stderr)
        return 2
    api = os.environ.get("GITHUB_API_URL", "https://api.github.com").rstrip("/")
    token = os.environ.get("LIVE_SETTINGS_AUDIT_TOKEN") or os.environ.get("GITHUB_TOKEN")
    try:
        ruleset_id = mapping(policy, "ruleset")["id"]
        repo_json = api_get(f"{api}/repos/{repository}", token)
        ruleset_json = api_get(f"{api}/repos/{repository}/rulesets/{ruleset_id}", token)
        failures = compare_live(policy, text, repo_json, ruleset_json)
    except (KeyError, ValueError, RuntimeError) as exc:
        print(f"live-settings audit failed closed: {exc}", file=sys.stderr)
        return 1
    if failures:
        print("live repository/ruleset drift:")
        for failure in failures:
            print(f"  - {failure}")
        return 1
    print("OK: live repository/ruleset settings match merge-gate-policy.yml.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
