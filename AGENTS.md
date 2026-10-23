# zMyBatis Engineering Contract

This is the authoritative engineering and review contract for zMyBatis. It describes the **current enforced baseline** separately from **known product gaps** and from the Leap target architecture tracked by Epic #60. Do not turn a target rule into a claim about current behavior without evidence.

For Leap implementation, [docs/product-contract.md](docs/product-contract.md) is the product-policy authority and [docs/leap-architecture.md](docs/leap-architecture.md) is the architecture authority. **Read [the 2026-10-13 Leap execution rebaseline](docs/leap-execution-rebaseline.md) first**: #391 replaced #258's strict JDBC-binding-as-cutover-gate *target policy* without changing #251's API evidence. The PostgreSQL BIGINT materializer/native-console feasibility proof and the bounded #66 replacement chain have since landed; the registered action now routes supported statements through that single Leap authority. Unsupported DBMS/type/source/input/materialization cases still fail closed, and the retained legacy evaluator/action implementation is non-shipping cleanup debt owned by #67.

CI green is necessary evidence, never sufficient approval. Every PASS belongs to one exact final PR HEAD SHA. See [CONTRIBUTING.md](CONTRIBUTING.md) for contribution mechanics, [docs/test-contracts.md](docs/test-contracts.md) for executable product evidence, and [SECURITY.md](SECURITY.md) for vulnerability reporting.

## 1. Current product boundary

zMyBatis is an IntelliJ/DataGrip database plugin. `plugin.xml` depends on `com.intellij.database`.

The current execution path is broadly:

1. the registered zMyBatis action performs bounded context classification and delegates to the Leap admission coordinator;
2. immutable Java/XML source authority and complete supported dependencies are captured from the current editor/project;
3. a provenanced parameter contract is derived and explicit input is validated; zero-input statements bypass the input modal;
4. stock MyBatis prepares `BoundSql`, and one proven materializer either creates an immutable exact SQL-text artifact or returns a typed refusal;
5. the persisted/selected stable datasource UUID + explicit schema target is resolved and revalidated across modal/async boundaries;
6. preview/mandatory approval is presentation-only over the same immutable artifact;
7. `DatabaseToolsConsoleAdapter` executes exactly that artifact through the native Database Tools console and native result/history path.

A SQL-text materializer is a bounded compatibility contract, **not** JDBC/TypeHandler equivalence. Unsupported or unproven source/input/databaseId/dialect/type/placeholder/target cases fail closed before native execution; there is no runtime fallback to the retained legacy evaluator.

Key current ownership areas:

- action/context edge: thin `MyBatisExecuteProxyAction` + bounded `MyBatisContextAnalyzer`;
- source authority: `ActiveEditorSourceSnapshotAdapter`, Java annotation capture, XML mapper discovery/source-graph adapters;
- input authority: core `ParameterContract` / `InputEnvironment` plus `ContractParameterInputDialog` presentation/retention;
- MyBatis preparation/materialization: packaged `:mybatis-engine` plus `LeapExecutionCandidateCoordinator` and proven materializers;
- orchestration/approval: `LeapActionContextAdmissionCoordinator`, `LeapExecutionInvocationCoordinator`, `LeapExecutionDeliveryCoordinator`, `LeapExecutionApprovalAdapter`;
- execution/DataGrip integration: `DatabaseToolsConsoleAdapter` low-level console mechanics and native query dispatch;
- target persistence/resource lifecycle: `ExecutionTargetDescriptorStore`, exact target resolution, migration-only `LegacyV2ConsoleSessionMigrationStore`, and persistence-neutral `ConsoleCacheService`;
- legacy compatibility: `LegacyMyBatisExecuteProxyAction`, `MyBatisEvaluator`, old extractors/dialog/history/bridges remain non-shipping until bounded #67 deletion;
- settings: `ZMyBatisSettings`, configurable UI.

## 2. Mapper, parameter, and SQL fidelity

- `#{...}` and `${...}` are distinct semantics. Never silently convert a bound placeholder into raw interpolation or vice versa.
- The current literal renderer is a zMyBatis product representation; do not describe it as JDBC/TypeHandler-equivalent without dedicated evidence.
- Strings, numbers, booleans, `null`, collections, nested objects, arrays, escaping, and indexed paths must preserve the contracts covered by `docs/test-contracts.md` while the legacy path remains shipping.
- Internal MyBatis variables such as `<bind>` names and `foreach` item/index must not be invented as caller inputs.
- Unsupported or ambiguous source/input/evaluation behavior must not silently become plausible executable SQL. The stronger typed-failure architecture is owned by Leap #60/#64/#67.
- Preview and execution must not silently diverge. Full Database Tools click-through fidelity remains a platform/integration evidence gap documented in `docs/test-contracts.md`.

### Privacy/logging hardened baseline

PR #117 removed the known INFO-level leakage of raw parameter values, extracted/dialog values, and rendered SQL from the shipping action. Current normal logging retains only non-sensitive metadata such as parameter counts and SQL length. `SensitiveLoggingContractTest` guards the known direct-interpolation regression patterns across production Kotlin logging call sites.

This is a bounded source-level regression guard, not semantic taint analysis or proof that every possible future alias/sink is safe. New code must not log raw parameter/input values, remembered values, raw `${}` content, rendered/materialized SQL, credentials, or equivalent sensitive source-derived content. This hardening does **not** validate or preserve the legacy action/evaluator architecture; #67 continues to own the final privacy/diagnostic evidence.

## 3. DataGrip action and IDE boundary

zMyBatis must not replace, wrap, unregister, reorder, or intercept DataGrip built-in Execute/Explain/console actions.

- Platform action IDs remain untouched.
- `MyBatisActionInterceptorActivity` is target-persistence migration/cleanup infrastructure despite its historical name; it does not recreate consoles and is not a global action interceptor.
- `MyBatisExecuteProxyAction.getActionUpdateThread()` is BGT.
- `update()` consumes only the bounded local `MyBatisContextAnalyzer`: supported XML statement, direct Java statement annotation, and provider-annotation contexts are enabled/visible; unrelated or missing context is hidden, and Java annotation classification fails closed while indexes are unavailable. It must not perform source-graph construction, parameter analysis, datasource enumeration, persistence, MyBatis preparation, or console work.
- `actionPerformed()` is a thin adapter over `LeapActionContextAdmissionCoordinator`; it must not recreate source, input, MyBatis, materialization, target, or console semantics and must never fall back to the legacy action.
- Provider annotations remain visible for an explicit typed unsupported result; they are not routed to legacy provider execution.
- UI/console work belongs on the EDT where required; index/source scanning and blocking work stay off the EDT; PSI reads obey IntelliJ read-action requirements.

## 4. IntelliJ / Database API compatibility

The plugin uses `com.intellij.database.*`, including APIs that can move between IDE releases.

- `pluginSinceBuild`, platform type/version, bundled database plugin declarations, and Plugin Verifier configuration form one compatibility contract.
- The required `Verify plugin` context is authoritative for its configured target. The deterministic automated verifier baseline is IntelliJ IDEA Ultimate 2026.2.3; an additional 2026.2 target protects the declared minimum build 262.
- DataGrip remains a product target, but a green verifier against IDEA Ultimate alone is not evidence of every DataGrip runtime path. Database Tools behavior that cannot be credibly covered by unit tests requires explicit platform/manual evidence.
- Raising the minimum IDE build or widening compatibility claims is a product/API decision, not a routine dependency bump.

## 5. Datasource / schema / session identity — hardened baseline

Executing correct SQL against the wrong datasource/schema is a higher-severity failure than refusing to execute.

Hardening #57 established the stable datasource/schema safety baseline, and #260/#262 migrate that authority into the current v3 target-selection model:

- persistence is project-scoped; active state does not use `project.basePath.hashCode()` as a project namespace;
- v3 persists canonical source association separately from target identity;
- restart restoration uses a stable IDE datasource UUID as part of target identity, paired with the exact explicit named schema; under v3 this restores target authority for action-time resolution, not a live console, and datasource display name is presentation-only;
- `Use Default Schema` and datasources without a stable UUID remain in-process only and cannot leave an older restart target behind;
- malformed, interrupted, index-mismatched, orphan, or source-stale persisted state fails closed;
- legacy application-global hash/name records remain deliberately unread because their original ownership cannot be proven safely;
- v2 project-scoped records are migration-only and convert to v3 only when the exact canonical source identity is proven;
- startup performs migration/pruning only and creates no `JdbcConsole`;
- datasource/schema resources are resolved exactly when an explicit action needs a console;
- `REUSE`/`NEW_EACH` are ephemeral resource policies; live console registration/disposal is not v3 target-persistence authority; the project-scoped REUSE cache owns retained live consoles and must dispose superseded entries and all remaining cached consoles when the service is disposed;
- cancellation and shutdown remain fail-closed across target resolution/resource acquisition.

The remaining v2 reader/cleanup is isolated in `LegacyV2ConsoleSessionMigrationStore` and remains compatibility debt owned by #65. `ConsoleCacheService` now owns live resource/selection/shutdown lifecycle only and has no persistence storage surface.

See [docs/session-persistence.md](docs/session-persistence.md) for the current persistence contract and [docs/test-contracts.md](docs/test-contracts.md) for automated versus platform-dependent evidence.

## 6. Lifecycle and execution safety

Only the user's explicit zMyBatis execution action may execute SQL.

Extraction, parameter discovery, evaluation, preview, formatting, settings, diagnostics, and startup target migration must not execute a statement as a side effect.

- No auto-confirm, auto-retry, or auto-reexecute of failed statements.
- Project shutdown must win deterministically over migration/selection/resource-acquisition/execution work.
- Consoles, listeners, callbacks, dialogs, editors, PSI objects, and scheduled work must not retain disposed project state.
- Re-check project/lifecycle availability after asynchronous boundaries. The shipping Leap path carries immutable root/dependency source revisions, exact datasource UUID + explicit schema identity and target-derived databaseId context through input/approval/console boundaries; source or target drift, project/editor shutdown, missing/ambiguous target and disposed console/editor resources refuse before native query invocation. Active unsaved editor content is authoritative; dependent loaded Documents outrank stale disk content where the source adapter contract allows it. XML `<sql>/<include>` dependencies are resolved through the captured source graph rather than legacy unknown-tag stripping. Structural tests do not substitute for real IU/DataGrip disposal evidence.
- Expected unsupported/degraded/user failures should not be promoted to IntelliJ fatal errors. The registered action presents typed Leap refusal categories without including raw SQL/input data; cancellation remains cancellation. At the Database Tools adapter boundary, ordinary handled `Exception` outcomes use typed non-fatal failures, while cancellation and fatal non-`Exception` `Throwable` propagate. The retained `LegacyMyBatisExecuteProxyAction` / `MyBatisEvaluator` safety tests are non-shipping characterization only and remain deletion evidence for #67, not production fallback authority. Project-scoped live-console sentinel registration keeps the same cancellation/fatal severity rule.

## 7. Tests and evidence

The required `Test` context is product evidence, not a coverage percentage. The authoritative current contract map is [docs/test-contracts.md](docs/test-contracts.md).

Hardening #58/#79 removed the old IntelliJ template rename test and debug/reproduction-only evaluator evidence. Do not cite removed template fixtures, empty tests, `println`, or Kotlin/JVM `assert(...)` as product proof.

Baseline evidence for ordinary code changes is selected by the changed contract and normally includes:

- `./gradlew check`;
- `./gradlew buildPlugin`;
- `./gradlew verifyPlugin` when platform/API compatibility is plausibly affected;
- required CI/static-analysis gates;
- an explicit E2E applicability assessment under [docs/e2e-testing.md](docs/e2e-testing.md); when process-level evidence applies, the maintained Starter/Driver scenario must run on the exact final PR HEAD.

Leap evidence must be indexed by the new contract/domain boundary where possible rather than by continued existence of legacy class names. Legacy fixtures remain useful only when they can falsify a target invariant or protect shipping behavior during migration.

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

Hardening is a maintained baseline, not a declaration that the current product architecture is final. Epic #60 owns the replacement product/runtime architecture.

The authoritative target is [docs/leap-architecture.md](docs/leap-architecture.md), with product policy in [docs/product-contract.md](docs/product-contract.md). New Leap implementation must follow those dependency/ownership boundaries rather than adding new semantic responsibility to classes scheduled for deletion.

The single-authority action cutover has replaced the shipping god-action orchestration, but cleanup is intentionally incomplete. Remaining replacement/deletion debt includes:

- non-shipping `LegacyMyBatisExecuteProxyAction` and its old source/input/target orchestration;
- `AnnotationSqlExtractor`, regex/keyword `ParameterExtractor`, old parameter dialog/history paths and compatibility bridges that no longer own shipping semantics;
- global/regex/literal/error-string `MyBatisEvaluator` compatibility behavior;
- migration-only v2 compatibility whose supported upgrade window still needs an explicit removal decision;
- Strict OGNL / Ignore Unknown Tags compatibility settings that no longer authorize shipping execution behavior;
- broader #67 diagnostics/privacy/host-runtime evidence and dead-code deletion.

The physical dependency graph is now the intended DAG: root IntelliJ plugin → `:core` and `:mybatis-engine`; `:mybatis-engine` → `:core` + MyBatis; `:core` remains free of IntelliJ/Database Tools/MyBatis dependencies. The root packages the stock-MyBatis engine used by the shipping Leap authority.

**Development priority after the cutover:** do not reopen a second interpreter or legacy fallback. Expand executable DBMS/type/source coverage only with named, reproducible fidelity evidence; otherwise fail closed. #67 should delete dead legacy code in bounded slices, preserve equivalent negative evidence under the new authority, and complete DataGrip/runtime/diagnostic compatibility proof.

Bounded safety fixes to the current path and explicitly temporary migration bridges are allowed. They must not be used to justify preserving legacy architecture and must have an owner/deletion criterion when they survive beyond one PR.

Current known product gaps include, among others:

- final privacy/diagnostic taxonomy and evidence beyond the maintained source-level sensitive-logging guard (#67);
- deletion of non-shipping legacy action/evaluator/extractor/input/bridge code while retaining equivalent regression evidence (#67);
- expansion of SQL-text materialization beyond the explicitly proven dialect/type envelope (#64/#67);
- DataGrip/runtime integration and disposal evidence beyond the deterministic IDEA Ultimate verifier and maintained Starter/Driver scenarios (#67).

Do not claim these are solved merely because repository hardening is green.

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
