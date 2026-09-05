#!/usr/bin/env python3
from __future__ import annotations

import argparse
import io
import re
import sys
import zipfile
from pathlib import Path
from xml.etree import ElementTree

TAG_RE = re.compile(r"^\d{2}\.\d{2}\.\d{2}\.\d{6}$")


def validate_tag(tag: str) -> list[str]:
    return [] if TAG_RE.fullmatch(tag) else [f"release tag {tag!r} must match yy.MM.dd.HHmmss"]


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
    archives = sorted(distributions.glob("*.zip"))
    if len(archives) != 1:
        return failures + [f"expected exactly one release ZIP, found {len(archives)}"]
    archive = archives[0]
    if tag not in archive.name:
        failures.append(f"release ZIP name {archive.name!r} does not contain version {tag!r}")
    versions = plugin_versions_from_zip(archive)
    if versions != [tag]:
        failures.append(f"artifact plugin.xml versions: expected {[tag]!r}, got {versions!r}")
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
        './gradlew clean buildPlugin verifyPlugin -PpluginVersion="$RELEASE_TAG"',
        'python3 .github/workflow-policy/check_release_provenance.py artifact "$RELEASE_TAG" build/distributions',
        './gradlew signPlugin -PpluginVersion="$RELEASE_TAG"',
        './gradlew publishPlugin -PpluginVersion="$RELEASE_TAG"',
    ]
    for fragment in required_release_fragments:
        if fragment not in release:
            failures.append(f"release.yml missing provenance gate: {fragment}")
    if "continue-on-error" in release:
        failures.append("release.yml must not use continue-on-error")
    if "pull_request:" in release or "workflow_dispatch:" in release:
        failures.append("release.yml must not publish from PR/manual-dispatch triggers")
    publish_pos = release.find("./gradlew publishPlugin")
    artifact_pos = release.find("check_release_provenance.py artifact")
    sign_pos = release.find("./gradlew signPlugin")
    if min(publish_pos, artifact_pos, sign_pos) < 0 or not (artifact_pos < sign_pos < publish_pos):
        failures.append("artifact verification and signing must precede publication")

    if '--target "$GITHUB_SHA"' not in build_workflow:
        failures.append("build.yml release draft must bind the tag target to exact main GITHUB_SHA")
    if "git show -s --format=%ct \"$GITHUB_SHA\"" not in build_workflow:
        failures.append("build.yml release draft version must derive deterministically from the exact commit")
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
