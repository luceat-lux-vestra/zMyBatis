# Contributing

zMyBatis is a private repository; this document covers the mechanics of proposing a change here. For what makes a change correct - MyBatis/SQL fidelity, DataGrip integration boundaries, CI trust-boundary rules, and review discipline - see [AGENTS.md](AGENTS.md), the actual engineering contract. This file exists to route people there and to state the parts of the process AGENTS.md doesn't cover.

## Before you start

- Read [AGENTS.md](AGENTS.md) section relevant to what you're touching (product code, or `.github/`). It is the review checklist, not a formality.
- If your change touches `.github/workflows/`, `.github/dependabot.yml`, or `.github/merge-gate-policy.yml`, read that file's own header comment first - each one explains the trust boundary or policy it implements, and unexplained deviation from it is treated as a defect, not a style choice.
- Security issues (a real vulnerability, not a hardening improvement) go through [SECURITY.md](SECURITY.md), not a public PR.

## Branching and PRs

- Branch from a freshly pulled `main`. Note the base commit SHA if the change is part of a tracked hardening/reconciliation effort - PR descriptions in this repository record exact base and head SHAs for that reason.
- Keep PRs scoped to one coherent change. A PR description should let a reviewer answer "does this diff match its stated scope" without cross-referencing unrelated work.
- `main` is squash-merge only (see `.github/merge-gate-policy.yml`). Write your PR title/description as the commit message you want in history.

## Required evidence before requesting review

Run what's relevant to your change, and say in the PR description what you ran:

- Product code: `./gradlew check`; `./gradlew buildPlugin`; `./gradlew verifyPlugin` if platform/API compatibility is plausibly affected.
- `.github/workflows/` or `.github/workflow-policy/` changes: `python3 .github/workflow-policy/test_policy.py` locally (it exercises `check_pins.py`, `check_trust_boundary.py`, `check_required_contexts.py`, actionlint, and zizmor against both the real workflows and the fixtures under `.github/workflow-policy/fixtures/`), in addition to the CI-run `Lint workflows` check.
- Anything touching parsing, parameter binding, or SQL rendering: add or update a unit test that would fail without your change - see AGENTS.md section 8. `MyPluginTest`/`src/test/testData/rename/` are template leftovers and do not count as coverage.

CI green is necessary, not sufficient. A PASS from review is only valid for the exact HEAD SHA it was given against (AGENTS.md section 11); pushing after approval invalidates it.

## Changing CI/workflow policy specifically

- Never weaken `.github/workflow-policy/check_trust_boundary.py`'s R1/R2 rules, `check_pins.py`'s SHA-pinning requirement, or `workflow-lint.yml`'s actionlint/zizmor invocations to make a finding disappear. Fix the underlying workflow.
- If a check's fixtures under `.github/workflow-policy/fixtures/bad/` ever stop failing, that is a regression in the check, not a fixture to delete.
- If you add or rename a job that should be a required merge-gate context, update `.github/merge-gate-policy.yml`'s `requiredStatusChecks` in the same PR - `check_required_contexts.py` will otherwise fail on the drift.
- `release.yml` (JetBrains Marketplace publication) is intentionally out of scope for the `workflow-lint.yml` checks right now, pending a separate release-provenance PR. Don't fold release.yml hardening into an unrelated change; open it against that track instead.

## Dependabot PRs

Gradle and GitHub Actions updates arrive weekly, grouped by `.github/dependabot.yml`. A GitHub Actions bump changes a pinned SHA and its trailing version comment together - verify both moved, not just the comment. A Gradle/platform bump that touches `platformVersion` or IDE compatibility ranges needs `./gradlew verifyPlugin` to pass, not just `./gradlew check`.
