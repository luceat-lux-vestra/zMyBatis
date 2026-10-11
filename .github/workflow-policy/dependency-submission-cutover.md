# Gradle dependency-submission cutover runbook

Status: **STAGED / NOT ACTIVE**. Do **not** enable the new submission job
while GitHub-managed Automatic Dependency Submission is enabled.

## Verified baseline

- Last known GitHub-managed main graph: [run #38140387260](https://github.com/luceat-lux-vestra/zMyBatis/actions/runs/38140387260), SHA `eda1ad314019137becb15002760a163d2c0aa6aa`.
- Exact actual artifact: **1** manifest; **761** resolved dependency nodes;
  **67** direct dependencies. MyBatis 3.5.19 and IntelliJ Gradle Plugin 2.19.0
  present. Normalized graph SHA-256:
  `0c93c7f688f7d01fcbbce5cc8cc0264b66a7ee2d14e599d88be27be396ccc3ef`.
- [Research PR #381](https://github.com/luceat-lux-vestra/zMyBatis/pull/381)
  tested both the x64 cache-disabled and ARM64 shared-read-only modes at the
  same source. Both had **0** missing/extra nodes, relation changes, edge
  changes, or Package URL changes against managed submission.
- ARM64 shared-read-only job: ~**88.7 s**; x64 fully cache-disabled:
  ~**185.4 s** in the matched two-case probe. The ARM64 action restored the
  existing protected-main Gradle dependencies/transforms and wrote **no**
  new cache entry. Both were `generate-and-upload`, NOT submission.

## Deployment guards

`.github/workflows/dependency-submission.yml` runs only on `push: main`
or manual `workflow_dispatch`, and **skips its submission job** unless both
of these conditions hold:

1. It is running on the genuine `luceat-lux-vestra/zMyBatis` protected `main`;
2. The repository Actions variable `ZMYBATIS_ADS_CUTOVER_DONE` is exactly
   `true` (case-sensitive).

The variable must remain **unset/false** until managed ADS is disabled.
Unlike the research job, the activated job uses `contents: write` and
`dependency-graph: generate-and-submit`; it must not execute on pull
requests. It retains `cache-read-only: true`, full dependency resolution,
fail-closed submission, provenance and node-coverage assertions, and one
main-branch concurrency group. No Qodana, Gradle, Dependabot or code-scanning
gates are relaxed.

## Ordered activation — repository administrator

1. Confirm the new workflow exists on `main` and the repository variable
   `ZMYBATIS_ADS_CUTOVER_DONE` is **absent or false**. Confirm recent
   GitHub-managed `Automatic Dependency Submission (Gradle)` is still
   succeeding. Do not touch the Dependency graph and Dependabot alerts toggles.
2. Navigate to **Repository Settings > Advanced Security > Dependency graph >
   Automatic dependency submission** and select **Disabled**. This stops
   GitHub's separate dynamic Gradle-cache producer but **does not** disable the
   dependency graph or Dependabot alerts.
3. Immediately set **Repository Settings > Secrets and variables > Actions >
   Variables**: `ZMYBATIS_ADS_CUTOVER_DONE=true`. This is a repository
   variable, **not** a secret or file committed to the repository. Merely
   changing it does not trigger a new Actions run.
4. In **Actions > Gradle Dependency Submission**, choose **Run workflow** on
   `main`. Require the single job to run (not skipped) and complete
   **SUCCESS**, with `Submitted ...: Dependency results for the repo have
   been successfully updated`, `GRAPH_SUBMITTED_MAIN_SHA=<exact sha>`,
   no cache-save attempt, and at least 650 nodes including the two guarded
   coordinates. Baseline on stable source is 761 resolved / 67 direct.
5. Confirm **Security > Dependency graph** still shows the resolved Gradle
   dependency graph including transitive dependencies, and **Dependabot
   alerts** remains enabled. Review a subsequent `push: main`: a new
   ARM64 read-only submission should succeed; GitHub-managed
   `Automatic Dependency Submission (Gradle)` must not recur.
6. Only after Step 4 succeeds, use an authenticated admin CLI/API to inspect
   **actual** Actions cache inventory and retention quota. Decide whether
   old x64 ADS cache entries and obsolete Build-produced ARM64 caches can
   be removed safely by validated exact key/ref/ID. No blanket cache clear.
   Qodana cache restoration remains tracked in
   [#377](https://github.com/luceat-lux-vestra/zMyBatis/issues/377).

### Rollback

If the new submission fails: **first** change repository variable
`ZMYBATIS_ADS_CUTOVER_DONE=false` to stop further custom submissions,
**then** re-enable managed **Automatic dependency submission** while
retaining Dependency graph and Dependabot alerts. Confirm a fresh managed
snapshot succeeds. Do not leave both publishers enabled and do not
remove either cached dependency baseline before replacement has been verified.

## Scope and non-goals

- This cutover does not reduce the **contents** of Qodana's 4.39GB index or
  Gradle's 1.58GB transforms cache. It eliminates a *separate cache producer*.
- The new workflow's warm Gradle cache is read-only. As configured since
  [#380](https://github.com/luceat-lux-vestra/zMyBatis/pull/380), only an
  explicitly dispatched E2E job on trusted `main` may seed new Gradle state.
- Managed ADS setting and cache inventory are administrator-controlled; a
  GitHub repository content PR **cannot change them**. Do not claim an
  operating cost or retained capacity saving until actual cache inventory
  demonstrates it.
- Documentation: [automatic dependency submission settings](https://docs.github.com/en/code-security/how-tos/secure-your-supply-chain/secure-your-dependencies/submit-dependencies-automatically);
  [Gradle dependency-submission action](https://github.com/gradle/actions/blob/main/docs/dependency-submission.md);
  [GitHub Actions configuration variables](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-variables).
