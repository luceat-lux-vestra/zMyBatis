#!/usr/bin/env python3
"""Fail-closed verifier for Qodana SARIF evidence.

zMyBatis currently runs Qodana with --fail-threshold 0, so reusable evidence
must contain a well-formed SARIF report with zero findings. The Qodana action
exit status alone is not sufficient release evidence.
"""
from __future__ import annotations

import argparse
import json
import tempfile
from pathlib import Path


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def check(path: Path) -> dict[str, int | str]:
    require(path.is_file(), "Qodana SARIF is missing")
    require(path.stat().st_size > 0, "Qodana SARIF is empty")
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError("Qodana SARIF is unreadable or malformed") from exc

    require(isinstance(payload, dict), "Qodana SARIF root must be an object")
    require(payload.get("version") == "2.1.0", "Qodana SARIF must use SARIF 2.1.0")
    runs = payload.get("runs")
    require(isinstance(runs, list) and runs, "Qodana SARIF must contain at least one run")

    result_count = 0
    for index, run in enumerate(runs):
        require(isinstance(run, dict), f"Qodana SARIF run {index} must be an object")
        results = run.get("results", [])
        require(isinstance(results, list), f"Qodana SARIF run {index} results must be an array")
        for result in results:
            require(isinstance(result, dict), "Qodana SARIF result must be an object")
        result_count += len(results)

    require(
        result_count == 0,
        f"Qodana SARIF contains {result_count} finding(s); --fail-threshold 0 requires zero",
    )
    return {"sarif_version": "2.1.0", "runs": len(runs), "results": result_count}


def self_test() -> None:
    good = {"version": "2.1.0", "runs": [{"tool": {"driver": {"name": "Qodana"}}, "results": []}]}
    finding = {
        "version": "2.1.0",
        "runs": [{"results": [{"ruleId": "Example", "message": {"text": "problem"}}]}],
    }
    cases = [
        ("good", good, True),
        ("finding", finding, False),
        ("no-runs", {"version": "2.1.0", "runs": []}, False),
        ("wrong-version", {"version": "2.0.0", "runs": [{"results": []}]}, False),
        ("non-array-results", {"version": "2.1.0", "runs": [{"results": {}}]}, False),
    ]
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        for name, payload, should_pass in cases:
            path = root / f"{name}.sarif.json"
            path.write_text(json.dumps(payload), encoding="utf-8")
            passed = True
            try:
                check(path)
            except ValueError:
                passed = False
            if passed != should_pass:
                raise AssertionError(f"self-test case {name} expected pass={should_pass}, got {passed}")

        malformed = root / "malformed.sarif.json"
        malformed.write_text("{", encoding="utf-8")
        try:
            check(malformed)
        except ValueError:
            pass
        else:
            raise AssertionError("malformed SARIF must fail")

        try:
            check(root / "missing.sarif.json")
        except ValueError:
            pass
        else:
            raise AssertionError("missing SARIF must fail")

    print("Qodana SARIF self-test passed.")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sarif-file", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        return 0
    if args.sarif_file is None:
        parser.error("--sarif-file is required unless --self-test is used")
    verdict = check(args.sarif_file)
    print(json.dumps(verdict, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
