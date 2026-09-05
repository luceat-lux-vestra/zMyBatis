#!/usr/bin/env python3
from __future__ import annotations

import io
import tempfile
import zipfile
from pathlib import Path

from check_release_provenance import effective_version, validate_tag, verify_artifact, verify_static

REPO_ROOT = Path(__file__).resolve().parents[2]
GOOD_TAG = "v27.0.0"
GOOD_VERSION = "27.0.0"


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


def main() -> int:
    failures: list[str] = []
    expect("migration epoch publication tag accepted", not validate_tag(GOOD_TAG), failures)
    expect("later stable SemVer publication tag accepted", not validate_tag("v27.1.2"), failures)
    expect("SemVer prerelease publication tag accepted", not validate_tag("v27.1.0-beta.1"), failures)
    expect("effective plugin version strips one v prefix", effective_version(GOOD_TAG) == GOOD_VERSION, failures)
    expect("missing v prefix rejected", bool(validate_tag("27.0.0")), failures)
    expect("timestamp release identity rejected", bool(validate_tag("26.09.05.123456")), failures)
    expect("pre-migration SemVer rejected", bool(validate_tag("v26.99.99")), failures)
    expect("leading-zero major rejected", bool(validate_tag("v027.0.0")), failures)
    expect("leading-zero numeric prerelease rejected", bool(validate_tag("v27.0.0-01")), failures)
    expect("build metadata excluded from publication tag", bool(validate_tag("v27.0.0+build.7")), failures)
    expect("arbitrary tag rejected", bool(validate_tag("latest")), failures)
    expect("checked-in release contract passes static proof", not verify_static(REPO_ROOT), failures)

    with tempfile.TemporaryDirectory() as tmp:
        directory = Path(tmp)
        make_plugin_zip(directory, GOOD_VERSION, GOOD_VERSION)
        expect("matching artifact accepted", not verify_artifact(GOOD_TAG, directory), failures)

    with tempfile.TemporaryDirectory() as tmp:
        directory = Path(tmp)
        make_plugin_zip(directory, GOOD_VERSION, "27.0.1")
        expect("plugin.xml version mismatch rejected", bool(verify_artifact(GOOD_TAG, directory)), failures)

    with tempfile.TemporaryDirectory() as tmp:
        directory = Path(tmp)
        make_plugin_zip(directory, "27.0.1", GOOD_VERSION)
        expect("distribution filename mismatch rejected", bool(verify_artifact(GOOD_TAG, directory)), failures)

    with tempfile.TemporaryDirectory() as tmp:
        directory = Path(tmp)
        make_plugin_zip(directory, "27.1.0-beta.1", "27.1.0-beta.1")
        expect(
            "matching prerelease artifact accepted",
            not verify_artifact("v27.1.0-beta.1", directory),
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
