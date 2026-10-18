#!/usr/bin/env python3
"""Fail-closed exact-source-SHA Qodana release preflight.

Reuse complete trusted Qodana evidence for the immutable release source, join
one eligible in-flight scan, or dispatch one trusted full scan and wait. The
preflight never publishes, signs, or reads Marketplace credentials.
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

from check_qodana_sarif import check as check_sarif

SHA = re.compile(r"^[0-9a-f]{40}$")
TAG = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.+-]+)?\Z")
WORKFLOW = "qodana-full.yml"
RUN_PREFIX = "Qodana source "
GUARD_JOB = "Qodana schedule guard"
SCAN_JOB = "Qodana full scan"
WAIT_SECONDS = 50 * 60
POLL_SECONDS = 15
QODANA_ACTION = "JetBrains/qodana-action@3e8d76ff0e0241618abd808682f426afe84bab3f"


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
    refs = dict(reversed(line.split("\t", 1)) for line in git(
        "ls-remote", "--tags", "origin", f"refs/tags/{tag}",
        f"refs/tags/{tag}^{{}}").splitlines())
    remote_tag = refs.get(f"refs/tags/{tag}^{{}}", refs.get(f"refs/tags/{tag}"))
    require(remote_tag == sha, "remote release tag moved or is unavailable")
    subprocess.run(["git", "fetch", "--no-tags", "origin", "main"], check=True)
    require(subprocess.run(
        ["git", "merge-base", "--is-ancestor", sha, "FETCH_HEAD"],
        check=False,
    ).returncode == 0, "candidate is not in current protected main history")


def eligible_run(run: dict, sha: str, workflow_id: int) -> bool:
    return bool(
        isinstance(run, dict)
        and run.get("workflow_id") == workflow_id
        and run.get("path") == ".github/workflows/qodana-full.yml"
        and run.get("head_branch") == "main"
        and run.get("event") in {"schedule", "workflow_dispatch"}
        and run.get("display_title") == RUN_PREFIX + sha
    )


def verify_run(run: dict, sha: str, workflow_id: int) -> None:
    require(eligible_run(run, sha, workflow_id), "wrong Qodana run source or identity")
    require(run.get("run_attempt") == 1, "rerun/attempt ambiguity")
    require(isinstance(run.get("id"), int) and run["id"] > 0, "invalid Qodana run ID")
    require(
        run.get("status") == "completed" and run.get("conclusion") == "success",
        "Qodana workflow did not complete successfully",
    )
    require(
        isinstance(run.get("head_sha"), str) and bool(SHA.fullmatch(run["head_sha"])),
        "invalid Qodana workflow event SHA",
    )


def verify_jobs(payload: dict) -> None:
    jobs = payload.get("jobs")
    require(
        isinstance(jobs, list) and payload.get("total_count") == len(jobs),
        "incomplete Qodana job listing",
    )
    names = {j.get("name") for j in jobs if isinstance(j, dict)}
    require(names <= {GUARD_JOB, SCAN_JOB}, "unexpected Qodana workflow job")
    guards = [j for j in jobs if isinstance(j, dict) and j.get("name") == GUARD_JOB]
    scans = [j for j in jobs if isinstance(j, dict) and j.get("name") == SCAN_JOB]
    require(len(guards) == 1, "missing or duplicate Qodana schedule guard job")
    require(len(scans) == 1, "missing or duplicate Qodana full scan job")
    require(
        guards[0].get("status") == "completed" and guards[0].get("conclusion") == "success",
        "Qodana schedule guard did not complete successfully",
    )
    scan = scans[0]
    require(
        scan.get("status") == "completed" and scan.get("conclusion") == "success",
        "Qodana full scan failed, skipped or was cancelled",
    )
    steps = scan.get("steps")
    require(isinstance(steps, list), "no Qodana job-step attestation")
    required_steps = (
        "Verify exact protected-main candidate",
        "Verify exact checkout",
        "Qodana - Full Code Inspection",
        "Enforce full Qodana SARIF",
        "Retain verified original Qodana SARIF",
        "Record exact-source Qodana provenance",
        "Upload exact-source Qodana provenance",
    )
    for name in required_steps:
        matches = [s for s in steps if isinstance(s, dict) and s.get("name") == name]
        require(
            len(matches) == 1 and matches[0].get("conclusion") == "success",
            f"Qodana missing successful {name} attestation",
        )


def verify_artifacts(payload: dict, sha: str, run_id: int,
                     allow_missing: bool = False) -> dict[str, str] | None:
    items = payload.get("artifacts")
    require(
        isinstance(items, list) and payload.get("total_count") == len(items),
        "incomplete Qodana artifact listing",
    )
    names = {
        "sarif": f"qodana-{sha}-sarif",
        "source": f"qodana-source-{sha}",
    }
    missing = False
    for kind, name in names.items():
        matches = [a for a in items if isinstance(a, dict) and a.get("name") == name]
        require(len(matches) <= 1, f"duplicate Qodana {kind} artifact")
        if not matches:
            if allow_missing:
                missing = True
                continue
            require(False, f"missing Qodana {kind} artifact")
        artifact = matches[0]
        require(
            isinstance(artifact.get("size_in_bytes"), int)
            and artifact["size_in_bytes"] > 0,
            f"Qodana {kind} artifact empty or invalid",
        )
        if isinstance(artifact.get("workflow_run"), dict):
            require(
                artifact["workflow_run"].get("id") == run_id,
                f"Qodana {kind} artifact belongs to another run",
            )
        if artifact.get("expired") is True:
            if allow_missing:
                missing = True
                continue
            require(False, f"Qodana {kind} artifact expired")
        require(
            artifact.get("expired") is False,
            f"Qodana {kind} artifact has unknown expiry state",
        )
    return None if missing else names


def download(run_id: int, repo: str, name: str, destination: Path) -> None:
    subprocess.run([
        "gh", "run", "download", str(run_id), "-R", repo,
        "-n", name, "-D", str(destination),
    ], check=True)


def only_file(root: Path, expected_name: str, label: str) -> Path:
    files = [path for path in root.rglob("*") if path.is_file()]
    require(
        len(files) == 1 and files[0].name == expected_name,
        f"{label} missing or ambiguous",
    )
    return files[0]


def verify_manifest(root: Path, sha: str, run: dict, repo: str) -> None:
    path = only_file(root, "qodana-source-manifest.json", "Qodana source manifest")
    document = json.loads(path.read_text(encoding="utf-8"))
    require(isinstance(document, dict), "Qodana source manifest is malformed")
    expected = {
        "schema": 1,
        "candidate_sha": sha,
        "checkout_sha": sha,
        "event_name": run["event"],
        "event_sha": run["head_sha"],
        "repository": repo,
        "run_id": run["id"],
        "run_attempt": 1,
        "qodana_action": QODANA_ACTION,
        "profile": "qodana.recommended",
        "fail_threshold": 0,
    }
    require(document == expected, "Qodana actual checkout/source identity mismatch")


def verify_evidence(run: dict, sha: str, workflow_id: int, repo: str,
                    allow_missing: bool = False) -> dict | None:
    verify_run(run, sha, workflow_id)
    require(
        run.get("head_repository", {}).get("full_name") == repo,
        "Qodana head repository does not match trusted repository",
    )
    run_id = run["id"]
    subprocess.run(["git", "fetch", "--no-tags", "origin", "main"], check=True)
    require(
        subprocess.run(
            ["git", "merge-base", "--is-ancestor", run["head_sha"], "FETCH_HEAD"],
            check=False,
        ).returncode == 0,
        "Qodana event SHA is not trusted main history",
    )

    verify_jobs(gh_json(f"repos/{repo}/actions/runs/{run_id}/jobs?filter=latest&per_page=100"))
    names = verify_artifacts(
        gh_json(f"repos/{repo}/actions/runs/{run_id}/artifacts?per_page=100"),
        sha, run_id, allow_missing=allow_missing,
    )
    if names is None:
        return None

    with tempfile.TemporaryDirectory(prefix="qodana-preflight-") as temp:
        root = Path(temp)
        source_dir = root / "source"
        sarif_dir = root / "sarif"
        source_dir.mkdir()
        sarif_dir.mkdir()
        download(run_id, repo, names["source"], source_dir)
        download(run_id, repo, names["sarif"], sarif_dir)
        verify_manifest(source_dir, sha, run, repo)
        sarif_file = only_file(sarif_dir, "qodana.sarif.json", "Qodana SARIF")
        verdict = check_sarif(sarif_file)

    return {
        "run_id": run_id,
        "attempt": 1,
        "candidate_sha": sha,
        "event_sha": run["head_sha"],
        "sarif": verdict,
        "findings": 0,
    }


def find_runs(repo: str, sha: str, workflow_id: int) -> list[dict]:
    found: list[dict] = []
    for event in ("workflow_dispatch", "schedule"):
        for page in range(1, 11):
            data = gh_json(
                f"repos/{repo}/actions/workflows/{WORKFLOW}/runs"
                f"?event={event}&per_page=100&page={page}"
            )
            runs = data.get("workflow_runs")
            require(
                isinstance(runs, list) and len(runs) <= 100,
                "malformed Qodana workflow run listing",
            )
            require(all(isinstance(run, dict) for run in runs),
                    "malformed Qodana discovery record")
            found.extend(run for run in runs if eligible_run(run, sha, workflow_id))
            if len(runs) < 100:
                break
        else:
            raise ValueError("Qodana discovery exceeded the safe 1000-run bound")
    return sorted(
        found,
        key=lambda run: (run.get("created_at", ""), run.get("id", 0)),
        reverse=True,
    )


def intentional_schedule_skip(repo: str, run: dict) -> bool:
    if (
        run.get("event") != "schedule"
        or run.get("status") != "completed"
        or run.get("conclusion") != "success"
        or not isinstance(run.get("id"), int)
    ):
        return False
    payload = gh_json(f"repos/{repo}/actions/runs/{run['id']}/jobs?filter=latest&per_page=100")
    jobs = payload.get("jobs")
    require(
        isinstance(jobs, list) and payload.get("total_count") == len(jobs),
        "incomplete Qodana schedule-guard job listing",
    )
    guards = [j for j in jobs if isinstance(j, dict) and j.get("name") == GUARD_JOB]
    scans = [j for j in jobs if isinstance(j, dict) and j.get("name") == SCAN_JOB]
    return bool(
        len(guards) == 1
        and guards[0].get("conclusion") == "success"
        and len(scans) == 1
        and scans[0].get("conclusion") == "skipped"
    )


def select_run(repo: str, sha: str, workflow_id: int,
               ignored: set[int] | None = None) -> dict | None:
    runs = [
        run for run in find_runs(repo, sha, workflow_id)
        if not intentional_schedule_skip(repo, run)
    ]
    for run in runs:
        require(run.get("run_attempt") == 1,
                "ambiguous rerun/attempt for candidate Qodana scan")
        if run.get("status") == "completed":
            require(
                run.get("conclusion") == "success",
                f"Qodana run {run.get('id')} ended: {run.get('conclusion')}",
            )
    active = [run for run in runs if run.get("status") != "completed"]
    require(len(active) <= 1, "multiple concurrent Qodana scans for candidate SHA")
    if active:
        return active[0]
    return next(
        (run for run in runs if run.get("id") not in (ignored or set())),
        None,
    )


def dispatch(repo: str, sha: str) -> None:
    subprocess.run([
        "gh", "api", "-X", "POST",
        f"repos/{repo}/actions/workflows/{WORKFLOW}/dispatches",
        "-f", "ref=main",
        "-f", f"inputs[candidate_sha]={sha}",
    ], check=True)


def run_preflight(sha: str, tag: str, repo: str,
                  timeout: int = WAIT_SECONDS, poll: int = POLL_SECONDS) -> dict:
    verify_source(sha, tag, repo)
    workflow = gh_json(f"repos/{repo}/actions/workflows/{WORKFLOW}")
    workflow_id = workflow.get("id")
    require(
        isinstance(workflow_id, int) and workflow_id > 0
        and workflow.get("state") == "active",
        "trusted Qodana workflow unavailable",
    )

    selected = select_run(repo, sha, workflow_id)
    expired_ids: set[int] = set()
    verified_ids: set[int] = set()
    verified_proof: dict | None = None
    dispatched = False
    if selected is None:
        dispatch(repo, sha)
        dispatched = True
        print(f"Dispatched {WORKFLOW} for exact source {sha}", flush=True)
    else:
        print(f"Joining/reusing Qodana run {selected['id']} for {sha}", flush=True)

    deadline = time.monotonic() + timeout
    discovery_deadline = min(deadline, time.monotonic() + 150)
    while True:
        current = select_run(repo, sha, workflow_id, expired_ids | verified_ids)
        if current is not None:
            require(current.get("run_attempt") == 1, "Qodana rerun/attempt ambiguity")
            status = current.get("status")
            if status == "completed":
                require(
                    current.get("conclusion") == "success",
                    f"Qodana run {current.get('id')} ended: {current.get('conclusion')}",
                )
                result = verify_evidence(
                    current, sha, workflow_id, repo,
                    allow_missing=not dispatched,
                )
                if result is None:
                    expired_ids.add(current["id"])
                    print(
                        f"Original Qodana evidence expired/missing for run "
                        f"{current['id']}; searching older verified proof",
                        flush=True,
                    )
                    continue
                if not dispatched:
                    verified_ids.add(current["id"])
                    if verified_proof is None:
                        verified_proof = result
                    print(
                        f"Independently verified complete Qodana run {current['id']}",
                        flush=True,
                    )
                    continue
                verify_source(sha, tag, repo)
                result["execution"] = "dispatched"
                return result
            require(
                status in {"queued", "in_progress", "waiting", "pending", "requested"},
                f"unrecognized Qodana run status: {status}",
            )
        elif not dispatched:
            if verified_proof is not None:
                verify_source(sha, tag, repo)
                verified_proof["execution"] = "reused-or-joined"
                return verified_proof
            if not expired_ids:
                raise ValueError("previously visible Qodana run vanished")
            dispatch(repo, sha)
            dispatched = True
            discovery_deadline = min(deadline, time.monotonic() + 150)
            print(f"Regenerating exact-source Qodana proof for {sha}", flush=True)

        if time.monotonic() >= deadline:
            raise ValueError("Qodana preflight timed out before complete verified evidence")
        if current is None and time.monotonic() >= discovery_deadline:
            raise ValueError("dispatched Qodana run did not appear within 150 seconds")
        time.sleep(min(poll, max(0.1, deadline - time.monotonic())))


def self_test() -> None:
    sha = "a" * 40
    other = "b" * 40

    def reject(fn, fragment: str) -> None:
        try:
            fn()
        except (ValueError, json.JSONDecodeError) as error:
            assert fragment in str(error), (fragment, str(error))
        else:
            raise AssertionError("invalid Qodana evidence accepted: " + fragment)

    def source_git(*args: str) -> str:
        if args == ("rev-parse", "HEAD"):
            return sha
        if args == ("rev-parse", "refs/tags/v1.0.0^{commit}"):
            return sha
        if args[:3] == ("ls-remote", "--tags", "origin"):
            return sha + "\trefs/tags/v1.0.0"
        raise AssertionError(f"unexpected git call: {args!r}")

    with patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repo"}), \
         patch(__name__ + ".git", side_effect=source_git), \
         patch(__name__ + ".subprocess.run") as source_run:
        source_run.return_value.returncode = 0
        verify_source(sha, "v1.0.0", "owner/repo")
        source_run.return_value.returncode = 1
        reject(
            lambda: verify_source(sha, "v1.0.0", "owner/repo"),
            "not in current protected main",
        )

    with patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repo"}), \
         patch(__name__ + ".git", side_effect=lambda *args: (
             other + "\trefs/tags/v1.0.0"
             if args[:3] == ("ls-remote", "--tags", "origin")
             else sha
         )):
        reject(
            lambda: verify_source(sha, "v1.0.0", "owner/repo"),
            "remote release tag moved",
        )

    run = {
        "workflow_id": 2,
        "path": ".github/workflows/qodana-full.yml",
        "head_sha": other,
        "head_branch": "main",
        "head_repository": {"full_name": "owner/repo"},
        "event": "workflow_dispatch",
        "display_title": RUN_PREFIX + sha,
        "run_attempt": 1,
        "status": "completed",
        "conclusion": "success",
        "id": 17,
    }
    verify_run(run, sha, 2)
    for change, fragment in [
        ({"head_branch": "feature"}, "identity"),
        ({"event": "pull_request"}, "identity"),
        ({"display_title": RUN_PREFIX + other}, "identity"),
        ({"run_attempt": 2}, "attempt"),
        ({"conclusion": "failure"}, "successfully"),
        ({"workflow_id": 4}, "identity"),
    ]:
        reject(lambda change=change: verify_run(dict(run, **change), sha, 2), fragment)

    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        manifest = root / "qodana-source-manifest.json"
        document = {
            "schema": 1,
            "candidate_sha": sha,
            "checkout_sha": sha,
            "event_name": "workflow_dispatch",
            "event_sha": other,
            "repository": "owner/repo",
            "run_id": 17,
            "run_attempt": 1,
            "qodana_action": QODANA_ACTION,
            "profile": "qodana.recommended",
            "fail_threshold": 0,
        }
        manifest.write_text(json.dumps(document), encoding="utf-8")
        verify_manifest(root, sha, run, "owner/repo")
        for change in (
            {"candidate_sha": other},
            {"checkout_sha": other},
            {"event_sha": sha},
            {"repository": "other/repo"},
            {"run_id": 99},
            {"run_attempt": 2},
            {"qodana_action": "JetBrains/qodana-action@wrong"},
            {"profile": "custom"},
            {"fail_threshold": 1},
        ):
            manifest.write_text(
                json.dumps(dict(document, **change)),
                encoding="utf-8",
            )
            reject(
                lambda: verify_manifest(root, sha, run, "owner/repo"),
                "identity mismatch",
            )
        manifest.write_text("{broken", encoding="utf-8")
        reject(
            lambda: verify_manifest(root, sha, run, "owner/repo"),
            "Expecting property name",
        )

    steps = [
        {"name": name, "conclusion": "success"}
        for name in (
            "Verify exact protected-main candidate",
            "Verify exact checkout",
            "Qodana - Full Code Inspection",
            "Enforce full Qodana SARIF",
            "Retain verified original Qodana SARIF",
            "Record exact-source Qodana provenance",
            "Upload exact-source Qodana provenance",
        )
    ]
    jobs = {
        "total_count": 2,
        "jobs": [
            {"name": GUARD_JOB, "status": "completed", "conclusion": "success", "steps": []},
            {"name": SCAN_JOB, "status": "completed", "conclusion": "success", "steps": steps},
        ],
    }
    verify_jobs(jobs)
    bad_jobs = json.loads(json.dumps(jobs))
    bad_jobs["jobs"][1]["steps"][3]["conclusion"] = "failure"
    reject(lambda: verify_jobs(bad_jobs), "attestation")

    artifacts = {
        "total_count": 2,
        "artifacts": [
            {
                "name": f"qodana-{sha}-sarif",
                "expired": False,
                "size_in_bytes": 100,
                "workflow_run": {"id": 17},
            },
            {
                "name": f"qodana-source-{sha}",
                "expired": False,
                "size_in_bytes": 100,
                "workflow_run": {"id": 17},
            },
        ],
    }
    verify_artifacts(artifacts, sha, 17)
    expired = json.loads(json.dumps(artifacts))
    expired["artifacts"][0]["expired"] = True
    assert verify_artifacts(expired, sha, 17, allow_missing=True) is None
    absent = json.loads(json.dumps(artifacts))
    absent["artifacts"].pop()
    absent["total_count"] = 1
    assert verify_artifacts(absent, sha, 17, allow_missing=True) is None
    duplicate = json.loads(json.dumps(artifacts))
    duplicate["artifacts"].append(dict(duplicate["artifacts"][0]))
    duplicate["total_count"] = 3
    reject(lambda: verify_artifacts(duplicate, sha, 17), "duplicate")

    skipped = dict(run, event="schedule", head_sha=sha)
    skipped_jobs = {
        "total_count": 2,
        "jobs": [
            {"name": GUARD_JOB, "status": "completed", "conclusion": "success"},
            {"name": SCAN_JOB, "status": "completed", "conclusion": "skipped"},
        ],
    }
    with patch(__name__ + ".gh_json", return_value=skipped_jobs):
        assert intentional_schedule_skip("owner/repo", skipped)

    with patch(__name__ + ".find_runs", return_value=[
        run, dict(run, id=18, conclusion="failure")
    ]):
        reject(lambda: select_run("owner/repo", sha, 2), "ended")
    with patch(__name__ + ".find_runs", return_value=[
        run, dict(run, id=18, run_attempt=2)
    ]):
        reject(lambda: select_run("owner/repo", sha, 2), "rerun")
    active = dict(run, status="in_progress", conclusion=None)
    with patch(__name__ + ".find_runs", return_value=[
        active, dict(active, id=18)
    ]):
        reject(lambda: select_run("owner/repo", sha, 2), "multiple concurrent")

    def exercise(sequence, expected_dispatches: int, failure: str | None = None) -> None:
        snapshots = iter(sequence)
        last = sequence[-1]

        def choose(*args, **kwargs):
            try:
                entry = next(snapshots)
            except StopIteration:
                entry = last
            ignored = args[3] if len(args) > 3 else kwargs.get("ignored", set())
            if entry is not None and entry.get("id") in ignored:
                return None
            return entry

        with patch(__name__ + ".verify_source"), \
             patch(__name__ + ".gh_json", return_value={"id": 2, "state": "active"}), \
             patch(__name__ + ".select_run", side_effect=choose), \
             patch(__name__ + ".dispatch") as invoke, \
             patch(__name__ + ".verify_evidence", return_value={"run_id": 17}), \
             patch(__name__ + ".time.sleep"), \
             patch(__name__ + ".time.monotonic",
                   side_effect=(value for value in range(0, 10000))):
            if failure:
                reject(
                    lambda: run_preflight(sha, "v1.0.0", "owner/repo",
                                          timeout=50, poll=1),
                    failure,
                )
            else:
                result = run_preflight(
                    sha, "v1.0.0", "owner/repo", timeout=50, poll=1
                )
                assert result["run_id"] == 17
            assert invoke.call_count == expected_dispatches

    queued = dict(run, status="queued", conclusion=None)
    in_progress = dict(run, status="in_progress", conclusion=None)
    failed = dict(run, conclusion="failure")
    exercise([run, run], 0)
    exercise([None, None, queued, in_progress, run], 1)
    exercise([in_progress, in_progress, run], 0)
    exercise([failed, failed], 0, "ended")
    exercise([None] * 10, 1, "timed out")

    with patch(__name__ + ".verify_source"), \
         patch(__name__ + ".gh_json", return_value={"id": 2, "state": "active"}), \
         patch(__name__ + ".select_run", side_effect=[
             run, run, None, queued, in_progress, run
         ]), \
         patch(__name__ + ".dispatch") as regenerated, \
         patch(__name__ + ".verify_evidence", side_effect=[
             None, {"run_id": 17}
         ]) as checks, \
         patch(__name__ + ".time.sleep"), \
         patch(__name__ + ".time.monotonic",
               side_effect=(value for value in range(0, 10000))):
        result = run_preflight(
            sha, "v1.0.0", "owner/repo", timeout=50, poll=1
        )
        assert result["execution"] == "dispatched"
        assert checks.call_count == 2 and regenerated.call_count == 1

    print("Exact-source Qodana reuse/dispatch/join/wait negative controls passed")


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
        proof = run_preflight(
            args.sha, args.tag, os.environ.get("GITHUB_REPOSITORY", "")
        )
        print("Exact source Qodana preflight accepted: " + json.dumps(proof, sort_keys=True))
        if os.environ.get("GITHUB_STEP_SUMMARY"):
            with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as out:
                out.write("### Exact-source Qodana preflight\n\n```json\n")
                out.write(json.dumps(proof, indent=2, sort_keys=True))
                out.write("\n```\n")
    except (
        ValueError,
        OSError,
        subprocess.CalledProcessError,
        json.JSONDecodeError,
        TypeError,
        KeyError,
    ) as error:
        print(f"::error::Release Qodana preflight rejected: {error}", file=sys.stderr)
        raise SystemExit(1) from error


if __name__ == "__main__":
    main()
