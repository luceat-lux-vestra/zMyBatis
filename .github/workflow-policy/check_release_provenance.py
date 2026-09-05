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
# excluded from publication identity; prerelease identifiers are supported. The legacy
# Marketplace line uses 26.x timestamp-like versions, so 27 is the migration floor that
# keeps new publications monotonically newer for existing installations.
TAG_RE = re.compile(
    r"^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"
    r"(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$"
)
TAG_FORMAT = "vMAJOR.MINOR.PATCH[-PRERELEASE]"
MIN_PUBLICATION_MAJOR = 27


def validate_tag(tag: str) -> list[str]:
    match = TAG_RE.fullmatch(tag)
    if not match:
        return [f"release tag {tag!r} must match strict {TAG_FORMAT}"]
    major = int(match.group(1))
    if major < MIN_PUBLICATION_MAJOR:
        return [
            f"release tag {tag!r} is below the SemVer migration floor v{MIN_PUBLICATION_MAJOR}.0.0"
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
        'git merge-base --is-ancestor "$TAG_COMMIT" origin/main',
        'python3 .github/workflow-policy/check_release_provenance.py tag "$RELEASE_TAG"',
        './gradlew clean buildPlugin verifyPlugin -PpluginVersion="$PLUGIN_VERSION"',
        'python3 .github/workflow-policy/check_release_provenance.py artifact "$RELEASE_TAG" build/distributions',
        './gradlew signPlugin -PpluginVersion="$PLUGIN_VERSION"',
        './gradlew publishPlugin -PpluginVersion="$PLUGIN_VERSION"',
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
    publish_pos = release.find("./gradlew publishPlugin")
    artifact_pos = release.find("check_release_provenance.py artifact")
    sign_pos = release.find("./gradlew signPlugin")
    if min(publish_pos, artifact_pos, sign_pos) < 0 or not (artifact_pos < sign_pos < publish_pos):
        failures.append("artifact verification and signing must precede publication")

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
