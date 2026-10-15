#!/usr/bin/env python3
"""Require trustworthy exact-tag-SHA CodeQL evidence before first Marketplace publication.

Only invoked after the existing release provenance and recovery-state checks. A
successful workflow check alone is not evidence: authenticate one workflow run,
both actual matrix job results, and independently re-parse original SARIF bytes.
This program does not publish, sign, or mutate repository state.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

from check_codeql_sarif import check

LANGUAGES = ("actions", "java-kotlin")
SHA = re.compile(r"^[0-9a-f]{40}$")


def require(ok: bool, message: str) -> None:
    if not ok:
        raise ValueError(message)


def gh_json(endpoint: str) -> dict:
    output = subprocess.check_output(["gh", "api", endpoint], text=True)
    result = json.loads(output)
    require(isinstance(result, dict), f"invalid API response: {endpoint}")
    return result


def verify_run(run: dict, sha: str, workflow_id: int) -> None:
    require(isinstance(run, dict), "missing CodeQL workflow run")
    require(run.get("workflow_id") == workflow_id, "wrong workflow")
    require(run.get("head_sha") == sha, "CodeQL run SHA mismatch")
    require(run.get("head_branch") == "main", "CodeQL run did not originate on main")
    require(run.get("event") in {"schedule", "workflow_dispatch"}, "untrusted CodeQL run event")
    require(run.get("run_attempt") == 1, "rerun/attempt ambiguity")
    require(run.get("status") == "completed" and run.get("conclusion") == "success",
            "CodeQL workflow did not complete successfully")
    require(isinstance(run.get("id"), int) and run["id"] > 0, "invalid CodeQL run ID")


def verify_jobs(payload: dict, sha: str) -> None:
    jobs = payload.get("jobs")
    require(isinstance(jobs, list) and payload.get("total_count") == len(jobs),
            "incomplete CodeQL job listing")
    require(len(jobs) == len(LANGUAGES), "expected exactly two CodeQL language jobs")
    for language in LANGUAGES:
        matches = [job for job in jobs if isinstance(job, dict)
                   and job.get("name") == f"CodeQL ({language})"]
        require(len(matches) == 1, f"missing or duplicate {language} CodeQL job")
        job = matches[0]
        require(job.get("status") == "completed" and job.get("conclusion") == "success",
                f"{language} CodeQL job failed, skipped or was cancelled")
        if "head_sha" in job:
            require(job["head_sha"] == sha, f"{language} job SHA mismatch")


def verify_artifacts(payload: dict, sha: str, run_id: int) -> dict:
    artifacts = payload.get("artifacts")
    require(isinstance(artifacts, list)
            and payload.get("total_count") == len(artifacts),
            "incomplete CodeQL artifact listing")
    validated = {}
    for language in LANGUAGES:
        name = f"codeql-{sha}-{language}"
        matches = [item for item in artifacts if isinstance(item, dict)
                   and item.get("name") == name]
        require(len(matches) == 1, f"missing or duplicate original {language} SARIF")
        item = matches[0]
        require(item.get("expired") is False and isinstance(item.get("size_in_bytes"), int)
                and item["size_in_bytes"] > 0,
                f"{language} SARIF artifact expired or empty")
        if isinstance(item.get("workflow_run"), dict):
            require(item["workflow_run"].get("id") == run_id,
                    f"{language} SARIF belongs to another run")
        validated[language] = name
    return validated


def download(run_id: int, repo: str, name: str, directory: Path) -> None:
    subprocess.run(["gh", "run", "download", str(run_id), "-R", repo,
                    "-n", name, "-D", str(directory)], check=True)


def verify_release(sha: str, tag: str, repo: str) -> dict:
    require(bool(SHA.fullmatch(sha)), "release candidate must be an exact SHA")
    require(bool(re.fullmatch(r"v[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.+-]+)?", tag)),
            "release tag is not a SemVer v-prefixed tag")
    require(repo == os.environ.get("GITHUB_REPOSITORY"),
            "repository mismatch")
    current = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    tagged = subprocess.check_output(
        ["git", "rev-parse", f"refs/tags/{tag}^{{commit}}"], text=True
    ).strip()
    require(current == sha == tagged, "checkout, tag and candidate SHA disagree")

    workflow = gh_json(f"repos/{repo}/actions/workflows/codeql.yml")
    workflow_id = workflow.get("id")
    require(isinstance(workflow_id, int), "CodeQL workflow identity missing")
    runs = gh_json(f"repos/{repo}/actions/workflows/codeql.yml/runs?head_sha={sha}&per_page=100")
    candidates = [run for run in runs.get("workflow_runs", [])
                  if isinstance(run, dict)
                  and run.get("head_sha") == sha
                  and run.get("workflow_id") == workflow_id
                  and run.get("event") in {"schedule", "workflow_dispatch"}]
    require(bool(candidates), "no CodeQL run for exact release SHA")
    candidates.sort(key=lambda run: (run.get("created_at", ""), run.get("id", 0)), reverse=True)
    # No silent fallback to a second run when the selected newest exact-SHA
    # attempt failed: investigate explicitly before publication.
    selected = candidates[0]
    verify_run(selected, sha, workflow_id)
    run_id = selected["id"]

    jobs = gh_json(f"repos/{repo}/actions/runs/{run_id}/jobs?filter=latest&per_page=100")
    verify_jobs(jobs, sha)
    artifact_payload = gh_json(f"repos/{repo}/actions/runs/{run_id}/artifacts?per_page=100")
    names = verify_artifacts(artifact_payload, sha, run_id)

    evidence = {}
    with tempfile.TemporaryDirectory(prefix="codeql-release-") as temporary:
        root = Path(temporary)
        for language, name in names.items():
            destination = root / language
            destination.mkdir()
            download(run_id, repo, name, destination)
            evidence[language] = check(destination, language, sha)
    # Reconfirm release source after IO. Protected tag may not be retargeted;
    # a moved checkout or mismatch still fails closed before mutation.
    require(subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip() == sha,
            "release checkout moved during preflight")
    return {"run_id": run_id, "attempt": 1, "candidate_sha": sha,
            "languages": evidence, "high_or_critical": 0}


def self_test() -> None:
    sha = "a" * 40
    run = {"workflow_id": 2, "head_sha": sha, "head_branch": "main",
           "event": "schedule", "run_attempt": 1, "status": "completed",
           "conclusion": "success", "id": 17}
    jobs = {"total_count": 2, "jobs": [
        {"name": f"CodeQL ({language})", "status": "completed",
         "conclusion": "success", "head_sha": sha} for language in LANGUAGES]}
    artifacts = {"total_count": 2, "artifacts": [
        {"name": f"codeql-{sha}-{language}", "expired": False,
         "size_in_bytes": 100, "workflow_run": {"id": 17}}
        for language in LANGUAGES]}
    verify_run(run, sha, 2)
    verify_jobs(jobs, sha)
    verify_artifacts(artifacts, sha, 17)

    def negative(call, error):
        try:
            call()
        except ValueError as exc:
            assert error in str(exc), (error, str(exc))
        else:
            raise AssertionError("invalid evidence accepted: " + error)

    for key, value, message in [
        ("head_sha", "b" * 40, "SHA mismatch"),
        ("head_branch", "feature", "main"),
        ("event", "pull_request", "event"),
        ("run_attempt", 2, "attempt"),
        ("conclusion", "failure", "successfully"),
        ("workflow_id", 3, "workflow"),
    ]:
        bad = dict(run, **{key: value})
        negative(lambda bad=bad: verify_run(bad, sha, 2), message)
    for change, message in [
        ({"name": "CodeQL (other)"}, "missing or duplicate"),
        ({"conclusion": "cancelled"}, "cancelled"),
        ({"head_sha": "b" * 40}, "SHA mismatch"),
    ]:
        bad = json.loads(json.dumps(jobs))
        bad["jobs"][0].update(change)
        negative(lambda bad=bad: verify_jobs(bad, sha), message)
    for change, message in [
        ({"expired": True}, "expired or empty"),
        ({"workflow_run": {"id": 3}}, "another run"),
        ({"size_in_bytes": 0}, "expired or empty"),
    ]:
        bad = json.loads(json.dumps(artifacts))
        bad["artifacts"][0].update(change)
        negative(lambda bad=bad: verify_artifacts(bad, sha, 17), message)
    bad = dict(artifacts, total_count=3)
    negative(lambda: verify_artifacts(bad, sha, 17), "incomplete")
    print("Release exact-SHA CodeQL provenance negative controls passed")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--sha")
    parser.add_argument("--tag")
    args = parser.parse_args()
    try:
        if args.self_test:
            self_test()
        else:
            if not args.sha or not args.tag:
                parser.error("--sha and --tag are required")
            proof = verify_release(args.sha, args.tag, os.environ.get("GITHUB_REPOSITORY", ""))
            print("Exact release CodeQL preflight accepted: " +
                  json.dumps(proof, sort_keys=True))
            summary = os.environ.get("GITHUB_STEP_SUMMARY")
            if summary:
                with open(summary, "a", encoding="utf-8") as stream:
                    stream.write("### Exact-SHA CodeQL preflight\\n\\n```json\\n")
                    stream.write(json.dumps(proof, indent=2, sort_keys=True))
                    stream.write("\\n```\\n")
    except (ValueError, OSError, subprocess.CalledProcessError,
            json.JSONDecodeError, TypeError, KeyError) as exc:
        print(f"::error::Release security preflight rejected: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc


if __name__ == "__main__":
    main()
