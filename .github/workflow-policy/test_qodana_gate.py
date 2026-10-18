#!/usr/bin/env python3
"""Deterministic positive and negative controls for check_qodana_gate.py."""
from __future__ import annotations

import subprocess
import sys
import tempfile
from pathlib import Path

POLICY_DIR = Path(__file__).resolve().parent
CHECKER = POLICY_DIR / "check_qodana_gate.py"
PIN = "a" * 40

GOOD_WORKFLOW = f"""name: Build
on: pull_request
permissions:
  contents: read
  pull-requests: read
jobs:
  qodanaScope:
    name: Qodana scope
    runs-on: ubuntu-latest
    outputs:
      qodana_required: ${{{{ steps.scope.outputs.qodana_required }}}}
      qodana_reason: ${{{{ steps.scope.outputs.qodana_reason }}}}
      qodana_changed_count: ${{{{ steps.scope.outputs.qodana_changed_count }}}}
    steps:
      - name: Fetch trusted Qodana classifier policy
        uses: actions/checkout@{PIN}
        with:
          ref: ${{{{ github.event.pull_request.base.sha }}}}
          path: .trusted-qodana-scope
          persist-credentials: false
      - name: Decide Qodana applicability
        id: scope
        run: |
          classifier=".trusted-qodana-scope/.github/workflow-policy/classify_qodana_scope.py"
          test "$(git -C .trusted-qodana-scope rev-parse HEAD)" = "$PR_BASE_SHA"
          expected="$(gh api "repos/$GITHUB_REPOSITORY/pulls/$PR_NUMBER" --jq '.changed_files')"
          gh api --paginate --slurp "repos/$GITHUB_REPOSITORY/pulls/$PR_NUMBER/files?per_page=100"
          echo "qodana_required=true"
          echo "qodana_reason=metadata-api-failure"
          echo "qodana_reason=non-pr-full-scan"
  inspectCode:
    name: Inspect code
    needs: [ qodanaScope ]
    runs-on: ubuntu-latest
    steps:
      - name: Require valid Qodana scope decision
        run: |
          test "$QODANA_REASON" = "all-paths-proven-non-qodana"
      - name: Qodana not applicable
        if: ${{{{ needs.qodanaScope.outputs.qodana_required == 'false' }}}}
        run: echo not-applicable
      - name: Qodana - Code Inspection
        if: ${{{{ needs.qodanaScope.outputs.qodana_required == 'true' }}}}
        uses: JetBrains/qodana-action@{PIN}
        with:
          args: --fail-threshold 0
          pr-mode: false
          upload-result: true
"""

GOOD_QODANA = """version: \"1.0\"
linter: jetbrains/qodana-jvm-community:2026.2
projectJDK: \"21\"
profile:
  name: qodana.recommended
exclude:
  - name: All
    paths:
      - .qodana
"""


def run_case(workflow: str, qodana: str) -> int:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        workflow_path = root / "build.yml"
        qodana_path = root / "qodana.yml"
        workflow_path.write_text(workflow, encoding="utf-8")
        qodana_path.write_text(qodana, encoding="utf-8")
        return subprocess.run(
            [sys.executable, str(CHECKER), str(workflow_path), str(qodana_path)],
            capture_output=True,
            text=True,
        ).returncode


def expect(label: str, condition: bool, failures: list[str]) -> None:
    print(f"{'ok  ' if condition else 'FAIL'} - {label}")
    if not condition:
        failures.append(label)


def main() -> int:
    failures: list[str] = []

    expect("accepts the fail-closed gate", run_case(GOOD_WORKFLOW, GOOD_QODANA) == 0, failures)
    expect(
        "rejects a missing fail threshold",
        run_case(GOOD_WORKFLOW.replace("          args: --fail-threshold 0\n", ""), GOOD_QODANA) != 0,
        failures,
    )
    expect(
        "rejects a non-zero fail threshold",
        run_case(GOOD_WORKFLOW.replace("--fail-threshold 0", "--fail-threshold 19"), GOOD_QODANA) != 0,
        failures,
    )
    expect(
        "rejects PR-only analysis",
        run_case(GOOD_WORKFLOW.replace("pr-mode: false", "pr-mode: true"), GOOD_QODANA) != 0,
        failures,
    )
    expect(
        "rejects continue-on-error",
        run_case(
            GOOD_WORKFLOW.replace(
                "        uses: JetBrains/qodana-action@",
                "        continue-on-error: true\n        uses: JetBrains/qodana-action@",
            ),
            GOOD_QODANA,
        ) != 0,
        failures,
    )
    expect(
        "rejects PR-head classifier authority",
        run_case(
            GOOD_WORKFLOW.replace(
                "github.event.pull_request.base.sha",
                "github.event.pull_request.head.sha",
            ),
            GOOD_QODANA,
        ) != 0,
        failures,
    )
    expect(
        "rejects classifier execution from PR workspace",
        run_case(
            GOOD_WORKFLOW.replace(
                'classifier=".trusted-qodana-scope/.github/workflow-policy/classify_qodana_scope.py"',
                'classifier=".github/workflow-policy/classify_qodana_scope.py"',
            ),
            GOOD_QODANA,
        ) != 0,
        failures,
    )
    expect(
        "rejects Inspect code without scope dependency",
        run_case(GOOD_WORKFLOW.replace("    needs: [ qodanaScope ]\n", ""), GOOD_QODANA) != 0,
        failures,
    )
    expect(
        "rejects an unguarded non-applicable result",
        run_case(
            GOOD_WORKFLOW.replace(
                'test "$QODANA_REASON" = "all-paths-proven-non-qodana"',
                'echo "$QODANA_REASON"',
            ),
            GOOD_QODANA,
        ) != 0,
        failures,
    )
    expect(
        "rejects an incompatible linter line",
        run_case(GOOD_WORKFLOW, GOOD_QODANA.replace("2026.2", "2024.3")) != 0,
        failures,
    )
    expect(
        "rejects a broadened generated-workspace exclusion",
        run_case(GOOD_WORKFLOW, GOOD_QODANA.replace("      - .qodana", "      - src")) != 0,
        failures,
    )
    expect(
        "rejects an added inspection exclusion",
        run_case(
            GOOD_WORKFLOW,
            GOOD_QODANA + "  - name: ConstantConditionIf\n    paths:\n      - src/main\n",
        ) != 0,
        failures,
    )
    expect(
        "rejects an added repository-source path",
        run_case(
            GOOD_WORKFLOW,
            GOOD_QODANA.replace("      - .qodana", "      - .qodana\n      - src/main"),
        ) != 0,
        failures,
    )

    if failures:
        print(f"\n{len(failures)} Qodana gate expectation(s) failed:")
        for failure in failures:
            print(f"  - {failure}")
        return 1

    print("\nAll Qodana gate positive/negative controls held.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
