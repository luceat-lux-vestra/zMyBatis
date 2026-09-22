#!/usr/bin/env python3
from __future__ import annotations

import io
import re
import shutil
import tempfile
import zipfile
from pathlib import Path

from check_release_provenance import effective_version, validate_tag, verify_artifact, verify_static

REPO_ROOT = Path(__file__).resolve().parents[2]
GOOD_TAG = "v1.0.0"
GOOD_VERSION = "1.0.0"


def make_plugin_zip(directory: Path, filename_version: str, embedded_version: str) -> None:
    jar_bytes = io.BytesIO()
    with zipfile.ZipFile(jar_bytes, "w") as jar:
        jar.writestr(
            "META-INF/plugin.xml",
            f"<idea-plugin><id>com.algorist.zMyBatis</id><version>{embedded_version}</version></idea-plugin>",
        )
    with zipfile.ZipFile(directory / f"zMyBatis-{filename_version}.zip", "w") as outer:
        outer.writestr(f"zMyBatis/lib/zMyBatis-{filename_version}.jar", jar_bytes.getvalue())


def expect(label: str, condition: bool, failures: list[str]) -> None:
    print(f"{'ok  ' if condition else 'FAIL'} - {label}")
    if not condition:
        failures.append(label)


def make_static_fixture(directory: Path, release_text: str) -> None:
    workflows = directory / ".github" / "workflows"
    workflows.mkdir(parents=True)
    shutil.copy2(REPO_ROOT / "build.gradle.kts", directory / "build.gradle.kts")
    shutil.copy2(REPO_ROOT / ".github/workflows/build.yml", workflows / "build.yml")
    docs = directory / "docs"
    docs.mkdir(parents=True)
    shutil.copy2(REPO_ROOT / "docs/release-recovery.md", docs / "release-recovery.md")
    (workflows / "release.yml").write_text(release_text, encoding="utf-8")


def main() -> int:
    failures: list[str] = []
    expect("first stable publication tag accepted", not validate_tag(GOOD_TAG), failures)
    expect("later stable SemVer publication tag accepted", not validate_tag("v1.1.2"), failures)
    expect("SemVer prerelease publication tag accepted", not validate_tag("v1.1.0-beta.1"), failures)
    expect("effective plugin version strips one v prefix", effective_version(GOOD_TAG) == GOOD_VERSION, failures)
    expect("missing v prefix rejected", bool(validate_tag("1.0.0")), failures)
    expect("timestamp release identity rejected", bool(validate_tag("26.09.05.123456")), failures)
    expect("pre-1.0 publication tag rejected", bool(validate_tag("v0.99.99")), failures)
    expect("leading-zero major rejected", bool(validate_tag("v01.0.0")), failures)
    expect("leading-zero numeric prerelease rejected", bool(validate_tag("v1.0.0-01")), failures)
    expect("build metadata excluded from publication tag", bool(validate_tag("v1.0.0+build.7")), failures)
    expect("arbitrary tag rejected", bool(validate_tag("latest")), failures)
    expect("checked-in release contract passes static proof", not verify_static(REPO_ROOT), failures)

    release_text = (REPO_ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
    with tempfile.TemporaryDirectory() as tmp:
        fixture = Path(tmp)
        make_static_fixture(
            fixture,
            release_text.replace(
                "RELEASE_ASSET: ${{ steps.signed_artifact.outputs.path }}",
                "RELEASE_ASSET: ${{ steps.artifact.outputs.path }}",
                1,
            ),
        )
        expect(
            "unsigned GitHub Release artifact regression rejected",
            bool(verify_static(fixture)),
            failures,
        )

    with tempfile.TemporaryDirectory() as tmp:
        fixture = Path(tmp)
        make_static_fixture(
            fixture,
            re.sub(
                r"actions/attest@[0-9a-f]{40}",
                "actions/attest@v4",
                release_text,
                count=1,
            ),
        )
        expect(
            "mutable attestation action regression rejected",
            bool(verify_static(fixture)),
            failures,
        )

    with tempfile.TemporaryDirectory() as tmp:
        fixture = Path(tmp)
        make_static_fixture(
            fixture,
            release_text.replace(
                "permissions:\n  contents: read",
                "permissions:\n  contents: write\n  id-token: write\n  attestations: write",
                1,
            ),
        )
        expect(
            "workflow-level release/attestation write authority regression rejected",
            bool(verify_static(fixture)),
            failures,
        )

    with tempfile.TemporaryDirectory() as tmp:
        fixture = Path(tmp)
        make_static_fixture(
            fixture,
            release_text.replace(
                "environment: jetbrains-marketplace",
                "environment: unrestricted-release",
                1,
            ),
        )
        expect(
            "release environment regression rejected",
            bool(verify_static(fixture)),
            failures,
        )

    with tempfile.TemporaryDirectory() as tmp:
        fixture = Path(tmp)
        make_static_fixture(
            fixture,
            release_text.replace(
                "Lock publication identity before Marketplace mutation",
                "Publication mutation without durable lock",
                1,
            ),
        )
        expect(
            "missing publication lock regression rejected",
            bool(verify_static(fixture)),
            failures,
        )

    with tempfile.TemporaryDirectory() as tmp:
        fixture = Path(tmp)
        make_static_fixture(
            fixture,
            release_text.replace(
                "concurrency:\n  group: release-${{ github.event.release.tag_name }}\n  cancel-in-progress: false\n\n",
                "",
                1,
            ),
        )
        expect(
            "missing per-tag release concurrency rejected",
            bool(verify_static(fixture)),
            failures,
        )

    with tempfile.TemporaryDirectory() as tmp:
        fixture = Path(tmp)
        make_static_fixture(
            fixture,
            release_text.replace(
                "Pending and published release identities conflict.",
                "Conflicting identities ignored.",
                1,
            ),
        )
        expect(
            "completed identity lineage regression rejected",
            bool(verify_static(fixture)),
            failures,
        )

    with tempfile.TemporaryDirectory() as tmp:
        directory = Path(tmp)
        make_plugin_zip(directory, GOOD_VERSION, GOOD_VERSION)
        expect("matching artifact accepted", not verify_artifact(GOOD_TAG, directory), failures)

    with tempfile.TemporaryDirectory() as tmp:
        directory = Path(tmp)
        make_plugin_zip(directory, GOOD_VERSION, "1.0.1")
        expect("plugin.xml version mismatch rejected", bool(verify_artifact(GOOD_TAG, directory)), failures)

    with tempfile.TemporaryDirectory() as tmp:
        directory = Path(tmp)
        make_plugin_zip(directory, "1.0.1", GOOD_VERSION)
        expect("distribution filename mismatch rejected", bool(verify_artifact(GOOD_TAG, directory)), failures)

    with tempfile.TemporaryDirectory() as tmp:
        directory = Path(tmp)
        make_plugin_zip(directory, "1.1.0-beta.1", "1.1.0-beta.1")
        expect(
            "matching prerelease artifact accepted",
            not verify_artifact("v1.1.0-beta.1", directory),
            failures,
        )

    with tempfile.TemporaryDirectory() as tmp:
        expect("missing artifact rejected", bool(verify_artifact(GOOD_TAG, Path(tmp))), failures)

    if failures:
        print(f"\n{len(failures)} release provenance expectation(s) failed")
        return 1
    print("\nAll release provenance controls held.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
