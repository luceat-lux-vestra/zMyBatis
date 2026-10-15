#!/usr/bin/env python3
"""Fail-closed exact-source-SHA CodeQL release preflight.

The release candidate is a protected-main ancestor, independently of the
workflow_dispatch event SHA. Reuse authenticated completed evidence, join an
eligible in-flight scan, or dispatch *one* trusted workflow and wait. This file
never publishes a release or reads publication credentials.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from unittest.mock import patch

from check_codeql_sarif import check

LANGUAGES = ("actions", "java-kotlin")
SHA = re.compile(r"^[0-9a-f]{40}$")
TAG = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.+-]+)?\Z")
WORKFLOW = "codeql.yml"
RUN_PREFIX = "CodeQL source "
WAIT_SECONDS = 80 * 60
POLL_SECONDS = 15


def require(ok: bool, message: str) -> None:
    if not ok:
        raise ValueError(message)


def gh_json(endpoint: str) -> dict:
    result = json.loads(subprocess.check_output(["gh", "api", endpoint], text=True))
    require(isinstance(result, dict), f"invalid API response: {endpoint}")
    return result


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], text=True).strip()


def verify_source(sha: str, tag: str, repo: str) -> None:
    require(bool(SHA.fullmatch(sha)), "release candidate must be an exact SHA")
    require(bool(TAG.fullmatch(tag)), "release tag must be v-prefixed SemVer")
    require(repo == os.environ.get("GITHUB_REPOSITORY"), "repository mismatch")
    require(git("rev-parse", "HEAD") == sha, "release checkout SHA mismatch")
    require(git("rev-parse", f"refs/tags/{tag}^{{commit}}") == sha,
            "tag or release candidate SHA mismatch")
    # Protected main and protected publication tag are verified independently
    # by the repository rulesets. Never require the tag SHA to be today's HEAD.
    subprocess.run(["git", "fetch", "--no-tags", "origin", "main"], check=True)
    require(subprocess.run(["git", "merge-base", "--is-ancestor", sha, "FETCH_HEAD"],
                           check=False).returncode == 0,
            "candidate is not in current protected main history")


def eligible_run(run: dict, sha: str, workflow_id: int) -> bool:
    if not isinstance(run, dict):
        return False
    if (run.get("workflow_id") != workflow_id
            or run.get("path") != ".github/workflows/codeql.yml"
            or run.get("head_branch") != "main"
            or run.get("event") not in {"schedule", "workflow_dispatch"}):
        return False
    # Before the rollout, exact event SHA was mandated in the scanner; preserve
    # that trusted legacy proof. New runs advertise the *source* SHA separately.
    modern = run.get("display_title") == RUN_PREFIX + sha
    legacy = run.get("head_sha") == sha and run.get("display_title") == "CodeQL"
    return modern or legacy


def verify_run(run: dict, sha: str, workflow_id: int) -> bool:
    require(eligible_run(run, sha, workflow_id), "wrong CodeQL run source or identity")
    require(run.get("run_attempt") == 1, "rerun/attempt ambiguity")
    require(isinstance(run.get("id"), int) and run["id"] > 0,
            "invalid CodeQL run ID")
    require(run.get("status") == "completed" and run.get("conclusion") == "success",
            "CodeQL workflow did not complete successfully")
    require(isinstance(run.get("head_sha"), str)
            and bool(SHA.fullmatch(run["head_sha"])), "invalid workflow event SHA")
    return run.get("display_title") == RUN_PREFIX + sha


def verify_jobs(payload: dict, sha: str, modern: bool) -> None:
    jobs = payload.get("jobs")
    require(isinstance(jobs, list) and payload.get("total_count") == len(jobs),
            "incomplete CodeQL job listing")
    require(len(jobs) == len(LANGUAGES), "expected exactly two CodeQL language jobs")
    for language in LANGUAGES:
        matches = [j for j in jobs if isinstance(j, dict)
                   and j.get("name") == f"CodeQL ({language})"]
        require(len(matches) == 1, f"missing or duplicate {language} CodeQL job")
        job = matches[0]
        require(job.get("status") == "completed" and job.get("conclusion") == "success",
                f"{language} CodeQL job failed, skipped or was cancelled")
        # GitHub job.head_sha is the *event* SHA, not the historical checkout.
        require(job.get("head_sha") is None or bool(SHA.fullmatch(job["head_sha"])),
                f"{language} has malformed job event SHA")
        steps = job.get("steps")
        require(isinstance(steps, list), f"no {language} job-step attestation")
        required = ["Verify exact checkout"]
        if modern:
            required += ["Verify protected-main ancestry", "Record analyzed source",
                         "Verify protected-main ancestry after analysis"]
        for name in required:
            matches = [s for s in steps if isinstance(s, dict) and s.get("name") == name]
            require(len(matches) == 1 and matches[0].get("conclusion") == "success",
                    f"{language} missing successful {name} attestation")


def verify_artifacts(payload: dict, sha: str, run_id: int, modern: bool) -> dict:
    items = payload.get("artifacts")
    require(isinstance(items, list) and payload.get("total_count") == len(items),
            "incomplete CodeQL artifact listing")
    found = {}
    for language in LANGUAGES:
        names = {"sarif": f"codeql-{sha}-{language}"}
        if modern:
            names["source"] = f"codeql-source-{sha}-{language}"
        for kind, name in names.items():
            matches = [a for a in items if isinstance(a, dict) and a.get("name") == name]
            require(len(matches) == 1, f"missing or duplicate {language} {kind} artifact")
            art = matches[0]
            require(art.get("expired") is False and isinstance(art.get("size_in_bytes"), int)
                    and art["size_in_bytes"] > 0, f"{language} {kind} artifact expired or empty")
            if isinstance(art.get("workflow_run"), dict):
                require(art["workflow_run"].get("id") == run_id,
                        f"{language} {kind} belongs to another run")
        found[language] = names
    return found


def download(run_id: int, repo: str, name: str, destination: Path) -> None:
    subprocess.run(["gh", "run", "download", str(run_id), "-R", repo,
                    "-n", name, "-D", str(destination)], check=True)


def verify_manifest(root: Path, sha: str, run: dict, language: str) -> None:
    files = [x for x in root.rglob("*") if x.is_file()]
    require(len(files) == 1 and files[0].name == "source-identity.json",
            f"{language} source manifest missing or ambiguous")
    document = json.loads(files[0].read_text(encoding="utf-8"))
    require(isinstance(document, dict), "source manifest is malformed")
    expected = {"schema": 1, "candidate_sha": sha, "checkout_sha": sha,
                "event_sha": run["head_sha"], "run_id": run["id"],
                "run_attempt": 1, "language": language}
    require(document == expected, f"{language} actual checkout identity mismatch")


def verify_evidence(run: dict, sha: str, workflow_id: int, repo: str) -> dict:
    modern = verify_run(run, sha, workflow_id)
    rid = run["id"]
    # The event SHA must itself come from the protected-main history. New
    # source identity is independently attested by the verified checkout step.
    subprocess.run(["git", "fetch", "--no-tags", "origin", "main"], check=True)
    require(subprocess.run(["git", "merge-base", "--is-ancestor",
                            run["head_sha"], "FETCH_HEAD"], check=False).returncode == 0,
            "CodeQL event SHA is not trusted main history")
    jobs = gh_json(f"repos/{repo}/actions/runs/{rid}/jobs?filter=latest&per_page=100")
    verify_jobs(jobs, sha, modern)
    artifacts = gh_json(f"repos/{repo}/actions/runs/{rid}/artifacts?per_page=100")
    names = verify_artifacts(artifacts, sha, rid, modern)
    proof = {}
    with tempfile.TemporaryDirectory(prefix="codeql-preflight-") as temp:
        root = Path(temp)
        for lang, entry in names.items():
            if modern:
                source_dir = root / lang / "source"
                source_dir.mkdir(parents=True)
                download(rid, repo, entry["source"], source_dir)
                verify_manifest(source_dir, sha, run, lang)
            sarif_dir = root / lang / "sarif"
            sarif_dir.mkdir(parents=True)
            download(rid, repo, entry["sarif"], sarif_dir)
            proof[lang] = check(sarif_dir, lang, sha)
    return {"run_id": rid, "attempt": 1, "candidate_sha": sha, "event_sha": run["head_sha"],
            "mode": "source-attested" if modern else "legacy-exact-event-sha",
            "languages": proof, "high_or_critical": 0}


def find_runs(repo: str, sha: str, workflow_id: int) -> list[dict]:
    found = []
    # Searching by event head_sha would exclude historical-commit scans.
    # A bounded recent search may miss old evidence; that means a fresh scan.
    for page in range(1, 6):
        data = gh_json(
            f"repos/{repo}/actions/workflows/{WORKFLOW}/runs?per_page=100&page={page}"
        )
        runs = data.get("workflow_runs")
        require(isinstance(runs, list), "malformed workflow run listing")
        found.extend(x for x in runs if eligible_run(x, sha, workflow_id))
        if len(runs) < 100:
            break
    return sorted(found, key=lambda r: (r.get("created_at", ""), r.get("id", 0)),
                  reverse=True)


def select_run(repo: str, sha: str, workflow_id: int) -> dict | None:
    runs = find_runs(repo, sha, workflow_id)
    active = [r for r in runs if r.get("status") != "completed"]
    require(len(active) <= 1, "multiple concurrent CodeQL scans for candidate SHA")
    return runs[0] if runs else None


def dispatch(repo: str, sha: str) -> None:
    subprocess.run(["gh", "api", "-X", "POST",
                    f"repos/{repo}/actions/workflows/{WORKFLOW}/dispatches",
                    "-f", "ref=main", "-f", f"inputs[candidate_sha]={sha}"], check=True)


def run_preflight(sha: str, tag: str, repo: str,
                  timeout: int = WAIT_SECONDS, poll: int = POLL_SECONDS) -> dict:
    verify_source(sha, tag, repo)
    workflow = gh_json(f"repos/{repo}/actions/workflows/{WORKFLOW}")
    workflow_id = workflow.get("id")
    require(isinstance(workflow_id, int) and workflow_id > 0
            and workflow.get("state") == "active", "trusted CodeQL workflow unavailable")
    selected = select_run(repo, sha, workflow_id)
    dispatched = False
    if selected is None:
        # The destination workflow only accepts trusted protected-main sources.
        # All independent release jobs are serialized per release tag, and the
        # scanner itself serializes identical SHA requests via concurrency.
        dispatch(repo, sha)
        dispatched = True
        print(f"Dispatched {WORKFLOW} for exact source {sha}", flush=True)
    else:
        print(f"Joining/reusing CodeQL run {selected['id']} for {sha}", flush=True)

    deadline = time.monotonic() + timeout
    discovery_deadline = min(deadline, time.monotonic() + 150)
    while True:
        current = select_run(repo, sha, workflow_id)
        if current is not None:
            require(current.get("run_attempt") == 1,
                    "CodeQL rerun/attempt ambiguity")
            status = current.get("status")
            if status == "completed":
                require(current.get("conclusion") == "success",
                        f"CodeQL run {current.get('id')} ended: {current.get('conclusion')}")
                result = verify_evidence(current, sha, workflow_id, repo)
                verify_source(sha, tag, repo)
                result["execution"] = "dispatched" if dispatched else "reused-or-joined"
                return result
            require(status in {"queued", "in_progress", "waiting", "pending", "requested"},
                    f"unrecognized CodeQL run status: {status}")
        elif not dispatched:
            raise ValueError("previously visible CodeQL run vanished")
        if time.monotonic() >= deadline:
            raise ValueError("CodeQL preflight timed out before complete verified evidence")
        if current is None and time.monotonic() >= discovery_deadline:
            raise ValueError("dispatched CodeQL run did not appear within 150 seconds")
        time.sleep(min(poll, max(0.1, deadline - time.monotonic())))


def self_test() -> None:
    sha = "a" * 40
    other = "b" * 40
    modern = {"workflow_id": 2, "path": ".github/workflows/codeql.yml",
              "head_sha": other, "head_branch": "main", "event": "workflow_dispatch",
              "display_title": RUN_PREFIX + sha, "run_attempt": 1,
              "status": "completed", "conclusion": "success", "id": 17}
    legacy = dict(modern, head_sha=sha, display_title="CodeQL")
    assert verify_run(modern, sha, 2)
    assert not verify_run(legacy, sha, 2)
    steps = [{"name": name, "conclusion": "success"} for name in
             ("Verify exact checkout", "Verify protected-main ancestry",
              "Record analyzed source", "Verify protected-main ancestry after analysis")]
    jobs = {"total_count": 2, "jobs": [
        {"name": f"CodeQL ({language})", "status": "completed",
         "conclusion": "success", "head_sha": other, "steps": steps}
        for language in LANGUAGES]}
    verify_jobs(jobs, sha, True)
    artifacts = {"total_count": 4, "artifacts": [
        {"name": f"codeql-{sha}-{lang}" if kind == "sarif" else
         f"codeql-source-{sha}-{lang}", "expired": False, "size_in_bytes": 100,
         "workflow_run": {"id": 17}}
        for lang in LANGUAGES for kind in ("sarif", "source")]}
    verify_artifacts(artifacts, sha, 17, True)
    with tempfile.TemporaryDirectory() as directory:
        item = Path(directory) / "source-identity.json"
        doc = {"schema": 1, "candidate_sha": sha, "checkout_sha": sha,
               "event_sha": other, "run_id": 17, "run_attempt": 1, "language": "actions"}
        item.write_text(json.dumps(doc))
        verify_manifest(Path(directory), sha, modern, "actions")

        def reject(fn, fragment):
            try:
                fn()
            except (ValueError, json.JSONDecodeError) as error:
                assert fragment in str(error), (fragment, str(error))
            else:
                raise AssertionError("invalid CodeQL evidence accepted: " + fragment)

        for change, fragment in [
            ({"checkout_sha": other}, "identity mismatch"),
            ({"candidate_sha": other}, "identity mismatch"),
            ({"event_sha": sha}, "identity mismatch"),
            ({"run_attempt": 2}, "identity mismatch"),
            ({"run_id": 9}, "identity mismatch"),
            ({"language": "java-kotlin"}, "identity mismatch"),
        ]:
            item.write_text(json.dumps(dict(doc, **change)))
            reject(lambda: verify_manifest(Path(directory), sha, modern, "actions"),
                   fragment)
        item.write_text("{broken")
        reject(lambda: verify_manifest(Path(directory), sha, modern, "actions"),
               "Expecting property name")
    for change, frag in [
        ({"head_branch": "feature"}, "identity"),
        ({"event": "pull_request"}, "identity"),
        ({"display_title": "CodeQL source " + other}, "identity"),
        ({"run_attempt": 2}, "attempt"),
        ({"conclusion": "cancelled"}, "successfully"),
        ({"workflow_id": 4}, "identity"),
    ]:
        reject(lambda change=change: verify_run(dict(modern, **change), sha, 2), frag)
    bad_jobs = json.loads(json.dumps(jobs))
    bad_jobs["jobs"][0]["steps"][1]["conclusion"] = "failure"
    reject(lambda: verify_jobs(bad_jobs, sha, True), "attestation")
    bad_jobs = json.loads(json.dumps(jobs))
    bad_jobs["jobs"][0]["name"] = "CodeQL (other)"
    reject(lambda: verify_jobs(bad_jobs, sha, True), "missing or duplicate")
    for change, fragment in [
        ({"expired": True}, "expired or empty"),
        ({"size_in_bytes": 0}, "expired or empty"),
        ({"workflow_run": {"id": 99}}, "another run"),
    ]:
        bad = json.loads(json.dumps(artifacts))
        bad["artifacts"][0].update(change)
        reject(lambda bad=bad: verify_artifacts(bad, sha, 17, True), fragment)
    bad = dict(artifacts, total_count=5)
    reject(lambda: verify_artifacts(bad, sha, 17, True), "incomplete")

    # Exercise the state machine, including registration latency, reuse,
    # in-progress joining, explicit failures, and timeout without sleeping.
    def exercise(sequence, expected_dispatches, failure=None):
        snapshots = iter(sequence)
        last = sequence[-1]
        scans = []
        def choose(*args):
            try:
                entry = next(snapshots)
            except StopIteration:
                entry = last
            scans.append(entry)
            return entry
        with patch(__name__ + ".verify_source"), \
             patch(__name__ + ".gh_json", return_value={"id": 2, "state": "active"}), \
             patch(__name__ + ".select_run", side_effect=choose), \
             patch(__name__ + ".dispatch") as invoke, \
             patch(__name__ + ".verify_evidence", return_value={"run_id": 17}), \
             patch(__name__ + ".time.sleep"), \
             patch(__name__ + ".time.monotonic",
                   side_effect=(x for x in range(0, 10000))):
            if failure:
                reject(lambda: run_preflight(sha, "v1.0.0", "owner/repo",
                                           timeout=50, poll=1), failure)
            else:
                result = run_preflight(sha, "v1.0.0", "owner/repo",
                                       timeout=50, poll=1)
                assert result["run_id"] == 17
            assert invoke.call_count == expected_dispatches

    active = dict(modern, status="in_progress", conclusion=None)
    queued = dict(modern, status="queued", conclusion=None)
    failed = dict(modern, conclusion="failure")
    exercise([modern, modern], 0)
    exercise([None, None, queued, active, modern], 1)
    exercise([active, active, modern], 0)
    exercise([failed, failed], 0, "ended")
    exercise([None] * 10, 1, "timed out")
    print("Exact-source CodeQL reuse/dispatch/join/wait negative controls passed")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--sha")
    parser.add_argument("--tag")
    args = parser.parse_args()
    try:
        if args.self_test:
            self_test()
            return
        if not args.sha or not args.tag:
            parser.error("--sha and --tag are required")
        proof = run_preflight(args.sha, args.tag,
                              os.environ.get("GITHUB_REPOSITORY", ""))
        print("Exact source CodeQL preflight accepted: " + json.dumps(proof, sort_keys=True))
        if os.environ.get("GITHUB_STEP_SUMMARY"):
            with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as out:
                out.write("### Exact-source CodeQL preflight\n\n\`\`\`json\n")
                out.write(json.dumps(proof, indent=2, sort_keys=True))
                out.write("\n\`\`\`\n")
    except (ValueError, OSError, subprocess.CalledProcessError, json.JSONDecodeError,
            TypeError, KeyError) as error:
        print(f"::error::Release CodeQL preflight rejected: {error}", file=sys.stderr)
        raise SystemExit(1) from error


if __name__ == "__main__":
    main()
