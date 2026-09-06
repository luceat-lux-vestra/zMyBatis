#!/usr/bin/env python3
from __future__ import annotations

import tempfile
from pathlib import Path

from check_governance_contract import verify

REPO_ROOT = Path(__file__).resolve().parents[2]


def expect(label: str, condition: bool, failures: list[str]) -> None:
    print(f"{'ok  ' if condition else 'FAIL'} - {label}")
    if not condition:
        failures.append(label)


def main() -> int:
    failures: list[str] = []
    expect(
        "checked-in authoritative governance matches hardened baseline",
        not verify(REPO_ROOT),
        failures,
    )

    with tempfile.TemporaryDirectory() as tmp:
        repo = Path(tmp)
        (repo / "docs").mkdir()
        (repo / "AGENTS.md").write_text(
            "## Version and release contract (current state, not yet hardened)\n"
            "`build.gradle.kts` derives the plugin version from `LocalDateTime.now()`\n",
            encoding="utf-8",
        )
        (repo / "CONTRIBUTING.md").write_text(
            "release.yml is pending the separate release-provenance PR\n",
            encoding="utf-8",
        )
        (repo / "docs/test-contracts.md").write_text(
            "MyPluginTest and rename template fixtures are current evidence\n",
            encoding="utf-8",
        )
        stale = verify(repo)
        expect(
            "retired current-state claims are rejected",
            any("retired state" in item for item in stale),
            failures,
        )
        expect(
            "missing hardened-baseline statements fail closed",
            any("missing current-baseline statement" in item for item in stale),
            failures,
        )

    if failures:
        print(f"\n{len(failures)} governance expectation(s) failed")
        return 1
    print("\nAll governance contract controls held.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
