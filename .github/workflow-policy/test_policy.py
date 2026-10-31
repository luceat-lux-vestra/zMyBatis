#!/usr/bin/env python3
"""Deterministic controls for the workflow static-analysis gate.

The local policy checkers must reject checked-in bad fixtures and accept every
real workflow. actionlint and zizmor provide complementary schema/security
coverage; repository-specific fixtures may intentionally be local-checker-only
when those tools do not model the invariant.
"""
from __future__ import annotations

import re
import shutil
import subprocess
import tempfile
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
POLICY_DIR = Path(__file__).resolve().parent
WORKFLOWS_DIR = REPO_ROOT / ".github" / "workflows"

BAD_FIXTURES = sorted((POLICY_DIR / "fixtures" / "bad").glob("*.yml"))
GOOD_FIXTURES = sorted((POLICY_DIR / "fixtures" / "good").glob("*.yml"))
REQUIRED_CONTEXTS_BAD_FIXTURE = (
    POLICY_DIR / "fixtures" / "bad" / "required_contexts_missing_pull_request"
)
REAL_WORKFLOWS = sorted(
    [*WORKFLOWS_DIR.glob("*.yml"), *WORKFLOWS_DIR.glob("*.yaml")]
)

# Repository-specific controls that zizmor does not necessarily reject. The
# local checker is the authoritative oracle for these fixtures.
ZIZMOR_LOCAL_ONLY_FIXTURES = {
    "compound_pr_condition.yml",
    "missing_permissions.yml",
    "pwn_default_checkout.yml",
    "pwn_other_write_scope.yml",
}


def resolve_actionlint() -> str | None:
    on_path = shutil.which("actionlint")
    if on_path:
        return on_path

    local_binary = REPO_ROOT / "actionlint"
    if local_binary.is_file():
        return str(local_binary)
    return None


def run(cmd: list[str]) -> int:
    result = subprocess.run(
        cmd,
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        sys.stderr.write(result.stdout)
        sys.stderr.write(result.stderr)
    return result.returncode


def expect(label: str, condition: bool, failures: list[str]) -> None:
    print(f"{'ok  ' if condition else 'FAIL'} - {label}")
    if not condition:
        failures.append(label)


def check_local_policy(failures: list[str]) -> None:
    for fixture in BAD_FIXTURES:
        pins_rc = run(
            ["python3", str(POLICY_DIR / "check_pins.py"), str(fixture)]
        )
        boundary_rc = run(
            [
                "python3",
                str(POLICY_DIR / "check_trust_boundary.py"),
                str(fixture),
            ]
        )
        expect(
            f"local checkers reject {fixture.relative_to(REPO_ROOT)}",
            pins_rc != 0 or boundary_rc != 0,
            failures,
        )

    for fixture in GOOD_FIXTURES:
        pins_rc = run(
            ["python3", str(POLICY_DIR / "check_pins.py"), str(fixture)]
        )
        boundary_rc = run(
            [
                "python3",
                str(POLICY_DIR / "check_trust_boundary.py"),
                str(fixture),
            ]
        )
        expect(
            f"check_pins.py accepts {fixture.relative_to(REPO_ROOT)}",
            pins_rc == 0,
            failures,
        )
        expect(
            f"check_trust_boundary.py accepts {fixture.relative_to(REPO_ROOT)}",
            boundary_rc == 0,
            failures,
        )

    # YAML permits trailing comments on scalar permission values. A valid
    # least-privilege declaration must not become UNKNOWN merely because it
    # documents why a write grant exists.
    with tempfile.TemporaryDirectory() as tmp:
        fixture = Path(tmp) / "commented-permissions.yml"
        fixture.write_text(
            """name: Commented permissions
on:
  issues:
    types: [opened]
permissions:
  contents: read # workflow default
jobs:
  reconcile:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      issues: write # narrowly scoped metadata mutation
    steps:
      - run: echo metadata-only
""",
            encoding="utf-8",
        )
        commented_rc = run([
            "python3",
            str(POLICY_DIR / "check_trust_boundary.py"),
            str(fixture),
        ])
        expect(
            "check_trust_boundary.py accepts valid trailing permission comments",
            commented_rc == 0,
            failures,
        )

    lifecycle_fixture = """# lifecycle-owner: issue #419
# lifecycle-retire: delete after bounded workflow research is recorded
name: Research lifecycle fixture
on:
  workflow_dispatch:
permissions:
  contents: read
jobs:
  probe:
    runs-on: ubuntu-latest
    steps:
      - run: echo safe
"""
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        checker = str(POLICY_DIR / "check_trust_boundary.py")

        valid = root / "research-lifecycle-fixture.yml"
        valid.write_text(lifecycle_fixture, encoding="utf-8")
        expect(
            "ephemeral workflow with lifecycle metadata is accepted",
            run(["python3", checker, str(valid)]) == 0,
            failures,
        )

        missing_owner = root / "temporary-lifecycle-fixture.yml"
        missing_owner.write_text(
            lifecycle_fixture.replace("# lifecycle-owner: issue #419\n", "# lifecycle-owner: \n"),
            encoding="utf-8",
        )
        expect(
            "ephemeral workflow missing lifecycle owner is rejected",
            run(["python3", checker, str(missing_owner)]) != 0,
            failures,
        )

        missing_retire = root / "experiment-lifecycle-fixture.yaml"
        missing_retire.write_text(
            lifecycle_fixture.replace(
                "# lifecycle-retire: delete after bounded workflow research is recorded\n",
                "# lifecycle-retire: \n",
            ),
            encoding="utf-8",
        )
        expect(
            "ephemeral workflow missing lifecycle retirement condition is rejected",
            run(["python3", checker, str(missing_retire)]) != 0,
            failures,
        )

        maintained = root / "ci-fixture.yml"
        maintained.write_text(
            "\n".join(lifecycle_fixture.splitlines()[2:]) + "\n",
            encoding="utf-8",
        )
        expect(
            "maintained workflow filename needs no ephemeral lifecycle metadata",
            run(["python3", checker, str(maintained)]) == 0,
            failures,
        )

    # Immutable pinning applies to remote Actions/workflows. Repository-local
    # uses references execute from the same checked-out revision and do not
    # have an independent ref to pin.
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        checker = str(POLICY_DIR / "check_pins.py")

        local_uses = root / "local-uses.yml"
        local_uses.write_text(
            """name: Local uses fixture
on:
  workflow_dispatch:
jobs:
  reusable:
    uses: ./.github/workflows/e2e.yml
  action:
    runs-on: ubuntu-latest
    steps:
      - uses: ./.github/actions/example
""",
            encoding="utf-8",
        )
        expect(
            "check_pins.py accepts repository-local action and reusable-workflow references",
            run(["python3", checker, str(local_uses)]) == 0,
            failures,
        )

        remote_floating = root / "remote-floating.yml"
        remote_floating.write_text(
            """name: Remote floating fixture
on:
  workflow_dispatch:
jobs:
  probe:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
""",
            encoding="utf-8",
        )
        expect(
            "check_pins.py rejects a floating remote Action ref",
            run(["python3", checker, str(remote_floating)]) != 0,
            failures,
        )

        remote_pinned = root / "remote-pinned.yml"
        remote_pinned.write_text(
            """name: Remote pinned fixture
on:
  workflow_dispatch:
jobs:
  probe:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@08c6903cd8c0fde910a37f88322edcfb5dd907a8
""",
            encoding="utf-8",
        )
        expect(
            "check_pins.py accepts an immutable remote Action ref",
            run(["python3", checker, str(remote_pinned)]) == 0,
            failures,
        )

    pins_rc = run(
        [
            "python3",
            str(POLICY_DIR / "check_pins.py"),
            str(WORKFLOWS_DIR),
        ]
    )
    expect("check_pins.py accepts every real workflow", pins_rc == 0, failures)

    boundary_rc = run(
        [
            "python3",
            str(POLICY_DIR / "check_trust_boundary.py"),
            *[str(path) for path in REAL_WORKFLOWS],
        ]
    )
    expect(
        "check_trust_boundary.py accepts every real workflow",
        boundary_rc == 0,
        failures,
    )

    # CodeQL is never allowed on PR/push. An injected pull_request event
    # must not silently re-enable privileged checks of untrusted source.
    codeql_source = (WORKFLOWS_DIR / "codeql.yml").read_text(encoding="utf-8")
    expect("CodeQL has no PR or push trigger",
           "  pull_request:" not in codeql_source and "  push:" not in codeql_source,
           failures)
    expect("CodeQL preserves manual exact SHA, weekly cadence and manual JVM analysis",
           all(fragment in codeql_source for fragment in (
               "workflow_dispatch:", "candidate_sha:", 'cron: "47 3 * * 3"',
               "language: actions", "language: java-kotlin",
               "build-mode: manual", "java-version: 25",
               "check_codeql_sarif.py", "retention-days: 90",
               "name: CodeQL schedule guard",
               'if [ "$GITHUB_EVENT_NAME" != "schedule" ]; then',
               '.head_sha == $sha',
               "needs: scan-guard",
               "if: needs.scan-guard.outputs.should_scan == 'true'",
           )), failures)
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "renamed-codeql.yml"
        path.write_text(
            codeql_source.replace("  schedule:", "  pull_request:\n  schedule:", 1),
            encoding="utf-8",
        )
        expect("PR-injected CodeQL is rejected by trust-boundary checker",
               run(["python3", str(POLICY_DIR / "check_trust_boundary.py"),
                    str(path)]) != 0, failures)

    qodana_full_source = (WORKFLOWS_DIR / "qodana-full.yml").read_text(encoding="utf-8")
    expect("trusted full Qodana has no PR or push trigger",
           "  pull_request:" not in qodana_full_source
           and "  push:" not in qodana_full_source,
           failures)
    expect("trusted full Qodana preserves reusable weekly/manual exact-source contract",
           all(fragment in qodana_full_source for fragment in (
               "run-name: Qodana source ${{ inputs.candidate_sha || github.sha }}",
               "workflow_dispatch:", "candidate_sha:", 'cron: "23 4 * * 4"',
               "name: Qodana schedule guard",
               'if [ "$GITHUB_EVENT_NAME" != "schedule" ]; then',
               'actions/workflows/qodana-full.yml/runs?event=schedule&status=success&branch=main',
               'expected_sarif="qodana-$GITHUB_SHA-sarif"',
               'expected_source="qodana-source-$GITHUB_SHA"',
               "needs: scan-guard",
               "if: needs.scan-guard.outputs.should_scan == 'true'",
               'GITHUB_REF" != "refs/heads/main"',
               '^[0-9a-f]{40}$',
               'git merge-base --is-ancestor "$EXPECTED_SHA" origin/main',
               "path: .qodana-source",
               "ref: ${{ env.EXPECTED_SHA }}",
               'git -C .qodana-source rev-parse HEAD',
               "Qodana - Full Code Inspection",
               "JetBrains/qodana-action@3e8d76ff0e0241618abd808682f426afe84bab3f",
               "--project-dir .qodana-source --repository-root .qodana-source --fail-threshold 0",
               "cache-default-branch-only: true",
               "pr-mode: false",
               "artifact-name: qodana-report",
               "check_qodana_sarif.py",
               "Retain verified original Qodana SARIF",
               "name: qodana-${{ env.EXPECTED_SHA }}-sarif",
               "name: qodana-source-${{ env.EXPECTED_SHA }}",
               "qodana-source-manifest.json",
               "retention-days: 90",
           )), failures)
    expect("trusted full Qodana source identity is distinct from workflow event identity",
           '"candidate_sha": source' in qodana_full_source
           and '"checkout_sha": checkout' in qodana_full_source
           and '"event_sha": os.environ["GITHUB_SHA"]' in qodana_full_source,
           failures)

    build_source = (WORKFLOWS_DIR / "build.yml").read_text(encoding="utf-8")
    e2e_source = (WORKFLOWS_DIR / "e2e.yml").read_text(encoding="utf-8")
    expect("E2E PR execution is reusable and has no independent PR trigger",
           "  workflow_call:" in e2e_source and "  pull_request:" not in e2e_source,
           failures)
    expect("called E2E keeps a concurrency identity distinct from the Build caller",
           "group: zmybatis-e2e-" in e2e_source
           and "group: ${{ github.workflow }}-" not in e2e_source,
           failures)
    expect("Build is the canonical PR DAG for Starter / Driver E2E",
           all(fragment in build_source for fragment in (
               "\n  prScope:",
               "\n  starterDriver:",
               "uses: ./.github/workflows/e2e.yml",
               "needs: [ prScope, build, test, qodanaScope, inspectCode, dependencySubmissionValidation, verify, lint, dependencyReview, starterDriver ]",
               "E2E_RESULT: ${{ needs.starterDriver.result }}",
               "E2E_REQUIRED:",
           )), failures)
    classifier_source = (POLICY_DIR / "classify_qodana_scope.py").read_text(encoding="utf-8")
    expect("Qodana PR scope classifier self-test remains in lint",
           "Verify Qodana PR scope classifier policy" in build_source
           and "classify_qodana_scope.py --self-test" in build_source,
           failures)
    qodana_scope_section = build_source.split("\n  qodanaScope:", 1)[1].split("\n  inspectCode:", 1)[0]
    inspect_section = build_source.split("\n  inspectCode:", 1)[1].split("\n  verify:", 1)[0]
    expect("authoritative Qodana applicability uses trusted PR base policy",
           all(fragment in qodana_scope_section for fragment in (
               "ref: ${{ github.event.pull_request.base.sha }}",
               "path: .trusted-qodana-scope",
               'classifier=".trusted-qodana-scope/.github/workflow-policy/classify_qodana_scope.py"',
               'test "$(git -C .trusted-qodana-scope rev-parse HEAD)" = "$PR_BASE_SHA"',
               "--jq '.changed_files'",
               "gh api --paginate --slurp",
               'echo "qodana_required=true"',
           ))
           and "github.event.pull_request.head.sha" not in qodana_scope_section,
           failures)
    expect("Inspect code consumes the trusted scope result fail-closed",
           all(fragment in inspect_section for fragment in (
               "needs: [ qodanaScope ]",
               "Require valid Qodana scope decision",
               "needs.qodanaScope.outputs.qodana_required == 'false'",
               'test "$QODANA_REASON" = "all-paths-proven-non-qodana"',
               "needs.qodanaScope.outputs.qodana_required == 'true'",
           )), failures)
    expect("Stage-C shadow authority is retired",
           "qodana_scope_shadow" not in build_source
           and "Shadow only: this decision does not yet authorize skipping Inspect code." not in build_source,
           failures)
    expect("Qodana classifier remains fail-closed on control files and incomplete metadata",
           all(fragment in classifier_source for fragment in (
               '"qodana.yml"',
               '".github/workflows/build.yml"',
               '".github/workflows/qodana-full.yml"',
               '".github/workflows/release.yml"',
               '".github/workflow-policy/classify_qodana_scope.py"',
               '"metadata-count-mismatch"',
               '"rename-identity-incomplete"',
               '"status-unknown"',
               'qodana-relevant:',
           )), failures)

    required_rc = run(
        [
            "python3",
            str(POLICY_DIR / "check_required_contexts.py"),
            str(REPO_ROOT / ".github" / "merge-gate-policy.yml"),
            str(REPO_ROOT),
        ]
    )
    expect(
        "check_required_contexts.py accepts merge-gate-policy.yml as-is",
        required_rc == 0,
        failures,
    )

    fixture_policy = REQUIRED_CONTEXTS_BAD_FIXTURE / "merge-gate-policy.yml"
    required_bad_rc = run(
        [
            "python3",
            str(POLICY_DIR / "check_required_contexts.py"),
            str(fixture_policy),
            str(REQUIRED_CONTEXTS_BAD_FIXTURE),
        ]
    )
    expect(
        "check_required_contexts.py rejects a producing workflow with no pull_request trigger",
        required_bad_rc != 0,
        failures,
    )

    with tempfile.TemporaryDirectory() as tmp:
        staged_root = Path(tmp) / "repo"
        shutil.copytree(REPO_ROOT, staged_root, ignore=shutil.ignore_patterns(".git", ".gradle", "build"))
        staged_policy = staged_root / ".github" / "merge-gate-policy.yml"
        text = staged_policy.read_text(encoding="utf-8")
        text = text.replace(
            "producedBy: .github/workflows/build.yml\n    job: mergeGate",
            "producedBy: .github/workflows/build.yml\n    job: missing-staged-job",
            1,
        )
        staged_policy.write_text(text, encoding="utf-8")
        staged_bad_rc = run([
            "python3",
            str(POLICY_DIR / "check_required_contexts.py"),
            str(staged_policy),
            str(staged_root),
        ])
        expect(
            "check_required_contexts.py rejects a missing required producer",
            staged_bad_rc != 0,
            failures,
        )

    with tempfile.TemporaryDirectory() as tmp:
        aggregate_root = Path(tmp) / "repo"
        shutil.copytree(REPO_ROOT, aggregate_root, ignore=shutil.ignore_patterns(".git", ".gradle", "build"))
        aggregate_workflow = aggregate_root / ".github" / "workflows" / "build.yml"
        text = aggregate_workflow.read_text(encoding="utf-8")
        text = text.replace(
            "needs: [ build, test, qodanaScope, inspectCode, dependencySubmissionValidation, verify, lint, dependencyReview ]",
            "needs: [ build, test, qodanaScope, inspectCode, verify, lint, dependencyReview ]",
            1,
        )
        aggregate_workflow.write_text(text, encoding="utf-8")
        aggregate_bad_rc = run([
            "python3",
            str(POLICY_DIR / "check_required_contexts.py"),
            str(aggregate_root / ".github" / "merge-gate-policy.yml"),
            str(aggregate_root),
        ])
        expect(
            "check_required_contexts.py rejects a Merge Gate missing one component",
            aggregate_bad_rc != 0,
            failures,
        )

    build = (REPO_ROOT / "build.gradle.kts").read_text(encoding="utf-8")
    expect(
        "template UI-test workflow is absent until real UI tests exist",
        not (WORKFLOWS_DIR / "run-ui-tests.yml").exists(),
        failures,
    )
    expect(
        "template robot-server Gradle scaffold is absent",
        "runIdeForUiTests" not in build and "robotServerPlugin" not in build,
        failures,
    )


def check_actionlint(failures: list[str]) -> None:
    actionlint = resolve_actionlint()
    if not actionlint:
        expect("actionlint is available", False, failures)
        return

    good_rc = run([actionlint, *[str(path) for path in GOOD_FIXTURES]])
    expect("actionlint accepts the good fixtures", good_rc == 0, failures)

    real_rc = run([actionlint, *[str(path) for path in REAL_WORKFLOWS]])
    expect("actionlint accepts every real workflow", real_rc == 0, failures)


def check_zizmor(failures: list[str]) -> None:
    if not shutil.which("zizmor"):
        expect("zizmor is available", False, failures)
        return

    for fixture in BAD_FIXTURES:
        if fixture.name in ZIZMOR_LOCAL_ONLY_FIXTURES:
            continue
        rc = run(
            [
                "zizmor",
                "--offline",
                "--persona",
                "regular",
                str(fixture),
            ]
        )
        expect(
            f"zizmor rejects {fixture.relative_to(REPO_ROOT)}",
            rc != 0,
            failures,
        )

    for fixture in GOOD_FIXTURES:
        rc = run(
            [
                "zizmor",
                "--offline",
                "--persona",
                "regular",
                str(fixture),
            ]
        )
        expect(
            f"zizmor accepts {fixture.relative_to(REPO_ROOT)}",
            rc == 0,
            failures,
        )

    real_rc = run(
        [
            "zizmor",
            "--offline",
            "--persona",
            "regular",
            *[str(path) for path in REAL_WORKFLOWS],
        ]
    )
    expect("zizmor accepts every real workflow", real_rc == 0, failures)



def check_main_e2e_cache_seed(failures: list[str]) -> None:
    """Guard dependency-only cache production without a second writer/job key."""
    workflow = (WORKFLOWS_DIR / "e2e.yml").read_text(encoding="utf-8")
    trigger = re.search(
        r"(?m)^  push:\n    branches: \[main\]\n    paths:\n(?P<items>(?:      - '[^']+'\n)+)",
        workflow,
    )
    expected = {
        "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
        "gradle/**", "**/build.gradle.kts",
        "**/settings.gradle.kts", "**/gradle.properties",
        ".github/workflows/e2e.yml", ".github/workflows/build.yml",
    }
    actual = (
        set(re.findall(r"(?m)^      - '([^']+)'$", trigger["items"]))
        if trigger else set()
    )
    expect("main push seed matches exact Gradle input allowlist", actual == expected, failures)
    expect("seed never gates on all-source, docs, or every push", not (
        {"src/**", "**", "docs/**", "**/*.kt", "**/*.java"} & actual
    ), failures)
    expect("launcher-only gradlew never triggers protected-main cache prime",
           "gradlew" not in actual, failures)
    expect("existing E2E cache job identity remains unchanged",
           "  starterDriver:" in workflow and "    name: Starter / Driver E2E" in workflow,
           failures)
    expect("PRs are always read-only and main only may publish caches",
           "github.ref != 'refs/heads/main' || (github.event_name != 'push' && github.event_name != 'workflow_dispatch')" in workflow,
           failures)
    expect("main-only prime verifies exact checkout",
           'test "$(git rev-parse HEAD)" = "$GITHUB_SHA"' in workflow,
           failures)
    toolchain_envs = set(re.findall(
        r"-Dorg[.]gradle[.]java[.]installations[.]fromEnv=([A-Za-z0-9_]+)",
        workflow,
    ))
    expect("ARM64 E2E Gradle invocations use setup-java JAVA_HOME",
           toolchain_envs == {"JAVA_HOME"}, failures)
    expect("ARM64 E2E never references an x64 Java-home variable",
           "JAVA_HOME_25_X64" not in workflow, failures)
    expect("postmerge seed skips real product IDE E2E",
           all(
               f"      - name: {step}\n        if: ${{{{ github.event_name != 'push' }}}}" in workflow
               for step in (
                   "Build exact plugin artifact",
                   "Install ARM64 EGL runtime for IntelliJ Skiko",
                   "Run Starter / Driver E2E",
               )
           ), failures)

    expect("E2E failure diagnostics never archive the full IDE test tree",
           "\n            out/ide-tests/tests\n" not in workflow, failures)
    expect("E2E failure diagnostics retain bounded IDE logs and screenshots",
           "out/ide-tests/tests/**/log/idea.log" in workflow
           and "out/ide-tests/tests/**/screenshots/**" in workflow,
           failures)



def check_dependency_submission_main_scope(failures: list[str]) -> None:
    """Guard dependency-scoped default-branch graph submission."""
    workflow = (WORKFLOWS_DIR / "dependency-submission.yml").read_text(
        encoding="utf-8"
    )
    trigger = re.search(
        r"(?m)^  push:\n    branches: \[main\]\n    paths:\n(?P<items>(?:      - '[^']+'\n)+)",
        workflow,
    )
    expected = {
        "**/*.gradle", "**/*.gradle.kts", "**/gradle.properties",
        "**/gradle.lockfile", "**/gradle/dependency-locks/**",
        "**/*.versions.toml", "gradle/**", "buildSrc/**", "build-logic/**",
        "gradlew", "gradlew.bat", ".github/workflows/dependency-submission.yml",
    }
    actual = (
        set(re.findall(r"(?m)^      - '([^']+)'$", trigger["items"]))
        if trigger else set()
    )
    expect("dependency submission is limited to Gradle graph inputs",
           actual == expected, failures)
    expect("source and docs changes do not trigger graph submission",
           not ({"src/**", "docs/**", "**", "**/*.kt", "**/*.java"} & actual),
           failures)
    expect("manual submission stays main-only",
           "  workflow_dispatch:" in workflow
           and "github.ref == 'refs/heads/main'" in workflow, failures)
    expect("submission remains cutover-gated and cache read-only",
           "vars.ZMYBATIS_ADS_CUTOVER_DONE == 'true'" in workflow
           and "cache-read-only: true" in workflow
           and "dependency-graph: generate-and-submit" in workflow, failures)


def main() -> int:
    failures: list[str] = []
    check_local_policy(failures)
    check_main_e2e_cache_seed(failures)
    check_dependency_submission_main_scope(failures)
    check_actionlint(failures)
    check_zizmor(failures)

    if failures:
        print(f"\n{len(failures)} expectation(s) failed:")
        for failure in failures:
            print(f"  - {failure}")
        return 1

    print("\nAll negative/positive controls held.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
