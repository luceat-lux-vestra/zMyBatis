# Hardening Reassessment — 2026-09-20

Owning issue: #178

This pass re-evaluates the completed repository hardening against current external GitHub/OpenSSF guidance and the repository's present API/agent-heavy operating model. It does not discard the existing hardened merge, workflow-security, Qodana, Plugin Verifier, failure-triage, live-drift, or release-provenance controls.

## Findings

### GAP — API/direct issue metadata

The existing policy explicitly documented that only Dependabot labels were automated. Recent API/direct-created work therefore had no type metadata.

The repository now declares one small type taxonomy and reconciles only explicit title protocol. Unknown titles are diagnostic-only and are not guessed from body text.

### PASS — Dependency Review required

Dependabot proposes dependency movement; Dependency Review is a distinct PR admission control over the dependency diff.

PR #179 proved the producer after live Dependency Graph enablement. PR #189 promoted the checked-in merge-gate contract, and authoritative 2026-09-22 readback confirmed live `main protection` ruleset `22024054` requires `Dependency Review` with GitHub Actions integration id `15368` and no bypass actors. The staged promotion is complete; future changes to this context still require atomic checked-in/live reconciliation.

### ADVISORY — CodeQL Actions; Kotlin upstream-blocked

GitHub Actions CodeQL analysis runs on exact pull-request heads, main, and schedule.

The first Java/Kotlin leg established a tooling boundary instead of a green badge: the stable extractor rejected Kotlin 2.4.20 while GitHub's published support documentation lists it. #181 owns re-enablement when the normal stable bundle accepts the real build. The repository will not downgrade Kotlin or use a nightly bundle only to satisfy an advisory scanner.

Qodana Inspect code, Build, Test, Plugin Verifier, Lint workflows, and failure-triage remain authoritative.

### PASS — existing workflow/release hardening remains authoritative

Workflow Lint continues to own immutable action pins, explicit permissions, checkout credential boundaries, actionlint/zizmor, required-context producer validation, live-settings ownership, and release provenance. New workflows are covered by those controls rather than creating a second framework.

## MACHINE-READABLE MANUAL ASSERTIONS — live security features

The reassessment does not treat prose or a green repository-owned workflow as proof of GitHub-hosted security settings. The checked-in policy now declares the exact privileged live assertions that must hold:

- Dependency Graph: enabled;
- Dependabot vulnerability alerts: enabled;
- Dependabot security updates: enabled;
- secret scanning: enabled;
- secret scanning push protection: enabled;
- private vulnerability reporting: enabled.

These assertions are schema-tested fail-closed. They are intentionally not inferred by a low-privilege scheduled audit when GitHub withholds admin-only fields. Final reassessment evidence requires an administrator-authorized readback of the actual repository settings/endpoints; missing or unavailable evidence remains UNVERIFIED.

## Issue metadata boundary

Managed type labels are type:bug, type:feature, type:security, type:docs, type:research, and type:task. The reconciler runs on issue events or explicit backfill, executes trusted default-branch policy, owns only issues:write, disables persisted checkout credentials, and only replaces conflicting managed type labels when an explicit repository title prefix determines the canonical type.

## Exit criteria

- exact final PR HEAD passes all currently required contexts, including failure-triage and Workflow Lint;
- Dependency Review and CodeQL behavior is observed and classified rather than assumed;
- live Dependency Graph/security settings are read back;
- the completed Dependency Review promotion remains synchronized with the live ruleset, and any future required-context change is atomic with live state;
- backfill is dry-run reviewed before mutation;
- merged-main validation is read back on the exact merge SHA.

UNKNOWN, UNVERIFIED, and INSUFFICIENT EVIDENCE remain FAIL for any claimed control.


## RESOLVED — failure declaration trust boundary

The former `failure-triage.yml` trusted-base `pull_request_target` gate has
been removed. The required declaration gate now runs as
`.github/workflows/failure-declaration.yml` on unprivileged `pull_request`
and delegates validation to the immutable central `failure-declaration` action.

No repository PR workflow currently triggers on `pull_request_target`.
Reintroducing that trigger for a required merge-gate producer is rejected by
the checked-in workflow policy.

## Failure-classification rollout proof

The trusted `Failure classification` reporter is loaded from the default branch
through `workflow_run`. The PR that first introduces that reporter cannot prove
the reporter against its own pull-request runs.

Full rollout therefore requires a later PR, with the reporter already present on
`main`, that proves on one exact final HEAD:

- all ordinary required contexts, including `failure-triage`, succeed;
- exactly one sticky `CI Failure Classification` report is created or updated
  for the same HEAD;
- the report reaches `CLEAR` when no tracked workflow is pending or failed;
- the trusted reporter executes no PR code and no downloaded artifact.

`CANDIDATE` and `UNKNOWN` remain fail-closed and never authorize remediation.
