#!/usr/bin/env python3
from __future__ import annotations

import argparse
import io
import re
import sys
import zipfile
from pathlib import Path
from xml.etree import ElementTree

# Publication tags are SemVer with a lowercase v prefix. Build metadata is intentionally
# excluded from publication identity; prerelease identifiers are supported. The maintained
# release line starts at v1.0.0; v0.x publication tags are rejected.
TAG_RE = re.compile(
    r"^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"
    r"(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$"
)
TAG_FORMAT = "vMAJOR.MINOR.PATCH[-PRERELEASE]"
MIN_PUBLICATION_MAJOR = 1


def validate_tag(tag: str) -> list[str]:
    match = TAG_RE.fullmatch(tag)
    if not match:
        return [f"release tag {tag!r} must match strict {TAG_FORMAT}"]
    major = int(match.group(1))
    if major < MIN_PUBLICATION_MAJOR:
        return [
            f"release tag {tag!r} is below the supported publication floor v{MIN_PUBLICATION_MAJOR}.0.0"
        ]
    prerelease = match.group(4)
    if prerelease:
        for identifier in prerelease.split("."):
            if identifier.isdigit() and len(identifier) > 1 and identifier.startswith("0"):
                return [
                    f"release tag {tag!r} has a numeric prerelease identifier with a leading zero"
                ]
    return []


def effective_version(tag: str) -> str:
    failures = validate_tag(tag)
    if failures:
        raise ValueError(failures[0])
    return tag[1:]


def plugin_versions_from_zip(path: Path) -> list[str]:
    versions: list[str] = []
    with zipfile.ZipFile(path) as outer:
        for name in outer.namelist():
            if not name.endswith(".jar"):
                continue
            with outer.open(name) as jar_stream:
                data = jar_stream.read()
            try:
                with zipfile.ZipFile(io.BytesIO(data)) as jar:
                    if "META-INF/plugin.xml" not in jar.namelist():
                        continue
                    root = ElementTree.fromstring(jar.read("META-INF/plugin.xml"))
                    version = root.findtext("version")
                    if version:
                        versions.append(version.strip())
            except zipfile.BadZipFile:
                continue
    return versions


def verify_artifact(tag: str, distributions: Path) -> list[str]:
    failures = validate_tag(tag)
    if failures:
        return failures
    version = effective_version(tag)
    archives = sorted(distributions.glob("*.zip"))
    if len(archives) != 1:
        return [f"expected exactly one release ZIP, found {len(archives)}"]
    archive = archives[0]
    if not archive.stem.endswith(f"-{version}"):
        failures.append(
            f"release ZIP name {archive.name!r} must end with exact effective version '-{version}.zip'"
        )
    versions = plugin_versions_from_zip(archive)
    if versions != [version]:
        failures.append(
            f"artifact plugin.xml versions: expected {[version]!r}, got {versions!r}"
        )
    return failures


def verify_static(repo: Path) -> list[str]:
    failures: list[str] = []
    build = (repo / "build.gradle.kts").read_text(encoding="utf-8")
    release = (repo / ".github/workflows/release.yml").read_text(encoding="utf-8")
    build_workflow = (repo / ".github/workflows/build.yml").read_text(encoding="utf-8")
    recovery = (repo / "docs/release-recovery.md").read_text(encoding="utf-8")

    required_build_fragments = [
        'providers.gradleProperty("pluginVersion").orElse("0.0.0-dev")',
        "version = effectivePluginVersion.get()",
        "version = effectivePluginVersion",
        "channels = effectivePluginVersion.map",
    ]
    for fragment in required_build_fragments:
        if fragment not in build:
            failures.append(f"build.gradle.kts missing release-version invariant: {fragment}")
    if "LocalDateTime" in build or "DateTimeFormatter" in build:
        failures.append("build.gradle.kts must not derive plugin version from wall clock time")
    if "dependsOn(patchChangelog)" in build:
        failures.append("publishPlugin must not mutate changelog source before publication")

    required_release_fragments = [
        "fetch-depth: 0",
        "persist-credentials: false",
        "environment: jetbrains-marketplace",
        "concurrency:",
        "cancel-in-progress: false",
        "id-token: write",
        "attestations: write",
        'git merge-base --is-ancestor "$TAG_COMMIT" origin/main',
        'python3 .github/workflow-policy/check_release_provenance.py tag "$RELEASE_TAG"',
        './gradlew clean buildPlugin verifyPlugin -PpluginVersion="$PLUGIN_VERSION"',
        'python3 .github/workflow-policy/check_release_provenance.py artifact "$RELEASE_TAG" build/distributions',
        './gradlew signPlugin verifyPluginSignature -PpluginVersion="$PLUGIN_VERSION"',
        'subject-path: ${{ steps.signed_artifact.outputs.path }}',
        "create-storage-record: false",
        './gradlew publishPlugin -x signPlugin -PpluginVersion="$PLUGIN_VERSION"',
        'RELEASE_ASSET: ${{ steps.signed_artifact.outputs.path }}',
        "Pending Marketplace publication identity exists; manual recovery is required.",
        "Published signed asset digest does not match release identity.",
        "Signed GitHub Release asset exists without a completed publication identity.",
        "Published identity requires its original pending identity.",
        "Pending and published release identities conflict.",
        ".marketplace_channel == $channel",
        "zmybatis-release-identity.json",
        "zmybatis-release-published.json",
        'publication_state: "pending"',
        "Reuse or automatically execute exact-source CodeQL",
        "Reuse or automatically execute exact-source Qodana",
        "needs: [ codeql_preflight, qodana_preflight ]",
        "actions: write # Only this isolated nonpublishing job can dispatch CodeQL.",
        "actions: write # Only this isolated nonpublishing job can dispatch trusted Qodana.",
        "check_release_codeql.py",
        "check_release_qodana.py",
        'steps.state.outputs.publish == \'true\'',
        "actions: read # Inspect trusted CodeQL/Qodana runs, jobs and original SARIF artifacts.",
    ]
    for fragment in required_release_fragments:
        if fragment not in release:
            failures.append(f"release.yml missing provenance gate: {fragment}")
    if release.count('PLUGIN_VERSION="${RELEASE_TAG#v}"') < 3:
        failures.append("release.yml must derive the effective SemVer from the v-prefixed tag for build/sign/publish")
    if '-PpluginVersion="$RELEASE_TAG"' in release:
        failures.append("release.yml must not pass the v-prefixed Git tag as the plugin version")
    if "continue-on-error" in release:
        failures.append("release.yml must not use continue-on-error")
    if "pull_request:" in release or "workflow_dispatch:" in release:
        failures.append("release.yml must not publish from PR/manual-dispatch triggers")

    workflow_header = release.split("\njobs:", 1)[0]
    if "permissions:\n  contents: read" not in workflow_header:
        failures.append("release.yml workflow-level authority must remain contents:read")
    for permission in ("contents: write", "id-token: write", "attestations: write"):
        if permission in workflow_header:
            failures.append(
                f"release.yml must scope {permission} to the release job, not the workflow"
            )
    release_job = release.split("\n  release:", 1)[1] if "\n  release:" in release else ""
    for permission in ("contents: write", "id-token: write", "attestations: write"):
        if permission not in release_job:
            failures.append(f"release job missing explicit authority: {permission}")
    if not re.search(r"uses:\s+actions/attest@[0-9a-f]{40}(?:\s+#.*)?$", release, re.MULTILINE):
        failures.append("actions/attest must be pinned to an immutable full commit SHA")
    if 'RELEASE_ASSET: ${{ steps.artifact.outputs.path }}' in release:
        failures.append("GitHub Release must not upload the unsigned buildPlugin archive")

    # Authorize separate, minimally privileged scanner preflight jobs instead of
    # granting actions:write to the token also used for plugin publication.
    codeql_preflight_job = (
        release.split("\n  codeql_preflight:", 1)[1].split("\n  qodana_preflight:", 1)[0]
        if "\n  codeql_preflight:" in release and "\n  qodana_preflight:" in release else ""
    )
    qodana_preflight_job = (
        release.split("\n  qodana_preflight:", 1)[1].split("\n  release:", 1)[0]
        if "\n  qodana_preflight:" in release and "\n  release:" in release else ""
    )
    require_gate = "steps.source.outputs.publish == 'true'"

    codeql_security_pos = codeql_preflight_job.find("check_release_codeql.py")
    codeql_recovery_pos = codeql_preflight_job.find(
        "Prove release source and skip completed Marketplace recovery"
    )
    codeql_source_pos = codeql_preflight_job.find("check_release_provenance.py tag")
    if not (
        codeql_source_pos >= 0
        and codeql_recovery_pos >= 0
        and codeql_security_pos > codeql_source_pos > codeql_recovery_pos
        and require_gate in codeql_preflight_job
        and "group: release-codeql-preflight" in codeql_preflight_job
        and "cancel-in-progress: false" in codeql_preflight_job
        and "path: .trusted-codeql-preflight" in codeql_preflight_job
        and ".trusted-codeql-preflight/.github/workflow-policy/check_release_codeql.py"
            in codeql_preflight_job
    ):
        failures.append(
            "CodeQL preflight must follow source/recovery classification and remain "
            "a trusted-main exact-source prerequisite"
        )

    qodana_security_pos = qodana_preflight_job.find("check_release_qodana.py")
    qodana_recovery_pos = qodana_preflight_job.find(
        "Prove release source and skip completed Marketplace recovery"
    )
    qodana_source_pos = qodana_preflight_job.find("check_release_provenance.py tag")
    if not (
        qodana_source_pos >= 0
        and qodana_recovery_pos >= 0
        and qodana_security_pos > qodana_source_pos > qodana_recovery_pos
        and require_gate in qodana_preflight_job
        and "group: release-qodana-preflight" in qodana_preflight_job
        and "cancel-in-progress: false" in qodana_preflight_job
        and "path: .trusted-qodana-preflight" in qodana_preflight_job
        and ".trusted-qodana-preflight/.github/workflow-policy/check_release_qodana.py"
            in qodana_preflight_job
    ):
        failures.append(
            "Qodana preflight must follow source/recovery classification and remain "
            "a trusted-main exact-source prerequisite"
        )

    if (
        "needs: [ codeql_preflight, qodana_preflight ]" not in release_job
        or "      - name: Setup Java" not in release_job
    ):
        failures.append(
            "release publisher must depend on both exact-source security preflights "
            "before any publisher work"
        )

    for label, preflight_job in (
        ("CodeQL", codeql_preflight_job),
        ("Qodana", qodana_preflight_job),
    ):
        if not (
            "contents: read" in preflight_job
            and "actions: write" in preflight_job
            and "contents: write" not in preflight_job
            and "id-token: write" not in preflight_job
            and "attestations: write" not in preflight_job
            and "environment: jetbrains-marketplace" not in preflight_job
            and "continue-on-error" not in preflight_job
            and not any(
                op in preflight_job
                for op in ("publishPlugin", "gh release upload", "signPlugin")
            )
        ):
            failures.append(
                f"{label} dispatch job must remain isolated and nonpublishing"
            )
    artifact_pos = release.find("check_release_provenance.py artifact")
    sign_pos = release.find("./gradlew signPlugin verifyPluginSignature")
    capture_pos = release.find("Capture verified signed release asset")
    attest_pos = release.find("Attest verified signed release asset")
    lock_pos = release.find("Lock publication identity before Marketplace mutation")
    publish_pos = release.find("./gradlew publishPlugin -x signPlugin")
    recheck_pos = release.find("Recheck signed artifact identity after Marketplace publication")
    upload_pos = release.find("Upload verified signed release asset")
    complete_pos = release.find("Mark publication complete")
    positions = [
        artifact_pos,
        sign_pos,
        capture_pos,
        attest_pos,
        lock_pos,
        publish_pos,
        recheck_pos,
        upload_pos,
        complete_pos,
    ]
    if min(positions) < 0 or positions != sorted(positions):
        failures.append(
            "release order must be artifact proof -> sign/verify -> signed capture -> "
            "attestation -> publication lock -> Marketplace publish -> digest recheck -> "
            "signed GitHub Release upload -> completed marker"
        )

    if "group: release-${{ github.event.release.tag_name }}" not in release:
        failures.append("release concurrency must be scoped to the immutable release tag")
    if "environment: jetbrains-marketplace" not in release_job:
        failures.append("release job must be gated by jetbrains-marketplace environment")

    forbidden_draft_fragments = [
        "releaseDraft:",
        "gh release create",
        "git show -s --format=%ct",
        "%y.%m.%d.%H%M%S",
        "date -u -d",
    ]
    for fragment in forbidden_draft_fragments:
        if fragment in build_workflow:
            failures.append(
                f"build.yml ordinary main/PR CI must not synthesize release identity: {fragment}"
            )
    required_recovery_fragments = [
        "`Unknown` fails closed",
        "do not call `publishPlugin` again",
        "explicit maintainer authorization",
        "zmybatis-release-published.json",
        "version reuse",
    ]
    for fragment in required_recovery_fragments:
        if fragment not in recovery:
            failures.append(f"release recovery runbook missing invariant: {fragment}")

    return failures


def emit(failures: list[str]) -> int:
    if failures:
        print("release provenance violations:")
        for failure in failures:
            print(f"  - {failure}")
        return 1
    print("OK: release provenance contract holds.")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="cmd", required=True)
    tag_parser = sub.add_parser("tag")
    tag_parser.add_argument("tag")
    artifact_parser = sub.add_parser("artifact")
    artifact_parser.add_argument("tag")
    artifact_parser.add_argument("distributions", type=Path)
    static_parser = sub.add_parser("static")
    static_parser.add_argument("repo", type=Path)
    args = parser.parse_args(argv)
    if args.cmd == "tag":
        return emit(validate_tag(args.tag))
    if args.cmd == "artifact":
        return emit(verify_artifact(args.tag, args.distributions))
    return emit(verify_static(args.repo))


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
