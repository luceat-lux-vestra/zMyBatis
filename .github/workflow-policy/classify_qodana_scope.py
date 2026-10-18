#!/usr/bin/env python3
"""Conservative changed-file classifier for PR-time Qodana applicability.

Stage C runs this in shadow mode only. The classifier may prove a PR
non-Qodana-relevant, but it does not yet authorize skipping Inspect code.
UNKNOWN / incomplete metadata always means Qodana required.
"""
from __future__ import annotations

import argparse
import json
import tempfile
from dataclasses import dataclass
from pathlib import Path

ALLOWED_STATUSES = {"added", "modified", "removed", "renamed"}

# These files can change Qodana execution, evidence, release preflight, or the
# classifier itself. They must never classify their own change as non-risk.
QODANA_CONTROL_PATHS = {
    "qodana.yml",
    ".github/merge-gate-policy.yml",
    ".github/workflows/build.yml",
    ".github/workflows/qodana-full.yml",
    ".github/workflows/release.yml",
    ".github/workflow-policy/check_qodana_gate.py",
    ".github/workflow-policy/check_qodana_sarif.py",
    ".github/workflow-policy/check_release_qodana.py",
    ".github/workflow-policy/check_release_provenance.py",
    ".github/workflow-policy/classify_qodana_scope.py",
    ".github/workflow-policy/test_qodana_gate.py",
    ".github/workflow-policy/test_policy.py",
    ".github/workflow-policy/test_release_provenance.py",
}

SAFE_GITHUB_SUFFIXES = (".md", ".yml", ".yaml", ".py")


@dataclass(frozen=True)
class Decision:
    required: bool
    reason: str
    changed_count: int


def is_proven_non_qodana_path(path: str) -> bool:
    if path in QODANA_CONTROL_PATHS:
        return False
    lower = path.lower()
    # QDJVM 2026.2 evidence for this repository has no Markdown inspection
    # language. Keep Markdown source-neutral regardless of directory.
    if lower.endswith(".md"):
        return True
    # QDJVM evidence contains JAVA/UAST/Kotlin/XML/RegExp, but no Python,
    # YAML, Shell, or JSON inspection language. Only YAML/Python/Markdown
    # inside .github are admitted here; everything else remains conservative.
    if path.startswith(".github/") and lower.endswith(SAFE_GITHUB_SUFFIXES):
        return True
    return False


def flatten_payload(payload: object) -> tuple[list[dict], str | None]:
    if not isinstance(payload, list) or not payload:
        return [], "metadata-empty-or-not-list"
    if all(isinstance(item, dict) for item in payload):
        return list(payload), None
    if all(isinstance(page, list) for page in payload):
        flattened: list[dict] = []
        for page in payload:
            if not all(isinstance(item, dict) for item in page):
                return [], "metadata-page-malformed"
            flattened.extend(page)
        if not flattened:
            return [], "metadata-empty"
        return flattened, None
    return [], "metadata-shape-mixed"


def classify(payload: object, expected_count: int | None = None) -> Decision:
    files, error = flatten_payload(payload)
    if error:
        return Decision(True, error, 0)

    if expected_count is not None:
        if expected_count <= 0:
            return Decision(True, "expected-count-invalid", len(files))
        if len(files) != expected_count:
            return Decision(True, "metadata-count-mismatch", len(files))

    seen: set[str] = set()
    for record in files:
        filename = record.get("filename")
        status = record.get("status")
        if not isinstance(filename, str) or not filename:
            return Decision(True, "filename-missing", len(files))
        if filename in seen:
            return Decision(True, "duplicate-filename", len(files))
        seen.add(filename)
        if status not in ALLOWED_STATUSES:
            return Decision(True, "status-unknown", len(files))

        paths = [filename]
        if status == "renamed":
            previous = record.get("previous_filename")
            if not isinstance(previous, str) or not previous:
                return Decision(True, "rename-identity-incomplete", len(files))
            paths.append(previous)

        for path in paths:
            if not is_proven_non_qodana_path(path):
                return Decision(True, f"qodana-relevant:{path}", len(files))

    return Decision(False, "all-paths-proven-non-qodana", len(files))


def write_output(path: Path, decision: Decision) -> None:
    with path.open("a", encoding="utf-8") as out:
        out.write(f"qodana_required={'true' if decision.required else 'false'}\n")
        out.write(f"qodana_reason={decision.reason}\n")
        out.write(f"qodana_changed_count={decision.changed_count}\n")


def self_test() -> None:
    def decide(files: list[dict], expected: int | None = None) -> Decision:
        return classify([files], expected_count=expected)

    safe_docs = [
        {"filename": "README.md", "status": "modified"},
        {"filename": "docs/release/process.md", "status": "added"},
    ]
    assert not decide(safe_docs, 2).required
    assert not decide(
        [{"filename": ".github/workflows/e2e.yml", "status": "modified"}], 1
    ).required
    assert not decide(
        [{"filename": ".github/workflows/codeql.yml", "status": "modified"}], 1
    ).required
    assert not decide(
        [{"filename": ".github/workflow-policy/check_live_settings.py", "status": "modified"}], 1
    ).required
    assert not decide(
        [{
            "filename": "docs/new-name.md",
            "previous_filename": "docs/old-name.md",
            "status": "renamed",
        }],
        1,
    ).required

    risky = [
        "src/main/kotlin/com/example/App.kt",
        "core/src/test/kotlin/com/example/AppTest.kt",
        "build.gradle.kts",
        "gradle/libs.versions.toml",
        "src/main/resources/META-INF/plugin.xml",
        "qodana.yml",
        ".github/workflows/build.yml",
        ".github/workflows/qodana-full.yml",
        ".github/workflows/release.yml",
        ".github/workflow-policy/check_qodana_sarif.py",
        ".github/workflow-policy/classify_qodana_scope.py",
    ]
    for path in risky:
        result = decide([{"filename": path, "status": "modified"}], 1)
        assert result.required and result.reason == f"qodana-relevant:{path}", path

    renamed_from_source = decide([{
        "filename": "docs/old-code.md",
        "previous_filename": "src/main/kotlin/com/example/Old.kt",
        "status": "renamed",
    }], 1)
    assert renamed_from_source.required

    assert classify([], 0).required
    assert classify([[]], 1).reason == "metadata-empty"
    assert decide(safe_docs, 3).reason == "metadata-count-mismatch"
    assert decide([{"filename": "README.md", "status": "copied"}], 1).reason == "status-unknown"
    assert decide([{"filename": "README.md", "status": "renamed"}], 1).reason == "rename-identity-incomplete"
    assert classify([[{"status": "modified"}]], 1).reason == "filename-missing"
    assert classify([[
        {"filename": "README.md", "status": "modified"},
        {"filename": "README.md", "status": "modified"},
    ]], 2).reason == "duplicate-filename"
    assert classify([
        [{"filename": "README.md", "status": "modified"}],
        [{"filename": ".github/workflows/e2e.yml", "status": "modified"}],
    ], 2).required is False

    with tempfile.TemporaryDirectory() as directory:
        output = Path(directory) / "github-output"
        write_output(output, Decision(False, "safe", 2))
        text = output.read_text(encoding="utf-8")
        assert "qodana_required=false\n" in text
        assert "qodana_reason=safe\n" in text
        assert "qodana_changed_count=2\n" in text

    print("Qodana PR-scope classifier negative controls passed.")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path)
    parser.add_argument("--expected-count", type=int)
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        return 0
    if args.input is None:
        parser.error("--input is required unless --self-test is used")

    try:
        payload = json.loads(args.input.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        decision = Decision(True, "metadata-unreadable", 0)
    else:
        decision = classify(payload, expected_count=args.expected_count)

    print(json.dumps({
        "qodana_required": decision.required,
        "reason": decision.reason,
        "changed_count": decision.changed_count,
    }, sort_keys=True))
    if args.github_output is not None:
        write_output(args.github_output, decision)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
