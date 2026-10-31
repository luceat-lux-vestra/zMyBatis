#!/usr/bin/env python3
"""Narrow compatibility adapter for actionlint's pending GitHub `$/` support.

GitHub supports `$/path` as the self-repository syntax, but actionlint 1.7.12
predates that syntax. Upstream tracking:
- rhysd/actionlint#711
- rhysd/actionlint#732

Retire this adapter when a stable actionlint release supports `$/` natively.
The exact-version guard intentionally turns an actionlint upgrade into a review
point instead of silently carrying the suppression forward.
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

EXPECTED_ACTIONLINT_VERSION = "1.7.12"
SELF_REPOSITORY_WORKFLOW = "$/.github/workflows/e2e.yml"
SELF_REPOSITORY_DIAGNOSTIC = re.compile(
    r'^reusable workflow call "\$/\.github/workflows/e2e\.yml" at "uses" '
    r'is not following the format "owner/repo/path/to/workflow\.yml@ref" nor '
    r'"\./path/to/workflow\.yml"\. see '
    r'https://docs\.github\.com/en/actions/learn-github-actions/reusing-workflows '
    r'for more details$'
)


def actionlint_version(binary: str) -> str | None:
    result = subprocess.run(
        [binary, "-version"],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        return None
    lines = result.stdout.strip().splitlines()
    return lines[0].strip() if lines else None


def run_actionlint(binary: str, targets: list[str]) -> int:
    version = actionlint_version(binary)
    if version != EXPECTED_ACTIONLINT_VERSION:
        print(
            "actionlint self-repository compatibility shim requires review: "
            f"expected {EXPECTED_ACTIONLINT_VERSION}, got {version!r}. "
            "Remove this shim when stable actionlint supports $/ natively "
            "(upstream #711/#732).",
            file=sys.stderr,
        )
        return 2

    result = subprocess.run(
        [
            binary,
            "-ignore",
            SELF_REPOSITORY_DIAGNOSTIC.pattern,
            *targets,
        ],
        check=False,
    )
    return result.returncode


def self_test() -> int:
    known_gap = (
        'reusable workflow call "$/.github/workflows/e2e.yml" at "uses" '
        'is not following the format "owner/repo/path/to/workflow.yml@ref" nor '
        '"./path/to/workflow.yml". see '
        "https://docs.github.com/en/actions/learn-github-actions/reusing-workflows "
        "for more details"
    )
    controls = {
        "known self-repository gap": bool(SELF_REPOSITORY_DIAGNOSTIC.fullmatch(known_gap)),
        "different self-repository path is not suppressed": not bool(
            SELF_REPOSITORY_DIAGNOSTIC.fullmatch(
                known_gap.replace(
                    "$/.github/workflows/e2e.yml",
                    "$/.github/workflows/other.yml",
                )
            )
        ),
        "workspace-relative call is not suppressed": not bool(
            SELF_REPOSITORY_DIAGNOSTIC.fullmatch(
                known_gap.replace(
                    "$/.github/workflows/e2e.yml",
                    "./.github/workflows/e2e.yml",
                )
            )
        ),
        "unrelated workflow-call diagnostic is not suppressed": not bool(
            SELF_REPOSITORY_DIAGNOSTIC.fullmatch(
                'reusable workflow call "owner/repo/.github/workflows/e2e.yml@main" '
                'uses a mutable ref [workflow-call]'
            )
        ),
    }
    failed = [name for name, ok in controls.items() if not ok]
    if failed:
        for name in failed:
            print(f"FAIL: {name}", file=sys.stderr)
        return 1

    print("OK: actionlint compatibility suppression is exact and fail-closed.")
    return 0


def main(argv: list[str]) -> int:
    if argv == ["--self-test"]:
        return self_test()
    if len(argv) < 2:
        print(
            "usage: actionlint_compat.py <actionlint-binary> <workflow> [...] "
            "| --self-test",
            file=sys.stderr,
        )
        return 2
    binary, *targets = argv
    if not Path(binary).is_file():
        print(f"actionlint binary does not exist: {binary}", file=sys.stderr)
        return 2
    return run_actionlint(binary, targets)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
