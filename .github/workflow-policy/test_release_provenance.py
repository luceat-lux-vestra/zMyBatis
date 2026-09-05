#!/usr/bin/env python3
from __future__ import annotations

import io
import tempfile
import zipfile
from pathlib import Path

from check_release_provenance import validate_tag, verify_artifact, verify_static

REPO_ROOT = Path(__file__).resolve().parents[2]
GOOD_TAG = "26.09.05.123456"


def make_plugin_zip(directory: Path, tag: str, embedded_version: str) -> None:
    jar_bytes = io.BytesIO()
    with zipfile.ZipFile(jar_bytes, "w") as jar:
        jar.writestr(
            "META-INF/plugin.xml",
            f"<idea-plugin><id>com.algorist.zMyBatis</id><version>{embedded_version}</version></idea-plugin>",
        )
    with zipfile.ZipFile(directory / f"zMyBatis-{tag}.zip", "w") as outer:
        outer.writestr(f"zMyBatis/lib/zMyBatis-{tag}.jar", jar_bytes.getvalue())


def expect(label: str, condition: bool, failures: list[str]) -> None:
    print(f"{'ok  ' if condition else 'FAIL'} - {label}")
    if not condition:
        failures.append(label)


def main() -> int:
    failures: list[str] = []
    expect("valid release tag accepted", not validate_tag(GOOD_TAG), failures)
    expect("arbitrary tag rejected", bool(validate_tag("latest")), failures)
    expect("prefixed tag rejected", bool(validate_tag("v26.09.05.123456")), failures)
    expect("checked-in release contract passes static proof", not verify_static(REPO_ROOT), failures)

    with tempfile.TemporaryDirectory() as tmp:
        directory = Path(tmp)
        make_plugin_zip(directory, GOOD_TAG, GOOD_TAG)
        expect("matching artifact accepted", not verify_artifact(GOOD_TAG, directory), failures)

    with tempfile.TemporaryDirectory() as tmp:
        directory = Path(tmp)
        make_plugin_zip(directory, GOOD_TAG, "26.09.05.000000")
        expect("plugin.xml version mismatch rejected", bool(verify_artifact(GOOD_TAG, directory)), failures)

    with tempfile.TemporaryDirectory() as tmp:
        directory = Path(tmp)
        make_plugin_zip(directory, "26.09.05.000000", GOOD_TAG)
        expect("distribution filename mismatch rejected", bool(verify_artifact(GOOD_TAG, directory)), failures)

    with tempfile.TemporaryDirectory() as tmp:
        expect("missing artifact rejected", bool(verify_artifact(GOOD_TAG, Path(tmp))), failures)

    if failures:
        print(f"\n{len(failures)} release provenance expectation(s) failed")
        return 1
    print("\nAll release provenance controls held.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
