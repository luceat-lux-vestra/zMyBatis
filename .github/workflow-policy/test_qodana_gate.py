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
jobs:
  inspectCode:
    name: Inspect code
    runs-on: ubuntu-latest
    steps:
      - name: Qodana - Code Inspection
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
  - name: DialogTitleCapitalization
    paths:
      - src/main/kotlin/com/algorist/zMyBatis/SqlPreviewDialog.kt
      - src/main/kotlin/com/algorist/zMyBatis/settings/ZMyBatisConfigurable.kt
  - name: DuplicateArgumentsInSetOfAndMapOfFunctions
    paths:
      - src/main/kotlin/com/algorist/zMyBatis/ParameterExtractor.kt
  - name: KDocUnresolvedReference
    paths:
      - src/main/kotlin/com/algorist/zMyBatis/SqlFormatter.kt
      - src/main/kotlin/com/algorist/zMyBatis/MyBatisEvaluator.kt
  - name: MoveVariableDeclarationIntoWhen
    paths:
      - src/main/kotlin/com/algorist/zMyBatis/MyBatisEvaluator.kt
      - src/main/kotlin/com/algorist/zMyBatis/AnnotationSqlExtractor.kt
  - name: RedundantIf
    paths:
      - src/main/kotlin/com/algorist/zMyBatis/services/ConsoleCacheService.kt
  - name: RegExpUnnecessaryNonCapturingGroup
    paths:
      - src/main/kotlin/com/algorist/zMyBatis/ParameterExtractor.kt
  - name: RemoveExplicitTypeArguments
    paths:
      - core/src/main/kotlin/com/algorist/zMyBatis/core/source/SourceGraph.kt
  - name: RemoveRedundantQualifierName
    paths:
      - build.gradle.kts
  - name: UnusedSymbol
    paths:
      - core/src/main/kotlin/com/algorist/zMyBatis/core/source/SourceGraph.kt
      - src/main/kotlin/com/algorist/zMyBatis/JsonParameterParser.kt
  - name: UsePropertyAccessSyntax
    paths:
      - src/main/kotlin/com/algorist/zMyBatis/MyBatisExecuteProxyAction.kt
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
        "rejects an incompatible linter line",
        run_case(GOOD_WORKFLOW, GOOD_QODANA.replace("2026.2", "2024.3")) != 0,
        failures,
    )
    expect(
        "rejects a broadened source exclusion",
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
        "rejects a changed approved path",
        run_case(
            GOOD_WORKFLOW,
            GOOD_QODANA.replace(
                "src/main/kotlin/com/algorist/zMyBatis/ParameterExtractor.kt",
                "src/main/kotlin/com/algorist/zMyBatis/**",
                1,
            ),
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
