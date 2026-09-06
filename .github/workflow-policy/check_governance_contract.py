#!/usr/bin/env python3
"""Fail closed when authoritative engineering/contribution docs regress to retired state."""
from __future__ import annotations

import argparse
from pathlib import Path

AUTHORITATIVE_DOCS = ("AGENTS.md", "CONTRIBUTING.md", "docs/test-contracts.md")

RETIRED_FRAGMENTS = (
    "Version and release contract (current state, not yet hardened)",
    "build.gradle.kts` derives the plugin version from `LocalDateTime.now()",
    "pending the separate release-provenance",
    "intentionally out of scope for the `workflow-lint.yml` checks right now",
    "session persistence historically namespaces application-level `PropertiesComponent` data with `project.basePath.hashCode()`",
    "datasource restoration historically resolves by display name",
    "MyPluginTest` and `src/test/testData/rename/` are template leftovers",
    "MyPluginTest`/`src/test/testData/rename/` are template leftovers",
    "build.yml` validates code and stages (but never publishes) a draft release",
    "zMyBatis-public",
)

REQUIRED_FRAGMENTS = {
    "AGENTS.md": (
        "## 5. Datasource / schema / session identity — hardened baseline",
        "restart restoration uses a stable IDE datasource UUID",
        "## 8. CI / GitHub Actions — hardened baseline",
        "release.yml` is included in pinning, permission, actionlint, zizmor, and release-provenance review",
        "## 9. Version and release contract — hardened baseline",
        "vMAJOR.MINOR.PATCH[-PRERELEASE]",
        "0.0.0-dev",
        "refs/tags/v*",
        "## 10. Current product gaps versus Leap target",
    ),
    "CONTRIBUTING.md": (
        "the old template/debug tests were removed",
        "release.yml` is part of the current hardened workflow review surface",
    ),
    "docs/test-contracts.md": (
        "The old IntelliJ template rename test and evaluator debug/reproduction files were removed",
        "Those gaps are explicit so a green `Test` context is not misrepresented",
    ),
}


def verify(repo: Path) -> list[str]:
    failures: list[str] = []
    texts: dict[str, str] = {}

    for name in AUTHORITATIVE_DOCS:
        path = repo / name
        if not path.is_file():
            failures.append(f"missing authoritative governance document: {name}")
            continue
        texts[name] = path.read_text(encoding="utf-8")

    combined = "\n".join(texts.values())
    for fragment in RETIRED_FRAGMENTS:
        if fragment in combined:
            failures.append(f"retired state is presented in authoritative governance: {fragment}")

    for name, fragments in REQUIRED_FRAGMENTS.items():
        text = texts.get(name, "")
        for fragment in fragments:
            if fragment not in text:
                failures.append(f"{name} missing current-baseline statement: {fragment}")

    return failures


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("repo", type=Path)
    args = parser.parse_args()
    failures = verify(args.repo)
    if failures:
        print("governance contract violations:")
        for failure in failures:
            print(f"  - {failure}")
        return 1
    print("OK: authoritative governance matches the hardened baseline.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
