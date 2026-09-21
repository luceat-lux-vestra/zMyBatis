<!-- failure-triage:v1:start -->
## Failure remediation

Select exactly one. Required for human-authored PRs.

- [ ] Not remediation for an observed failure
- [ ] Remediation for an observed failure

If this PR is remediation, replace every placeholder. If root cause is still UNKNOWN / UNVERIFIED / INSUFFICIENT EVIDENCE, stop remediation and investigate first.

Observed:
<!-- What failed, where, and on which exact revision/run? -->

Classification:
<!-- Exactly one: implementation defect | test defect | evidence defect | workflow-policy drift | environment failure -->

Basis:
<!-- Why is this responsibility layer proven? Which plausible alternatives were rejected or remain unresolved? -->

Root cause:
<!-- Established cause; UNKNOWN / UNVERIFIED / INSUFFICIENT EVIDENCE / TBD are not remediation states. -->

Remediation:
<!-- Which owning layer changes, and why is this the minimum justified change? -->

Proof:
<!-- What will prove the cause is resolved without weakening tests/evidence/policy? -->
<!-- failure-triage:v1:end -->

## Scope

Describe the one coherent change and owning issue/Track.

## Proof obligations

- [ ] exact candidate HEAD identified
- [ ] required Build / Test / Inspect code / Verify plugin / Lint workflows / failure-triage checks pass
- [ ] dependency changes clear Dependency Review when applicable
- [ ] failure and recovery paths reviewed
- [ ] regression and compatibility impact reviewed
- [ ] adversarial/edge evidence added where the change can fail plausibly
- [ ] docs/contracts match shipped behavior

## Safety

State whether this change affects SQL rendering, parameter binding, datasource/schema/session identity, execution authority, workflow trust boundaries, or release provenance.

## Known gaps

List all remaining UNKNOWN / UNVERIFIED / INSUFFICIENT EVIDENCE. These are merge blockers during strict merge judgment.
