# Contributing

zMyBatis is a public Apache-2.0 repository. This document covers contribution mechanics. [AGENTS.md](AGENTS.md) is the engineering/review contract, and [docs/test-contracts.md](docs/test-contracts.md) states what the required `Test` context actually proves.

## Before you start

- Read [AGENTS.md](AGENTS.md) for the current enforced baseline, known product gaps, and exact-HEAD review rules.
- Read [SECURITY.md](SECURITY.md) for vulnerability reporting instead of opening a public PR.
- If a change touches `.github/workflows/`, `.github/workflow-policy/`, `.github/dependabot.yml`, or `.github/merge-gate-policy.yml`, treat the checked-in trust/release/live-settings policy as executable governance, not documentation-only guidance.
- Do not fold product/Leap redesign into a repository-governance change merely because the same audit exposed both.

## Branching and PRs

- Branch from a freshly read `main` and record the base SHA for tracked hardening/architecture work.
- Keep one independently reviewable purpose per PR.
- `main` is squash-only. A prior PASS is invalid after any HEAD movement.
- Treat same-repository and fork PR source as untrusted input for workflow authority decisions.

## Required evidence before review

Run the narrowest evidence capable of falsifying the changed contract and record it in the PR description.

- Product code: `./gradlew check`; `./gradlew buildPlugin`; `./gradlew verifyPlugin` when IntelliJ/Database API compatibility is plausibly affected.
- Workflow/policy changes: `python3 .github/workflow-policy/test_policy.py`, `python3 .github/workflow-policy/test_live_settings.py`, and `python3 .github/workflow-policy/test_release_provenance.py` as applicable. CI `Lint workflows` additionally runs immutable-pin checks, trust-boundary checks, required-context/live-settings/release-provenance checks, actionlint, and zizmor.
- Parsing/parameter/evaluation/rendering changes: add or update a falsifiable product contract test. See `docs/test-contracts.md`; the old template/debug tests were removed and must not be cited as evidence.
- Session/Database Tools lifecycle changes: distinguish pure automated evidence from real IDE/database platform evidence instead of claiming unit tests cover both.

CI green is necessary, not sufficient. UNKNOWN/UNVERIFIED evidence is not a PASS.

## Changing CI/workflow policy

- Never weaken `.github/workflow-policy/check_trust_boundary.py`, `check_pins.py`, required-context/live-settings/release-provenance checks, actionlint, or zizmor to make a finding disappear. Fix the underlying state.
- Deliberately bad fixtures must continue to fail. If they stop failing, the checker regressed.
- If a required job/context is added or renamed, update `.github/merge-gate-policy.yml` in the same PR.
- `release.yml` is part of the current hardened workflow review surface. It is checked for immutable action pins, permissions/credential boundaries, actionlint/zizmor findings, and the dedicated SemVer/tag/artifact publication-provenance contract.
- Do not add a manual UI-test workflow unless the repository also contains real UI tests whose result is meaningful evidence. Template IDE/robot-server startup alone is not a test contract.

## Release changes

The active release policy is documented in AGENTS.md section 9 and enforced by `.github/workflow-policy/check_release_provenance.py`.

- Ordinary PR/main CI uses the deterministic non-publishing development path.
- Publication tags follow protected `vMAJOR.MINOR.PATCH[-PRERELEASE]` identity with migration floor `v27.0.0`.
- The plugin version is the validated tag with one leading `v` removed.
- Release tag ancestry, artifact version, signing, and Marketplace publication must stay bound to that one effective version.
- Do not reintroduce timestamp/CalVer publication identity or ordinary-main draft-release synthesis.

## Dependabot PRs

Gradle and GitHub Actions updates arrive grouped by `.github/dependabot.yml`. A GitHub Actions bump changes a pinned full SHA and its readable version comment together. Platform/IDE compatibility bumps require Plugin Verifier evidence, not only compilation/tests.
