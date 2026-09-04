#!/usr/bin/env python3
"""Immutable action pin enforcement.

Every `uses:` reference to a third-party (or first-party) Action must be pinned
to a full-length 40-character commit SHA, not a floating tag or branch. A tag
such as `@v7` can be force-moved by the action owner (or anyone who compromises
that owner's account) to point at different, unreviewed code that this
repository's workflows would then execute automatically. A trailing `# v7`
comment is fine and expected - Dependabot keeps the SHA and the comment in
sync - only the ref before the comment must be a SHA.

Usage:
    check_pins.py <workflow-file-or-directory> [...]

Exits non-zero and prints every offending line if any `uses:` line is not
pinned to a 40-hex-character SHA. This must stay fail-closed: do not add an
allowlist/ignore mechanism to make a finding go away, fix the pin instead.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

PIN_PATTERN = re.compile(r"^uses:\s*[^#\s]+@[0-9a-f]{40}(?:\s+#.*)?$")
USES_PATTERN = re.compile(r"^uses:\s*(.+)$")


def iter_workflow_files(targets: list[str]) -> list[Path]:
    files: list[Path] = []
    for target in targets:
        path = Path(target)
        if path.is_dir():
            files.extend(sorted(path.glob("*.yml")))
            files.extend(sorted(path.glob("*.yaml")))
        elif path.is_file():
            files.append(path)
    return files


def find_unpinned(path: Path) -> list[str]:
    failures = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        stripped = line.strip()
        if stripped.startswith("uses:") and not PIN_PATTERN.match(stripped):
            failures.append(f"{path}:{number}: {stripped}")
    return failures


def main(argv: list[str]) -> int:
    if not argv:
        print("usage: check_pins.py <workflow-file-or-directory> [...]", file=sys.stderr)
        return 2

    files = iter_workflow_files(argv)
    if not files:
        print(f"no workflow files found under: {' '.join(argv)}", file=sys.stderr)
        return 2

    failures: list[str] = []
    for path in files:
        failures.extend(find_unpinned(path))

    if failures:
        print("Unpinned workflow actions (require a 40-hex-character commit SHA):")
        for failure in failures:
            print(f"  {failure}")
        return 1

    print(f"OK: every 'uses:' in {len(files)} file(s) is pinned to an immutable SHA.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
