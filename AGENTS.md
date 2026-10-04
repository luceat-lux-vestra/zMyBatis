# zMyBatis Engineering Contract

This is the authoritative engineering and review contract for zMyBatis. It describes the **current enforced baseline** separately from **known product gaps** and from the Leap target architecture tracked by Epic #60. Do not turn a target rule into a claim about current behavior without evidence.

For Leap implementation, [docs/product-contract.md](docs/product-contract.md) is the target product-policy authority and [docs/leap-architecture.md](docs/leap-architecture.md) is the target architecture authority. The current-class ownership lists below describe the shipping baseline only. They are **not** a reason to extend or preserve legacy action/evaluator/parameter/session architecture when the Leap documents schedule it for replacement.

CI green is necessary evidence, never sufficient approval. Every PASS belongs to one exact final PR HEAD SHA. See [CONTRIBUTING.md](CONTRIBUTING.md) for contribution mechanics, [docs/test-contracts.md](docs/test-contracts.md) for executable product evidence, and [SECURITY.md](SECURITY.md) for vulnerability reporting.

## 1. Current product boundary

zMyBatis is an IntelliJ/DataGrip database plugin. `plugin.xml` depends on `com.intellij.database`.

The current execution path is broadly:

1. extract a mapper statement from supported XML or annotation source;
2. infer user-supplied parameters;
3. evaluate dynamic SQL with MyBatis `XMLScriptBuilder` plus zMyBatis-owned compatibility behavior;
4. render a zMyBatis literal SQL representation;
5. resolve datasource + explicit schema and reuse/create a JDBC console;
6. execute only after the explicit zMyBatis action.

Using MyBatis internally does **not** by itself prove stock MyBatis/JDBC parity. zMyBatis currently owns parameter discovery, OGNL source sanitization, direct unknown-tag compatibility handling, and literal rendering; legacy execution must not install process-global OGNL accessors and qualified nested map/list lookup is delegated to stock MyBatis. Product/fidelity decisions and unsupported/degraded cases are owned by Leap #60. Track #61 completed the product-policy freeze; downstream implementation and evidence remain owned by #62–#67.

Key current ownership areas:

- source/context extraction: `AnnotationSqlExtractor`, `MyBatisContextAnalyzer`;
- parameters: `ParameterExtractor`, `JsonParameterParser`, parameter UI/history;
- dynamic SQL/rendering: `MyBatisEvaluator`, `SqlFormatter`, preview;
- execution/DataGrip integration: `MyBatisExecuteProxyAction` orchestration plus low-level `DatabaseToolsConsoleAdapter` console mechanics;
- target persistence/resource lifecycle: `ExecutionTargetDescriptorStore`, `StoredExecutionTargetBridge`, migration-only `LegacyV2ConsoleSessionMigrationStore`, persistence-neutral `ConsoleCacheService` live-resource lifecycle, and action-time console acquisition;
- settings: `ZMyBatisSettings`, configurable UI.

These are **current-state ownership descriptions**, not target component boundaries. Leap replacement/deletion dispositions are defined by `docs/leap-architecture.md`.

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
- `update()` consumes only the bounded local `MyBatisContextAnalyzer`: supported XML statement, direct Java statement annotation, and provider-annotation contexts are enabled/visible; unrelated or missing context is hidden, and Java annotation classification fails closed while indexes are unavailable. It must not perform SQL extraction, parameter analysis, datasource enumeration, persistence, or console work.
- UI/console work belongs on the EDT where required; PSI reads obey IntelliJ read-action requirements; blocking work must not be moved onto the EDT.

## 4. IntelliJ / Database API compatibility

The plugin uses `com.intellij.database.*`, including APIs that can move between IDE releases.

- `pluginSinceBuild`, platform type/version, bundled database plugin declarations, and Plugin Verifier configuration form one compatibility contract.
- The required `Verify plugin` context is authoritative for its configured target. The deterministic automated verifier baseline is IntelliJ IDEA Ultimate 2026.2.
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
- Re-check project/lifecycle availability after asynchronous boundaries. The Database Tools execution adapter must also reject disposed console/editor resources after delayed editor-open/preview/background boundaries and immediately before native query invocation; structural tests do not substitute for real IU/DataGrip disposal evidence. While legacy PSI extraction still ships, one invocation must fail closed if its active editor source is uncommitted at capture or its document revision changes before execution. For Java direct-annotation SQL, cross-file constant sources actually resolved by the shipping extractor must also carry immutable dependency revision evidence and invalidate execution on uncommitted/deleted/moved/revision-changed or document/VFS authority-changed dependencies. Until #62/#64 source-graph cutover is allowed, the shipping legacy XML action must refuse a real statement `<include>` before extraction/evaluation/target side effects rather than allowing legacy unknown-tag stripping to produce partial SQL. These temporary guards still do not replace #62 source-graph authority, XML `<sql>/<include>` dependency resolution, or final source cutover. While legacy target orchestration still ships, persisted exact datasource/schema identity must likewise be re-resolved through delayed execution boundaries and REUSE cache lookup must match the same `ExecutionTargetId`; default-schema/stable-id-unavailable in-process targets are not promoted to persisted authority by this guard.
- Expected unsupported/degraded/user failures should not be promoted to IntelliJ fatal errors. At the current Database Tools adapter boundary, ordinary handled `Exception` outcomes are WARN-level typed failures, cancellation is rethrown, and non-`Exception` fatal `Throwable` is error-level and rethrown. The still-shipping legacy evaluator/action boundary now also rethrows cancellation and never converts non-`Exception` fatal `Throwable` into legacy error SQL or a normal dialog outcome. Direct legacy `MyBatisEvaluator.evaluate(...)` calls may still return characterized compatibility error-comment/unsupported-literal strings, but production orchestration must use `evaluateForExecution(...)`: ordinary evaluation exceptions and direct unsupported List/Map/other-object literalization become explicit non-executable results and the worker must stop before formatting, preview, console mutation, or native query execution. The remaining legacy action/presentation recovery boundaries follow the same fail-safe severity rule: top-level preparation and datasource-chooser ordinary `Exception` outcomes are non-fatal, cancellation and fatal non-`Exception` `Throwable` are rethrown, and SQL formatting falls back only for ordinary `Exception` without logging exception messages that may contain source-derived SQL. Project-scoped live-console sentinel registration follows the same rule: ordinary registration `Exception` rejects only the ephemeral cache registration, cancellation and fatal non-`Exception` failures propagate, and failed-registration cleanup must not downgrade a primary cancellation/fatal failure. This is bounded safety hardening only: direct evaluator error-string compatibility and the legacy evaluator architecture remain replacement debt under #64/#67, and the complete typed diagnostic model is still owned by Leap #67.

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

Documentation-only CI uses a deliberately narrow syntactic fast path when **every** changed file is one of `README.md`, `CONTRIBUTING.md`, `AGENTS.md`, or a Markdown file under `docs/**`. This is not a semantic classifier and does not prove that a documentation claim is correct. In that case the internal `Build`, `Test`, `Inspect code`, and `Verify plugin` jobs keep their evidence slots but skip product/runtime work; `Lint workflows` and `Dependency Review` still execute, and the required `Merge Gate` succeeds only after all six component jobs report success. E2E and CodeQL are not scheduled for that docs-only change. Any changed file outside the allowlist, or any inability to establish the changed-file set, restores the ordinary full validation path.

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

`Merge Gate` is the fail-closed aggregate in `.github/workflows/build.yml`. It succeeds only when the internal `Build`, `Test`, `Inspect code`, `Verify plugin`, `Lint workflows`, and `Dependency Review` jobs all conclude successfully. The live `main protection` ruleset enforces that context strictly together with squash-only linear history, required review-thread resolution, and no bypass actors. `.github/workflows/repository-settings-drift.yml` performs scheduled fail-closed live readback and also runs when its checked-in policy or audit implementation changes on main.

Workflow trust-boundary rules:

- every third-party `uses:` reference is pinned to a full commit SHA;
- every scanned job has explicit effective permissions;
- PR-authored code cannot execute with write-scoped authority unless an exact audited event condition excludes PR execution;
- read-only checkout jobs use `persist-credentials: false`;
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

In particular, the target deliberately replaces or removes the current:

- `MyBatisExecuteProxyAction` god-object orchestration;
- event-coupled source/context + raw annotation extraction boundary;
- regex/keyword parameter extraction as caller-input authority;
- dialog-owned parameter semantics and raw-string history identity;
- global/regex/literal/error-string `MyBatisEvaluator` behavior;
- migration-only v2 compatibility plus legacy chooser/evaluator/orchestration still embedded in the shipping god action; low-level console mechanics are isolated behind `DatabaseToolsConsoleAdapter`;
- execution-time formatting mutation;
- direct legacy-evaluator compatibility behavior controlled by Strict OGNL / Ignore Unknown Tags switches; shipping execution must fail closed independently of both switches.

The target physical dependency graph is a DAG: root IntelliJ plugin -> `:core`, root IntelliJ plugin -> `:mybatis-engine`, and `:mybatis-engine` -> both `:core` and MyBatis; `:core` remains free of IntelliJ/Database Tools/MyBatis dependencies. The current repository now includes both `:core` and `:mybatis-engine` in `settings.gradle.kts`; the root module consumes both, and CI runs the module checks explicitly. This realizes the physical split defined by #101 while downstream #64 work continues to replace the legacy evaluator/materialization path. The core/platform dependency direction must not invert.

Bounded safety fixes to the current path and explicitly temporary migration bridges are allowed. They must not be used to justify preserving legacy architecture and must have an owner/deletion criterion when they survive beyond one PR.

Current known product gaps include, among others:

- raw parameter/rendered-SQL INFO logging (#67);
- broader IDE orchestration/threading/cancellation implementation beyond the bounded context-correct action presentation gate (policy frozen by #61; implementation owned by #66);
- compatibility-altered OGNL/unknown-tag/literal-rendering behavior that must not be described as stock MyBatis/JDBC parity (policy frozen by #61; implementation owned by #64);
- error/degradation paths that still need typed failure outcomes (#64/#67);
- DataGrip/runtime integration evidence that is not exercised by the deterministic IDEA Ultimate Plugin Verifier target (target-host policy frozen by #61; final evidence owned by #67).

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
