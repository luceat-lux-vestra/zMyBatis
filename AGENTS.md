# zMyBatis Engineering Contract

This is the authoritative engineering and review contract for zMyBatis. It distinguishes the
**current enforced implementation** from the **zMyBatis 1.0 target**. Do not turn either one into a
claim about the other without evidence.

For pre-1.0 work:

- [docs/product-contract.md](docs/product-contract.md) is the 1.0 product-policy authority;
- [docs/mybatis-compatibility-architecture.md](docs/mybatis-compatibility-architecture.md) is the
  target architecture authority under #512;
- [docs/leap-architecture.md](docs/leap-architecture.md) records the current Leap implementation and
  retained safety boundaries;
- [docs/test-contracts.md](docs/test-contracts.md) separates current evidence from the next parity
  obligations.

#251 and #391 remain historical evidence, but #391's bounded SQL-text/materializer target is
superseded by the implemented pre-1.0 architecture. The registered action now executes the exact
approved `PreparedExecutionPlan` through explicit prepared/native backends. Unsupported
application-runtime semantics and unsafe/ambiguous authority still fail closed.

The deleted legacy action/evaluator/extractor/dialog/history architecture must not be reintroduced as
fallback, compatibility routing, or test-only semantic authority.

CI green is necessary evidence, never sufficient approval. Every PASS belongs to one exact final PR
HEAD SHA. See [CONTRIBUTING.md](CONTRIBUTING.md), [docs/test-contracts.md](docs/test-contracts.md),
and [SECURITY.md](SECURITY.md).

## 1. Current product boundary

zMyBatis is an IntelliJ/DataGrip database plugin. `plugin.xml` depends on `com.intellij.database`.

The current registered execution path is broadly:

1. capture one immutable supported Java/XML mapper source graph from the active editor;
2. resolve one exact stable datasource UUID + explicit named schema and derive target database-id context;
3. derive caller-authoritative input requirements and collect a validated `InputEnvironment`;
4. prepare through isolated stock MyBatis and freeze one immutable `PreparedExecutionPlan`;
5. approve that exact plan, then immediately revalidate source/target/lifecycle authority;
6. route explicitly by binding cardinality:
   - ordered bindings > 0 -> `DatabaseSessionPreparedBackend` -> bounded zMyBatis prepared-result presentation;
   - ordered bindings == 0 -> `NativeConsoleSqlTextBackend` -> Database Tools native result/history.

The production contract is stock-MyBatis preparation plus explicit prepared/native execution, not a
SQL literalization envelope. Common stock TypeHandlers own bound execution; zero-binding/exact-text
execution remains native. Prepared/native backends do not fall back to each other, and the deleted
legacy evaluator/materializer is not a routing option.

Key current ownership areas:

- cheap action presentation: `MyBatisExecuteAction` + bounded `MyBatisContextAnalyzer`;
- source admission: `LeapActionContextAdmissionCoordinator` plus Java/XML #62 adapters and source-graph revalidation;
- input: caller-authoritative `ParameterContract`, `ContractParameterInputDialog`, typed `InputEnvironment`, and contract-keyed retention;
- preparation/plan: `:mybatis-engine` `MyBatisPreparationBoundary` plus `PreparedExecutionPlanner`;
- bound execution: `DatabaseSessionPreparedBackend` + `PreparedResultPresentationAdapter`;
- zero-binding/exact-text execution: `NativeConsoleSqlTextBackend` + `DatabaseToolsConsoleAdapter`;
- approval/delivery: `LeapExecutionApprovalAdapter` + `LeapExecutionDeliveryCoordinator`;
- target persistence/resource lifecycle: `ExecutionTargetDescriptorStore`, exact Database Tools resolution, v3 stale-source pruning, and persistence-neutral `ConsoleCacheService`;
- settings: `ZMyBatisSettings`, configurable UI.

The registered action has no retained legacy execution fallback. Removed proxy/evaluator/extractor/dialog/history implementations are not valid owners or characterization authorities for new work.

## 1.1 Pre-1.0 parity recovery — mandatory order

The P1-P4 compatibility architecture is now the registered shipping implementation. P5 validates
packaged parity plus minimal human UX smoke against it; P6 owns deletion of superseded migration islands.

Mandatory work-unit order:

```text
D0  record the target architecture in authoritative docs
  -> D1  stock MyBatis parity oracle
  -> P1  statement-scoped preparation projection
  -> P2  mapper-method/XML-only invocation contract replacement
  -> P3  Database Tools session prepared-backend spike + hard stop gate
  -> P4  unified PreparedExecutionPlan cutover
  -> P5  packaged parity + minimal human UX smoke
  -> P6  delete superseded admission/preflight/materializer islands
```

Rules:

- do not patch #509/#510 independently before D1;
- do not encode current Leap refusal as desired oracle behavior;
- stock MyBatis owns standard dynamic SQL semantics for the 1.0 target;
- do not reintroduce `DynamicOgnlAdmission`, `ForeachProvenanceAdmission`, Boolean/foreach/trim
  syntax islands, whole-document result/parameter metadata refusal, or PostgreSQL BIGINT
  single-binding materialization as registered authority merely because historical tests cover them;
- source/target/lifecycle/privacy/approval protections remain non-negotiable during parity recovery;
- P3 was a real stop gate and passed with maintained Database Tools session/prepared execution,
  cancellation, disposal and target correctness evidence; do not weaken those guarantees in later phases;
- one bounded child work unit at a time; do not pre-create all phase PRs.

The D1 oracle is green stock-MyBatis evidence with no production runtime change. Subsequent phases
must add zMyBatis-vs-oracle parity for the slice they replace.

## 2. Mapper, parameter, and SQL fidelity

- `#{...}` and `${...}` are distinct semantics. Never silently convert a bound placeholder into raw interpolation or vice versa.
- Historical materialized SQL text is a compatibility representation, not JDBC/TypeHandler equivalence and not current bound-execution authority.
- **Current shipping:** stock MyBatis preparation + immutable `PreparedExecutionPlan` + explicit prepared/native backend routing. Unsupported application-runtime semantics and ambiguous source/input/target authority still fail closed.
- Internal MyBatis variables such as `<bind>` names and `foreach` item/index must not be invented as caller inputs.
- Unsupported or ambiguous source/input/evaluation behavior must not silently become plausible executable SQL. The stronger typed-failure architecture is defined by Leap #60/#64; #67 supplied its final quality/readiness evidence.
- Preview and execution must not silently diverge. Full Database Tools click-through fidelity remains a platform/integration evidence gap documented in `docs/test-contracts.md`.

### Privacy/logging hardened baseline

PR #117 removed the known INFO-level leakage of raw parameter values, extracted/dialog values, and rendered SQL from the shipping action. #458 extends the same boundary to source/target identities and platform exception detail. Current normal logging retains only non-sensitive metadata such as stage/type, parameter counts, and SQL length. `SensitiveLoggingContractTest` guards the maintained direct-interpolation regression patterns across production Kotlin logging call sites.

This is a bounded source-level regression guard, not semantic taint analysis or proof that every possible future alias/sink is safe. New code must not log raw parameter/input values, remembered values, raw `${}` content, rendered/materialized SQL, credentials, mapper/source identities, datasource/schema display names, console titles, persisted selection IDs, platform exception messages, or equivalent sensitive source-derived content. Genuine fatal/platform throwables may still be attached to fatal diagnostics and rethrown; ordinary expected failures remain typed without retaining raw detail payload solely for logging. This hardening does **not** validate or preserve the legacy action/evaluator architecture; #67 completed the bounded privacy/diagnostic closure evidence, and future changes must keep this guard current.

## 3. DataGrip action and IDE boundary

zMyBatis must not replace, wrap, unregister, reorder, or intercept DataGrip built-in Execute/Explain/console actions.

- Platform action IDs remain untouched.
- `MyBatisActionInterceptorActivity` performs current v3 stale-source pruning despite its historical name; it does not migrate legacy state, recreate consoles, or act as a global action interceptor.
- `MyBatisExecuteAction.getActionUpdateThread()` is BGT.
- `update()` consumes only the bounded local `MyBatisContextAnalyzer`: supported XML statement, direct Java statement annotation, and provider-annotation contexts are enabled/visible; unrelated or missing context is hidden, and Java annotation classification fails closed while indexes are unavailable. It must not perform SQL extraction, parameter analysis, datasource enumeration, persistence, or console work.
- UI/console work belongs on the EDT where required; PSI reads obey IntelliJ read-action requirements; blocking work must not be moved onto the EDT.

## 4. IntelliJ / Database API compatibility

The plugin uses `com.intellij.database.*`, including APIs that can move between IDE releases.

- `pluginSinceBuild`, platform type/version, bundled database plugin declarations, and Plugin Verifier configuration form one compatibility contract.
- The declared minimum is build **263**. The required `Verify plugin` context independently targets IntelliJ IDEA Ultimate 2026.3 and DataGrip 2026.3. Their independent SDK selectors do not create 2026.2 support. A configured host is not PASS without exact-HEAD evidence; the default internal/override-only API failure levels remain enforced.
- Host evidence remains asymmetric: the **2026.3** IDEA Ultimate Starter/Driver suite owns the full PostgreSQL execution matrix, while the **2026.3** DataGrip suite independently owns packaged plugin/service loading, typed XML no-datasource refusal, and one bounded XML prepared-PostgreSQL route through the Database Tools session backend. These are requirements, not asserted PASS results. DataGrip proof does not imply Java-source parity or native Database Tools result/history parity. Internal and experimental IntelliJ editor-open API usage is disallowed. Shipping execution borrows an existing Database Tools `JdbcConsole` client from the resolved datasource session; if no persistent editor exists, it uses a transient off-screen editor over that console document and releases it after dispatch. Mapper execution must not open or focus a console editor tab.
- Raising the minimum IDE build or widening compatibility claims is a product/API decision, not a routine dependency bump.

## 5. Datasource / schema / session identity — hardened baseline

Executing correct SQL against the wrong datasource/schema is a higher-severity failure than refusing to execute.

Hardening #57 established the stable datasource/schema safety baseline, and #260/#262 migrate that authority into the current v3 target-selection model:

- persistence is project-scoped; active state does not use `project.basePath.hashCode()` as a project namespace;
- v3 persists canonical source association separately from target identity;
- restart restoration uses a stable IDE datasource UUID as part of target identity, paired with the exact explicit named schema; under v3 this restores target authority for action-time resolution, not a live console, and datasource display name is presentation-only;
- the registered Leap action requires a stable datasource UUID + explicit named schema; `Use Default Schema` and datasources without stable identity are not production execution choices;
- malformed, interrupted, index-mismatched, orphan, or source-stale persisted state fails closed;
- legacy application-global hash/name records remain deliberately unread because their original ownership cannot be proven safely;
- development-only v2 project-scoped records were never distributed and are not read or migrated;
- startup only prunes stale current-v3 source associations and creates no `JdbcConsole`;
- datasource/schema resources are resolved exactly when an explicit action needs database execution;
- shipping console selection follows this order: remembered runtime source+target association, unique current-mapper filename match, sole eligible console, explicit chooser when multiple remain, then console creation when none exists or the user explicitly chooses creation;
- mapper filename matching is a dynamic convenience heuristic only; it is never persisted target authority and no concrete mapper name is hardcoded;
- existing Database Tools consoles remain platform-owned and are never disposed by zMyBatis; a console created by zMyBatis is owned only for its resource lifetime and may be reused by later invocations;
- `ConsoleCacheService` stores source+target→console association only in memory and supplies two execution leases: one by source identity and one by the selected console identity. Same-source overlap and cross-source overlap on the same console both fail closed before schema/document mutation; unrelated sources on unrelated consoles remain independent;
- cancellation, terminal execution callbacks, lookup failure, and shutdown release or clear that lease fail-closed.

Development-only v2 persistence was never distributed and has no runtime reader or migration path. Legacy console-cache ownership helpers may remain only until bounded cleanup; they are not shipping acquisition authority.

See [docs/session-persistence.md](docs/session-persistence.md) for the current persistence contract and [docs/test-contracts.md](docs/test-contracts.md) for automated versus platform-dependent evidence.

## 6. Lifecycle and execution safety

Only the user's explicit zMyBatis execution action may execute SQL.

Source capture, input collection, preparation, preview, formatting, settings, diagnostics, and startup v3 pruning must not execute a statement as a side effect.

- No auto-confirm, auto-retry, or auto-reexecute of failed statements.
- Project shutdown must win deterministically over startup pruning/selection/resource-acquisition/execution work.
- Consoles, listeners, callbacks, dialogs, editors, PSI objects, and scheduled work must not retain disposed project state.
- Re-check project/editor/source/target/lifecycle authority after asynchronous and modal boundaries. The source graph is revalidated before delivery; the exact target is re-resolved; the Database Tools adapter rejects disposed console/editor resources after delayed boundaries and immediately before native query invocation. Structural tests do not substitute for real IU/DataGrip disposal evidence.
- Expected unsupported/degraded/user failures are typed outcomes and must not become SQL or IntelliJ fatal errors. Cancellation before native invocation means no query invocation. The action edge may present a redacted stage-level refusal and has no alternate execution authority to retry through. The deleted legacy evaluator/action and its removed characterization tests are not current product evidence. Project-scoped live-console cleanup still preserves cancellation/fatal precedence; #67's completed quality track contains the maintained diagnostic/privacy and lifecycle closure evidence.

## 7. Tests and evidence

The required `Test` context is product evidence, not a coverage percentage. The authoritative
evidence map is [docs/test-contracts.md](docs/test-contracts.md).

During parity recovery, current fail-closed tests prove current behavior only. D1 must establish a
stock-MyBatis oracle before P1/P2 implementation; later phases may merge only when their zMyBatis
results match the already-frozen oracle for the claimed slice.

Hardening #58/#79 removed the old IntelliJ template rename test and debug/reproduction-only evaluator evidence. Do not cite removed template fixtures, empty tests, `println`, or Kotlin/JVM `assert(...)` as product proof.

Baseline evidence for ordinary code changes is selected by the changed contract and normally includes:

- `./gradlew check`;
- `./gradlew buildPlugin`;
- `./gradlew verifyPlugin` when platform/API compatibility is plausibly affected;
- required CI/static-analysis gates;
- an explicit E2E applicability assessment under [docs/e2e-testing.md](docs/e2e-testing.md); when process-level evidence applies, the maintained Starter/Driver scenario must run on the exact final PR HEAD.

Leap evidence must be indexed by the maintained contract/domain boundary rather than by removed legacy class names. Historical legacy evidence may explain past decisions, but current product proof comes from maintained contract tests and packaged/runtime evidence.

Documentation-only CI uses a deliberately narrow syntactic fast path when **every** changed file is one of `README.md`, `CONTRIBUTING.md`, `AGENTS.md`, or a Markdown file under `docs/**`. This is not a semantic classifier and does not prove that a documentation claim is correct. In that case the internal `Build`, `Test`, `Inspect code`, and `Verify plugin` jobs keep their evidence slots but skip product/runtime work; `Lint workflows`, `Dependency submission PR validation`, and `Dependency Review` still execute, and the required `Merge Gate` succeeds only after all eight component jobs report success. E2E and CodeQL are not scheduled for that docs-only change. Any changed file outside the allowlist, or any inability to establish the changed-file set, restores the ordinary full validation path.

Do not delete, ignore, soften, or bypass assertions/checks to obtain green CI. UNKNOWN/UNVERIFIED evidence is not a PASS.

## 7.1 Failure handling before remediation

A failing plugin test, IntelliJ/Database Tools observation, Plugin Verifier result,
evidence gate, workflow check, or other red signal is an observation, not a
remediation instruction. Establish the root cause far enough to justify the
owning layer before changing implementation, tests, evidence, workflow policy,
or the execution environment.

UNKNOWN, UNVERIFIED, and INSUFFICIENT EVIDENCE remain fail-closed where the
unresolved point is material to the proposed remediation or merge judgment.
Do not weaken, delete, bypass, or reconfigure valid evidence merely to obtain a
green run. A deterministic failure should be fixed rather than hidden by
reruns; a suspected transient/environment failure may be rerun only when the
available evidence makes that hypothesis credible.

If remediation changes a premise of exact-HEAD proof, invalidate the affected
evidence and re-run the relevant validation on the new exact final PR HEAD.

## 8. CI / GitHub Actions — hardened baseline

The canonical merge policy is [`.github/merge-gate-policy.yml`](.github/merge-gate-policy.yml).

The live ruleset has exactly one required context:

- `Merge Gate`

`Merge Gate` is the fail-closed aggregate in `.github/workflows/build.yml`. It succeeds only when the internal `Build`, `Test`, `Qodana scope`, `Inspect code`, `Dependency submission PR validation`, `Verify plugin`, `Lint workflows`, and `Dependency Review` jobs all conclude successfully. The live `main protection` ruleset enforces that context strictly together with squash-only linear history, required review-thread resolution, and no bypass actors. `.github/workflows/repository-settings-drift.yml` performs scheduled fail-closed live readback and also runs when its checked-in policy or audit implementation changes on main.

Workflow trust-boundary rules:

- every third-party `uses:` reference is pinned to a full commit SHA;
- every scanned job has explicit effective permissions;
- PR-authored code cannot execute with write-scoped authority unless an exact audited event condition excludes PR execution;
- read-only checkout jobs use `persist-credentials: false`;
- trusted dependency-submission changes are pre-merge validated through a read-only PR adapter that runs the same pinned Gradle action in `generate-and-upload` mode; PR graphs remain `refs/pull/...` evidence and can never replace the protected-main `generate-and-submit` publisher;
- CodeQL `init` / `analyze` pin changes are pre-merge exercised only in the read-only `Lint workflows` lane using the representative Actions language, `build-mode: none`, local SARIF, and `upload: never`; PR runtime output cannot satisfy protected-main CodeQL or release evidence authority, and routine full PR CodeQL remains disabled;
- `Inspect code` is the authoritative Qodana gate;
- the internal `Lint workflows` job in `build.yml` runs repository pin/trust/required-context/live-settings/release-provenance checks plus actionlint and zizmor;
- the checked-in negative controls must continue to fail for deliberately bad fixtures;
- `release.yml` is included in pinning, permission, actionlint, zizmor, and release-provenance review. It is **not** pending or exempt from hardening.

`E2E / Starter / Driver E2E` is maintained process-level evidence from completed issue #130. It remains separately visible rather than an unconditional ruleset context; applicability is decided per change under `docs/e2e-testing.md`, and an applicable scenario must pass on the exact final HEAD. Do not substitute template IDE startup or robot-server smoke for falsifiable production-path evidence.

## 9. Version and release contract — hardened baseline

The active publication contract established by #56 is:

- ordinary PR validation is deterministic and non-publishing, using `0.0.0-dev` unless an explicit release version is supplied;
- publication identity is a protected Git tag matching `vMAJOR.MINOR.PATCH[-PRERELEASE]`; the supported publication line starts at `v1.0.0` and rejects `v0.x` tags;
- the effective JetBrains plugin version is exactly the validated tag with one leading `v` removed;
- `release.yml` runs only from GitHub Release events and checks out the release tag;
- release provenance proves the tag commit is reachable from reviewed `main`;
- build/Plugin Verifier and artifact-version validation happen before signing and Marketplace publication;
- signing and publishing use that same effective version;
- production signing/Marketplace credentials are reachable only through the `jetbrains-marketplace` GitHub Environment;
- a digest-bound pending publication identity is written before Marketplace mutation; ambiguous pending state fails closed, while a completed identity must match the immutable tag/source/signed GitHub Release asset before a rerun becomes a no-op;
- `docs/release-recovery.md` is the authoritative manual recovery procedure for pending/ambiguous Marketplace state; `Unknown` never authorizes a retry;
- ordinary `build.yml` does not create release identity or draft releases;
- live `publication tags` governance protects `refs/tags/v*` from update/deletion with no routine bypass while allowing new tag creation;
- `.github/workflow-policy/check_release_provenance.py` and its negative controls fail closed on release-contract drift.

JetBrains Marketplace `Source Code` and `License` are Marketplace-admin metadata rather than repository-controlled state. The maintainer has confirmed the canonical source URL and Apache-2.0 metadata; repository automation must not pretend it can mutate those fields.

## 10. Current product gaps versus Leap target

> Governance-stable heading: this section now distinguishes the current Leap implementation from the superseding zMyBatis 1.0 target under #512.

Epic #60 remains provenance for the single registered Leap authority, while #512 now defines the
shipping middle path: registered action → authoritative source/caller input → stock MyBatis
preparation → immutable `PreparedExecutionPlan` → exact target → explicit prepared/native backend.
The former legacy execution/evaluator/input architecture and undistributed v2 persistence remain
deleted.

The physical dependency graph remains enforced: root IntelliJ plugin → `:core` and
`:mybatis-engine`; `:mybatis-engine` → `:core` and MyBatis; `:core` remains free of IntelliJ,
Database Tools and MyBatis dependencies.

The completed #67 readiness baseline remains a maintenance contract for:

- typed diagnostics/privacy without sensitive SQL/input leakage;
- independent IDEA Ultimate and DataGrip host evidence;
- responsiveness, cancellation and retained-resource safety;
- source/target/lifecycle correctness;
- exact-head validation discipline.

It does **not** freeze superseded dynamic-admission or PostgreSQL BIGINT materializer restrictions as
the 1.0 product contract.

P1-P4 replacement support is now shipping authority. P5 validates packaged parity plus minimal human UX smoke against
that authority; P6 may delete superseded historical implementation/tests/docs only after P5 evidence
is complete. No old-path fallback is allowed.

Public wording must distinguish current shipping evidence from the pre-1.0 target. Do not advertise
planned parity as already shipped, and do not advertise temporary current restrictions as permanent
1.0 product decisions.

Never introduce hidden binding-count, mapper-kind, setting, source-flavor or failure-path fallback
routing. Any temporary bridge requires an explicit owner and deletion point.

## 11. Review discipline

Review the exact final PR HEAD for:

- functional correctness and regressions;
- mapper/dynamic-SQL/parameter fidelity;
- datasource/schema/session identity and lifecycle;
- IntelliJ/DataGrip API, threading, and disposal boundaries;
- diagnostics/privacy/security behavior;
- compatibility and resource/performance risk;
- abstractions, duplication, complexity, dead code, template residue, and hacks;
- adversarial/negative evidence and remaining platform gaps;
- workflow/release/merge-gate integrity;
- diff scope and documentation consistency.

For Leap architecture work, additionally review dependency direction, whether a legacy component is being preserved by inertia, whether a bridge has a deletion point, and whether an adapter is leaking platform objects into core models.

A PASS applies only to the reviewed HEAD SHA. Any HEAD movement invalidates it. Merge by squash only after fresh HEAD/main readback and exact-head approval. Post-merge workflows are reserved for checks or actions that require merged/default-branch state; ordinary product validation is not repeated merely because main advanced.

## Crash-recoverable agent work

For non-trivial repository work that may span sessions, follow `docs/engineering/agent-recovery.md`.

Current GitHub Issue/branch/commit/PR/check/review/merge state is authoritative for execution recovery. A durable Issue checkpoint is a compact resume hint only. At session start, discover any existing production PR first, fresh-read authoritative state, reconcile the checkpoint, and derive one safe next action before mutating.

After a material external mutation, verify the resulting GitHub postcondition before advancing the checkpoint. If a response or session is lost, never blindly repeat the mutation merely because the checkpoint is behind. Re-read and classify the result as matching, stale-but-reconcilable, retryable-absent, conflicting, or `UNKNOWN`.

Checkpoint updates must not move the production PR HEAD. Any actual HEAD movement invalidates prior exact-HEAD evidence under this repository's normal merge gate. Deliberately stale checkpoints are a test technique only; normal work updates the checkpoint after every verified durable boundary.


### Legacy UX parity during P5

P5 human smoke treats the pre-Leap product workflow as the UX baseline while allowing presentation/UI improvements. Internal architecture may change, but it must not introduce user-visible execution surfaces merely to expose implementation details.

- mapper execution preferentially reuses the remembered/eligible Database Tools console; if none exists, zMyBatis may create one console for that mapper/target, and ambiguous candidates are resolved by an explicit console chooser rather than arbitrary selection;
- newly created console names are derived from the current mapper filename and made collision-safe; no concrete mapper name or `- zMyBatis` branding is hardcoded;
- executing from a mapper must not open or focus another console editor tab; a transient off-screen editor may be used only as an implementation detail when native dispatch needs an `Editor`, and it is created/released per dispatch rather than retained as a hidden editor pool unless measured evidence later justifies a lifecycle-safe cache;
- parameter input may improve labels, validation, accessibility, and structured editing without changing caller authority;
- zero-binding/exact-text execution continues to use native Database Tools result/history surfaces;
- bound prepared execution must not be moved back to unsafe universal SQL literalization merely for UI parity. If maintained public Database Tools APIs cannot surface prepared results natively, treat result presentation as an explicit UX gap to resolve, not as permission to weaken binding semantics.
