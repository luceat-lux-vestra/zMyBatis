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
            "needs: [ build, test, inspectCode, verify, lint, dependencyReview ]",
            "needs: [ build, test, inspectCode, verify, lint ]",
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
