#!/usr/bin/env python3
"""Fail-closed PR compatibility adapter for the trusted Gradle dependency publisher."""
from __future__ import annotations

import argparse
import json
import re
import sys
import os
import tomllib
from pathlib import Path

SHA = re.compile(r"[0-9a-f]{40}\Z")
JOB = re.compile(r"^  ([A-Za-z0-9_-]+):\s*$", re.MULTILINE)
PIN_ACTIONS = (
    "actions/checkout",
    "actions/setup-java",
    "gradle/actions/dependency-submission",
)
RELEVANT_PATHS = frozenset({
    ".github/workflows/dependency-submission.yml",
    ".github/workflows/build.yml",
    ".github/workflow-policy/trusted_dependency_submission_validation.py",
    "build.gradle.kts",
    "gradle/libs.versions.toml",
})
# This evidence floor is based on the maintained 761-node parity snapshot;
# it is a coverage invariant, not a target dependency version.
MINIMUM_RESOLVED_NODES = 650


def required_coordinates(catalog: Path) -> frozenset[str]:
    with catalog.open("rb") as source:
        data = tomllib.load(source)
    versions = data["versions"]
    library = data["libraries"]["mybatis"]
    plugin = data["plugins"]["intelliJPlatform"]
    require(library.get("group") == "org.mybatis" and library.get("name") == "mybatis",
            "canonical MyBatis dependency identity changed")
    require(plugin.get("id") == "org.jetbrains.intellij.platform",
            "canonical IntelliJ platform plugin identity changed")
    mybatis = versions[library["version"]["ref"]]
    intellij = versions[plugin["version"]["ref"]]
    for version in (mybatis, intellij):
        require(isinstance(version, str) and re.fullmatch(r"[0-9]+(?:\.[0-9]+)+", version) is not None,
                "catalog must contain exact dependency versions")
    return frozenset({
        f"org.mybatis:mybatis:{mybatis}",
        f"org.jetbrains.intellij.platform:intellij-platform-gradle-plugin:{intellij}",
    })


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def safe_path(value: object) -> str:
    require(isinstance(value, str) and value, "missing changed-file path")
    require(not value.startswith("/") and ".." not in value.split("/"), "unsafe changed-file path")
    return value


def require_supported_publisher_patch(entry: dict) -> None:
    # Classification selects the read-only adapter, not authorization to
    # publish. The separate candidate-source contract and strict review
    # validate production trust, paired pins and the exact shared verifier.
    require(entry.get("status") == "modified",
            "unsupported dependency-submission workflow change type")
    patch = entry.get("patch")
    require(isinstance(patch, str) and bool(patch.strip()),
            "dependency-submission change has no reviewable patch")


def is_graph_input(path: str) -> bool:
    return (
        path in RELEVANT_PATHS
        or path.endswith((".gradle", ".gradle.kts", ".versions.toml", "gradle.properties", "gradle.lockfile"))
        or path.startswith(("gradle/", "buildSrc/", "build-logic/"))
        or "/gradle/dependency-locks/" in path
        or path in {"gradlew", "gradlew.bat"}
    )


def classify(pull: dict, pages: list, expected_head: str) -> bool:
    require(SHA.fullmatch(expected_head or "") is not None, "invalid expected PR HEAD")
    require(isinstance(pull, dict) and isinstance(pull.get("head"), dict),
            "missing PR metadata")
    require(pull["head"].get("sha") == expected_head,
            "PR HEAD changed during classification")
    expected_count = pull.get("changed_files")
    require(type(expected_count) is int and 0 < expected_count <= 3000,
            "invalid or unsupported PR changed-file count")
    require(isinstance(pages, list) and pages
            and all(isinstance(page, list) for page in pages),
            "invalid paginated changed-file listing")
    entries = [entry for page in pages for entry in page]
    require(len(entries) == expected_count, "incomplete PR changed-file listing")

    seen: set[str] = set()
    paths: set[str] = set()
    for entry in entries:
        require(isinstance(entry, dict), "malformed changed-file entry")
        name = safe_path(entry.get("filename"))
        require(name not in seen, "duplicate changed-file entry")
        seen.add(name)
        status = entry.get("status")
        require(status in {
            "added", "modified", "removed", "renamed",
            "copied", "changed", "unchanged",
        }, "unknown changed-file status")
        paths.add(name)
        if status == "renamed":
            paths.add(safe_path(entry.get("previous_filename")))
        if name == ".github/workflows/dependency-submission.yml":
            require_supported_publisher_patch(entry)

    return any(is_graph_input(path) for path in paths)


def extract_job(workflow: str, job_id: str) -> str:
    marker = re.search(rf"^  {re.escape(job_id)}:\s*$", workflow, re.MULTILINE)
    require(marker is not None, f"missing PR validation job {job_id}")
    tail = workflow[marker.end():]
    end = JOB.search(tail)
    return tail[:end.start()] if end else tail


def action_pin(text: str, action: str) -> str:
    pins = re.findall(
        rf"uses:\s*{re.escape(action)}@([0-9a-f]{{40}})(?:\s+#.*)?$",
        text,
        re.MULTILINE,
    )
    require(len(pins) == 1, f"expected exactly one {action} pin")
    return pins[0]


def check_contract(production: str, pr_workflow: str) -> None:
    top = production.split("\njobs:\n", 1)[0]
    events = re.search(r"(?m)^on:\n((?:[ \t].*\n|\n)*)", top)
    require(events is not None and re.findall(r"(?m)^  ([a-z_]+):.*$", events.group(1)) == ["push", "workflow_dispatch"]
            and "    branches: [main]" in events.group(1),
            "production dependency publisher must remain main push/manual-only")
    require(re.search(r"(?m)^permissions: \{\}$", top) is not None,
            "production workflow must default to no permissions")
    producer = extract_job(production, "submitGraph")
    header = producer.split("\n    steps:", 1)[0]
    require("    permissions:\n      contents: write\n" in producer
            and re.findall(r"^\s+([a-z-]+):\s*write(?:\s+#.*)?$", header, re.MULTILINE) == ["contents"],
            "production publisher must retain only contents:write authority")
    guard = "${{ github.repository == 'luceat-lux-vestra/zMyBatis' && github.ref == 'refs/heads/main' && vars.ZMYBATIS_ADS_CUTOVER_DONE == 'true' }}"
    require(re.findall(r"(?m)^    if: (.+)$", header) == [guard],
            "production publisher lost exact main cutover guard")
    require(re.findall(r"^  ([A-Za-z0-9_-]+):\s*$", production.split("\njobs:\n", 1)[1], re.MULTILINE) == ["submitGraph"],
            "production publisher has an unexpected job")
    require(re.findall(r"^      - name: (.+)$", producer, re.MULTILINE) == [
        "Checkout exact protected main", "Setup supported Java",
        "Generate and submit exact main dependency graph (no cache writes)",
        "Verify submitted graph coverage and provenance",
    ], "production publisher changed its reviewed step topology")
    require(len(re.findall(r"(?m)^      - ", producer)) == 4
            and len(re.findall(r"(?m)^        uses:", producer)) == 3
            and len(re.findall(r"(?m)^        run:", producer)) == 1,
            "production publisher has extra executable steps")
    checkout = producer.split("      - name: Setup supported Java", 1)[0]
    require("ref: ${{ github.sha }}" in checkout and "repository:" not in checkout,
            "production publisher lost exact checkout authority")
    require("persist-credentials: false" in producer
            and "trusted_dependency_submission_validation.py verify-main" in producer
            and '--head "$GITHUB_SHA"' in producer
            and "--catalog gradle/libs.versions.toml" in producer,
            "production publisher lost exact source/shared verifier authority")
    require("dependency-graph: generate-and-submit" in production,
            "production publisher is not in generate-and-submit mode")
    require("cache-read-only: true" in production,
            "production publisher may write Gradle cache")
    require("dependency-graph-continue-on-failure: false" in production,
            "production publisher no longer fails closed")

    job = extract_job(pr_workflow, "dependencySubmissionValidation")
    require("    permissions:\n      contents: read\n      pull-requests: read\n" in job,
            "PR dependency adapter permissions must be explicitly read-only")
    require(re.search(r"^\s+\S+:\s*write(?:\s+#.*)?$", job, re.MULTILINE) is None,
            "PR dependency adapter gained write authority")
    require("dependency-graph: generate-and-upload" in job,
            "PR dependency adapter must use generate-and-upload")
    require("dependency-graph: generate-and-submit" not in job,
            "PR dependency adapter must never submit")
    require("cache-read-only: true" in job,
            "PR dependency adapter may write Gradle cache")
    require("dependency-graph-continue-on-failure: false" in job,
            "PR dependency adapter no longer fails closed")
    require("dependency-graph-report-dir: dependency-graph-reports-pr" in job,
            "PR dependency adapter report directory drifted")
    require(
        "repository: ${{ github.event_name == 'pull_request' && github.event.pull_request.head.repo.full_name || github.repository }}" in job
        and "ref: ${{ github.event_name == 'pull_request' && github.event.pull_request.head.sha || github.sha }}" in job,
        "PR dependency adapter must checkout the exact PR-head repository/SHA",
    )
    require("persist-credentials: false" in job,
            "PR dependency adapter checkout credentials must not persist")
    require("trusted_dependency_submission_validation.py verify" in job
            and "--catalog gradle/libs.versions.toml" in job,
            "PR adapter lost shared catalog verifier")

    for action in PIN_ACTIONS:
        require(action_pin(production, action) == action_pin(job, action),
                f"PR adapter {action} pin does not match production publisher")


def production_eligible(graph: dict, source_sha: str) -> bool:
    return graph.get("sha") == source_sha and graph.get("ref") == "refs/heads/main"


def verify_graph(directory: Path, expected_head: str, event_ref: str,
                 catalog: Path, *, production: bool = False) -> dict:
    require(SHA.fullmatch(expected_head or "") is not None,
            "invalid expected PR HEAD")
    if production:
        require(event_ref == "refs/heads/main", "production graph must be exact main")
    else:
        require(isinstance(event_ref, str) and event_ref.startswith("refs/pull/"),
                "dependency fixture did not run in pull_request ref context")

    files = sorted(directory.rglob("*.json"))
    require(len(files) == 1,
            f"expected exactly one dependency graph report, got {len(files)}")
    data = json.loads(files[0].read_text(encoding="utf-8"))
    require(isinstance(data, dict), "dependency graph report is not an object")
    require(data.get("sha") == expected_head,
            "dependency graph source SHA is not exact PR HEAD")
    require(data.get("ref") == event_ref,
            "dependency graph ref is not the PR event ref")
    require(production_eligible(data, expected_head) == production,
            "dependency graph authority does not match the producer context")

    manifests = data.get("manifests")
    require(isinstance(manifests, dict) and manifests,
            "dependency graph has no manifests")
    resolved: dict[str, dict] = {}
    for manifest in manifests.values():
        require(isinstance(manifest, dict),
                "malformed dependency graph manifest")
        nodes = manifest.get("resolved")
        require(isinstance(nodes, dict),
                "dependency graph manifest lacks resolved dependencies")
        resolved.update(nodes)

    require(len(resolved) >= MINIMUM_RESOLVED_NODES,
            f"dependency graph lost required coverage: {len(resolved)} < {MINIMUM_RESOLVED_NODES}")
    missing = required_coordinates(catalog) - resolved.keys()
    require(not missing,
            f"dependency graph lost required coordinates: {sorted(missing)}")
    direct = sum(
        isinstance(node, dict) and node.get("relationship") == "direct"
        for node in resolved.values()
    )
    require(direct > 0, "dependency graph has no direct dependencies")
    return {
        "source_sha": expected_head,
        "event_ref": event_ref,
        "manifests": len(manifests),
        "resolved": len(resolved),
        "direct": direct,
        "production_evidence": production,
    }


def self_test() -> None:
    import tempfile

    sha = "a" * 40
    pull = {"head": {"sha": sha}, "changed_files": 1}
    assert classify(pull, [[{
        "filename": ".github/workflow-policy/trusted_dependency_submission_validation.py",
        "status": "modified",
    }]], sha)
    assert classify(pull, [[{
        "filename": "build.gradle.kts",
        "status": "modified",
    }]], sha)
    assert classify(pull, [[{
        "filename": "gradle/libs.versions.toml",
        "status": "modified",
    }]], sha)
    assert not classify(
        pull,
        [[{"filename": "README.md", "status": "modified"}]],
        sha,
    )

    allowed_patch = (
        "@@ -1 +1 @@\n"
        "-        uses: gradle/actions/dependency-submission@" + "b" * 40 + " # v6.3.0\n"
        "+        uses: gradle/actions/dependency-submission@" + "c" * 40 + " # v6.4.0"
    )
    assert classify(pull, [[{
        "filename": ".github/workflows/dependency-submission.yml",
        "status": "modified",
        "patch": allowed_patch,
    }]], sha)

    assert classify(pull, [[{
        "filename": ".github/workflows/dependency-submission.yml",
        "status": "modified",
        "patch": "@@ -1 +1 @@\n-runs-on: ubuntu-24.04-arm\n+runs-on: ubuntu-26.04-arm",
    }]], sha)
    assert classify(pull, [[{
        "filename": "mybatis-engine/build.gradle.kts",
        "status": "modified",
    }]], sha)

    bad_cases = [
        (
            {"head": {"sha": sha}, "changed_files": 2},
            [[{"filename": "README.md", "status": "modified"}]],
            sha,
        ),
        (pull, [[{"filename": "../bad", "status": "modified"}]], sha),
        (pull, [[{"filename": "new", "status": "renamed"}]], sha),
        (
            pull,
            [[{
                "filename": ".github/workflows/dependency-submission.yml",
                "status": "modified",
                "patch": "",
            }]],
            sha,
        ),
        (
            pull,
            [[{
                "filename": ".github/workflows/dependency-submission.yml",
                "status": "removed",
                "patch": allowed_patch,
            }]],
            sha,
        ),
        (
            pull,
            [[{"filename": "README.md", "status": "modified"}]],
            "d" * 40,
        ),
    ]
    for bad_pull, bad_pages, bad_sha in bad_cases:
        try:
            classify(bad_pull, bad_pages, bad_sha)
        except ValueError:
            pass
        else:
            raise AssertionError("unsafe changed-file classification was accepted")

    prod = (
        "on:\n  push:\n    branches: [main]\n  workflow_dispatch:\npermissions: {}\njobs:\n  submitGraph:\n"
        "    if: ${{ github.repository == 'luceat-lux-vestra/zMyBatis' && github.ref == 'refs/heads/main' && vars.ZMYBATIS_ADS_CUTOVER_DONE == 'true' }}\n"
        "    permissions:\n      contents: write\n    steps:\n"
        "      - name: Checkout exact protected main\n"
        "        uses: actions/checkout@" + "1" * 40 + "\n"
        "        with:\n          persist-credentials: false\n          ref: ${{ github.sha }}\n"
        "      - name: Setup supported Java\n"
        "        uses: actions/setup-java@" + "2" * 40 + "\n"
        "      - name: Generate and submit exact main dependency graph (no cache writes)\n"
        "        uses: gradle/actions/dependency-submission@" + "3" * 40 + "\n"
        "        with:\n          cache-read-only: true\n"
        "          dependency-graph: generate-and-submit\n"
        "          dependency-graph-continue-on-failure: false\n"
        "      - name: Verify submitted graph coverage and provenance\n"
        "        run: python3 .github/workflow-policy/trusted_dependency_submission_validation.py verify-main --head \"$GITHUB_SHA\" --catalog gradle/libs.versions.toml\n"
    )
    pr = (
        "jobs:\n  dependencySubmissionValidation:\n"
        "    permissions:\n      contents: read\n      pull-requests: read\n"
        "    steps:\n"
        "      - uses: actions/checkout@" + "1" * 40 + "\n"
        "        with:\n"
        "          repository: ${{ github.event_name == 'pull_request' && github.event.pull_request.head.repo.full_name || github.repository }}\n"
        "          ref: ${{ github.event_name == 'pull_request' && github.event.pull_request.head.sha || github.sha }}\n"
        "          persist-credentials: false\n"
        "      - uses: actions/setup-java@" + "2" * 40 + "\n"
        "      - uses: gradle/actions/dependency-submission@" + "3" * 40 + "\n"
        "        with:\n          cache-read-only: true\n"
        "          dependency-graph: generate-and-upload\n"
        "          dependency-graph-report-dir: dependency-graph-reports-pr\n"
        "          dependency-graph-continue-on-failure: false\n"
        "      - run: python3 .github/workflow-policy/trusted_dependency_submission_validation.py verify --catalog gradle/libs.versions.toml\n"
    )
    check_contract(prod, pr)
    for mutated_prod in (
        prod.replace("  push:", "  pull_request:"),
        prod.replace("contents: write", "contents: read"),
        prod.replace("refs/heads/main", "refs/heads/feature"),
        prod.replace(" == 'true' }}", " == 'true' || true }}"),
        prod.replace("ref: ${{ github.sha }}", "ref: main"),
        prod.replace("verify-main", "self-test"),
        prod.replace("      - name: Verify submitted", "      - name: Extra\n        run: echo unexpected\n      - name: Verify submitted"),
    ):
        try:
            check_contract(mutated_prod, pr)
        except ValueError:
            pass
        else:
            raise AssertionError("unsafe production graph contract was accepted")
    for mutated in (
        pr.replace(
            "dependency-graph: generate-and-upload",
            "dependency-graph: generate-and-submit",
        ),
        pr.replace("contents: read", "contents: write", 1),
        pr.replace("cache-read-only: true", "cache-read-only: false"),
        pr.replace(
            "gradle/actions/dependency-submission@" + "3" * 40,
            "gradle/actions/dependency-submission@" + "4" * 40,
        ),
    ):
        try:
            check_contract(prod, mutated)
        except ValueError:
            pass
        else:
            raise AssertionError(
                "unsafe PR dependency adapter contract was accepted"
            )

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        catalog = root / "libs.versions.toml"
        catalog.write_text("""[versions]\nmybatis = "8.9.1"\nintelliJPlatform = "7.6.5"\n[libraries]\nmybatis = { group = "org.mybatis", name = "mybatis", version.ref = "mybatis" }\n[plugins]\nintelliJPlatform = { id = "org.jetbrains.intellij.platform", version.ref = "intelliJPlatform" }\n""", encoding="utf-8")
        resolved = {
            f"example:dep:{i}": {"relationship": "indirect"}
            for i in range(648)
        }
        resolved["org.mybatis:mybatis:8.9.1"] = {
            "relationship": "direct"
        }
        resolved[
            "org.jetbrains.intellij.platform:intellij-platform-gradle-plugin:7.6.5"
        ] = {"relationship": "direct"}
        graph = {
            "sha": sha,
            "ref": "refs/pull/7/merge",
            "manifests": {"root": {"resolved": resolved}},
        }
        (root / "graph.json").write_text(
            json.dumps(graph),
            encoding="utf-8",
        )
        result = verify_graph(root, sha, "refs/pull/7/merge", catalog)
        assert result["resolved"] == 650
        assert result["production_evidence"] is False
        # A catalog update must require new graph coordinates without changing this verifier.
        catalog.write_text(catalog.read_text(encoding="utf-8").replace("8.9.1", "8.9.2"), encoding="utf-8")
        try:
            verify_graph(root, sha, "refs/pull/7/merge", catalog)
        except ValueError:
            pass
        else:
            raise AssertionError("stale graph version was accepted after catalog change")
        catalog.write_text(catalog.read_text(encoding="utf-8").replace("8.9.2", "8.9.1"), encoding="utf-8")
        graph["ref"] = "refs/heads/main"
        (root / "graph.json").write_text(json.dumps(graph), encoding="utf-8")
        assert verify_graph(root, sha, "refs/heads/main", catalog, production=True)["production_evidence"]
        graph["ref"] = "refs/pull/7/merge"
        (root / "graph.json").write_text(json.dumps(graph), encoding="utf-8")

        try:
            verify_graph(root, sha, "refs/heads/main", catalog)
        except ValueError:
            pass
        else:
            raise AssertionError(
                "protected-main provenance was accepted for PR fixture"
            )

        graph["sha"] = "b" * 40
        (root / "graph.json").write_text(
            json.dumps(graph),
            encoding="utf-8",
        )
        try:
            verify_graph(root, sha, "refs/pull/7/merge", catalog)
        except ValueError:
            pass
        else:
            raise AssertionError("wrong source SHA was accepted")

    print("trusted dependency-submission PR adapter self-tests passed")


def main() -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)

    classify_p = sub.add_parser("classify")
    classify_p.add_argument("--pull", type=Path, required=True)
    classify_p.add_argument("--pages", type=Path, required=True)
    classify_p.add_argument("--head", required=True)

    contract_p = sub.add_parser("check-contract")
    contract_p.add_argument("--production", type=Path, required=True)
    contract_p.add_argument("--pr-workflow", type=Path, required=True)

    verify_p = sub.add_parser("verify")
    verify_p.add_argument("--dir", type=Path, required=True)
    verify_p.add_argument("--head", required=True)
    verify_p.add_argument("--event-ref", required=True)
    verify_p.add_argument("--catalog", type=Path, required=True)

    main_p = sub.add_parser("verify-main")
    main_p.add_argument("--dir", type=Path, required=True)
    main_p.add_argument("--head", required=True)
    main_p.add_argument("--catalog", type=Path, required=True)

    sub.add_parser("self-test")
    args = parser.parse_args()

    try:
        if args.command == "classify":
            required = classify(
                json.loads(args.pull.read_text(encoding="utf-8")),
                json.loads(args.pages.read_text(encoding="utf-8")),
                args.head,
            )
            print("required=" + str(required).lower())
        elif args.command == "check-contract":
            check_contract(
                args.production.read_text(encoding="utf-8"),
                args.pr_workflow.read_text(encoding="utf-8"),
            )
        elif args.command == "verify":
            print(json.dumps(
                verify_graph(args.dir, args.head, args.event_ref, args.catalog),
                sort_keys=True,
            ))
        elif args.command == "verify-main":
            require(os.environ.get("GITHUB_REF") == "refs/heads/main"
                    and os.environ.get("GITHUB_SHA") == args.head
                    and os.environ.get("GITHUB_EVENT_NAME") in {"push", "workflow_dispatch"},
                    "production verification requires protected-main event authority")
            result = verify_graph(args.dir, args.head, "refs/heads/main", args.catalog, production=True)
            print(f"GRAPH_SUBMITTED_MAIN_SHA={args.head}")
            print(f"GRAPH_SUBMITTED_MANIFESTS={result['manifests']}")
            print(f"GRAPH_SUBMITTED_NODES={result['resolved']}")
            print(f"GRAPH_SUBMITTED_DIRECT={result['direct']}")
            with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a", encoding="utf-8") as summary:
                summary.write(
                    f"Main Gradle dependency submission: {result['manifests']} manifest(s), "
                    f"{result['resolved']} dependencies, {result['direct']} direct. "
                    "Gradle Actions cache read-only; no cache entries produced.\n"
                )
        else:
            self_test()
    except (
        ValueError,
        OSError,
        KeyError,
        TypeError,
        json.JSONDecodeError,
    ) as exc:
        print(
            f"::error::Trusted dependency-submission PR validation rejected: {exc}",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
