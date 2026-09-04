# Security Policy

## Scope

This covers two distinct things, because they have different blast radii:

1. **The zMyBatis plugin itself** - SQL rendering/parameter-binding fidelity, DataGrip action isolation, datasource/schema/session identity, and execution safety. See [AGENTS.md](AGENTS.md) sections 2, 3, 5, and 7 for the specific correctness/safety invariants a vulnerability here would violate (for example: `#{...}` being rendered as unescaped `${...}`-style interpolation, or SQL executing against the wrong datasource).
2. **This repository's CI/GitHub Actions configuration** - the trust-boundary rules in `.github/workflow-policy/` and `.github/merge-gate-policy.yml` (immutable action pinning, no privileged job checking out source in a `pull_request` workflow, `persist-credentials: false` on read-only jobs). A bypass of one of these rules, or a gap the automated checks don't catch, is a valid report even though it never touches the plugin's own code.

## Reporting a vulnerability

Do not open a public GitHub issue or pull request for an undisclosed vulnerability.

- If this repository has GitHub Security Advisories enabled, use **Security -> Advisories -> Report a vulnerability** to report privately.
- Otherwise, contact the repository owner ([@luceat-lux-vestra](https://github.com/luceat-lux-vestra)) directly through their GitHub profile rather than a public issue.

Please include:

- whether the report concerns the plugin itself or the repository's CI/workflow configuration;
- a minimal reproduction (a mapper snippet + parameters for a plugin issue; a workflow diff or trigger scenario for a CI issue);
- the impact you believe it has (e.g. "renders `${...}`-equivalent unescaped SQL from a `#{...}` parameter", "a job holding `contents: write` can be reached with attacker-controlled checkout content").

We do not currently offer a bug bounty. We will acknowledge reports and work with you on a disclosure timeline appropriate to the issue's severity.

## What this repository does and does not have

Dependabot version-update PRs are enabled and grouped weekly as a maintenance/noise policy. Do not infer the availability or absence of any dependency, alert, or advanced-security capability from this configuration; a maintainer reviewing an update should not assume an absent security alert means an absent vulnerability.

CI-side mitigations that do exist and are enforced automatically (see `.github/merge-gate-policy.yml` and `AGENTS.md` section 9):

- every third-party GitHub Action is pinned to an immutable commit SHA;
- no job that checks out source in a `pull_request` workflow holds a write-scoped `GITHUB_TOKEN` permission;
- validation jobs disable credential persistence on checkout;
- the workflow static-analysis gate (`actionlint`, `zizmor`, and this repository's own trust-boundary checks) is fail-closed and covered by deterministic negative controls, not just documentation.

`release.yml` (JetBrains Marketplace publication) has known, unresolved static-analysis findings and an incomplete release-version-provenance chain; this is a tracked, deliberate gap (see `AGENTS.md` section 10), not something this policy is claiming to have already closed.

## Supported versions

zMyBatis is distributed only through JetBrains Marketplace; there is no long-term-support branch. Security fixes land on `main` and are released in the next Marketplace publication - please update to the latest published version before reporting an issue that may already be fixed.
