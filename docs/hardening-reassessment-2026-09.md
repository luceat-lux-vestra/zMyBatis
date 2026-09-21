# Hardening Reassessment — 2026-09-20

Owning issue: #178

This pass re-evaluates the completed repository hardening against current external GitHub/OpenSSF guidance and the repository's present API/agent-heavy operating model. It does not discard the existing hardened merge, workflow-security, Qodana, Plugin Verifier, failure-triage, live-drift, or release-provenance controls.

## Findings

### GAP — API/direct issue metadata

The existing policy explicitly documented that only Dependabot labels were automated. Recent API/direct-created work therefore had no type metadata.

The repository now declares one small type taxonomy and reconciles only explicit title protocol. Unknown titles are diagnostic-only and are not guessed from body text.

### STAGED — Dependency Review

Dependabot proposes dependency movement; Dependency Review is a distinct PR admission control over the dependency diff.

It is not yet claimed as a live required context. Promotion requires successful ordinary-PR evidence, live Dependency Graph support, an atomic checked-in + live-ruleset update, and fresh authoritative readback.

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
- any Dependency Review promotion is atomic with the live ruleset;
- backfill is dry-run reviewed before mutation;
- merged-main validation is read back on the exact merge SHA.

UNKNOWN, UNVERIFIED, and INSUFFICIENT EVIDENCE remain FAIL for any claimed control.


## LIVE BLOCKER — GitHub pull_request_target event policy

GitHub's public-repository default Actions event policy is currently evaluating
`pull_request_target` and is scheduled for enforcement on 2026-11-02.

This repository still deliberately uses that trigger on the following audited
trusted-base / metadata-only workflows:

- `.github/workflows/failure-triage.yml`

The workflows must not be migrated to ordinary `pull_request` merely to avoid
the platform policy: doing so would move governance/metadata execution authority
onto PR-controlled workflow definitions. Instead, issue #187 owns one
administrative live prerequisite:

- read the repository Actions policies;
- add an active workflow-path-scoped event policy for only the audited paths;
- allow only `pull_request_target` for those paths;
- read the policy back and retain its id, path condition, enforcement, and event set;
- exercise the workflow on a real PR after activation.

A repository-wide `pull_request_target` allow rule is not accepted.
Any future checkout or execution of PR-controlled code under these workflows
invalidates the allow decision and requires a new security review.
