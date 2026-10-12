# Leap target architecture

**2026-10-13 rebaseline:** the target execution/materialization and implementation sequence are revised by [Leap execution rebaseline](leap-execution-rebaseline.md) (#391). This document remains useful for source/input/target/lifecycle boundaries; conflicts with the later dated decision are resolved below. #251 API observations remain historical facts and current shipping behavior is unchanged.

Status: target architecture for Epic #60 / architecture task #101.

Audit baseline: `main` `249d3a5058fee71b858cb7926dbb30864ee15858`.

Current-state note: the physical `:core` / `:mybatis-engine` module split described below has since landed in the repository. This document remains the target architecture authority; downstream Leap work is still replacing legacy runtime ownership and must not treat the presence of the modules alone as completion of #60/#64–#67.

This document defines the architecture zMyBatis is moving **to**. The current production classes are migration inputs and behavioral evidence; they are not preservation constraints. Where this document conflicts with an implementation technique in current `main`, the target architecture wins once the corresponding boundary is implemented and proven.

The product policy is defined by [product-contract.md](product-contract.md). Repository/review discipline remains defined by `AGENTS.md` and `.github/merge-gate-policy.yml`.

## 1. Architecture drivers

The Leap exists because the current implementation mixes too many correctness domains in a few mutable IDE-facing objects.

The target is driven by these invariants:

1. **Correctness over plausibility.** Unknown source, input, evaluation, execution-preparation, or target state blocks execution.
2. **MyBatis is the semantic authority** for behavior claimed as MyBatis-compatible; zMyBatis must not maintain a parallel approximate dynamic-SQL interpreter.
3. **Current editor content is authoritative.** Active unsaved edits cannot be silently replaced by stale committed PSI or disk content.
4. **`#{}` and `${}` are different types of input.** This distinction survives every layer.
5. **Preparation and execution are separate capabilities.** Preparing, formatting, previewing, restoring state, or cancelling cannot execute SQL.
6. **Preview and execution cannot drift.** The immutable prepared/materialized execution authority approved by the user is exactly what the Database Tools adapter consumes; presentation rendering never becomes a different executable statement.
7. **Wrong target is worse than refusal.** Datasource/schema ambiguity fails closed.
8. **Platform objects stay at the edge.** `AnActionEvent`, `Editor`, PSI, `Document`, `JdbcConsole`, database PSI objects, dialogs, and disposables do not leak into core contracts.
9. **Sensitive data is not diagnostics.** Raw inputs, remembered values, credentials, and rendered SQL are not normal log payloads.
10. **Legacy code is deleted when its replacement becomes authoritative.** Compatibility shims require an owner and deletion point.

## 2. Fresh-main structural audit

### `MyBatisExecuteProxyAction`

Current responsibilities include context detection, source extraction, statement/history identity, parameter prompting/history, evaluation dispatch, formatting/preview, datasource/schema selection, console reuse/acquisition orchestration, clipboard, and error UI. Low-level console creation, schema switching, editor acquisition, console-document mutation, script-model construction, and native query invocation are isolated behind `DatabaseToolsConsoleAdapter`.

**Disposition:** replace the orchestration body. The final action is a thin IDE adapter over one application use case.

### `MyBatisContextAnalyzer` and `AnnotationSqlExtractor`

These operate directly on action/event PSI and produce coarse context / raw SQL strings. Java method identity is not a complete signature and current editor/PSI authority is not encapsulated.

**Disposition:** replace with IntelliJ source adapters that emit immutable core source/statement models. No live PSI leaves the adapter.

### `ParameterExtractor`, `ParameterInputDialog`, `ParameterHistoryService`

Lexical heuristics currently become caller-input authority, the Swing dialog owns parsing semantics, and raw remembered input is keyed by an insufficient string identity.

**Disposition:** replace with a provenanced parameter contract, independent input codecs, a presentation adapter, and canonical-identity-based opt-in retention. `ParameterExtractor` is deleted as semantic authority.

### `MyBatisEvaluator`

The current object still mixes MyBatis `XMLScriptBuilder`, regex source/OGNL rewriting, settings, literal rendering, and SQL-shaped direct-call compatibility. The previous process-global OGNL accessor mutation and custom nested-map fallback lookup have been removed from the shipping legacy evaluator; qualified map/list navigation now uses stock MyBatis OGNL.

**Disposition:** replace completely. No global OGNL mutation, no authoritative unknown-tag stripping, no error-as-SQL, no evaluator-owned guessed literalization.

Until that replacement is permitted by #258, the legacy shipping adapter has bounded refusal guards: evaluator exceptions and direct unsupported List/Map/other-object literalization do not become execution candidates. Direct `evaluate(...)` compatibility strings remain characterization only; these guards do not turn the legacy scalar literalizer into the target materialization architecture.

### `SqlFormatter`

Formatting currently feeds the string that is later executed.

**Disposition:** retain only as a presentation adapter if useful. Formatting may produce display text but never changes the immutable execution artifact.

### Target persistence and `ConsoleCacheService`

Hardening #57 established strong target-identity rules. #260 introduced independent project-scoped v3 source -> target descriptor persistence, and #262 migrates valid v2 records into that authority without recreating consoles at startup. Persisted v3 identity is stable datasource UUID + exact explicit schema, associated with canonical source identity; datasource display name remains presentation-only.

The shipping legacy action now resolves a stored v3 target exactly on a live-console cache miss and acquires a console only for that explicit invocation. `REUSE` and `NEW_EACH` are resource policies over the same persisted target authority. Closing an ephemeral console no longer deletes v3 target identity. The old v2 reader/cleanup path is isolated in `LegacyV2ConsoleSessionMigrationStore`; `ConsoleCacheService` is persistence-neutral and owns only live-resource/selection/shutdown lifecycle.

**Disposition:** continue isolating Database Tools console mechanics and delete `LegacyV2ConsoleSessionMigrationStore` when no supported upgrade path needs v2 compatibility. The live console lifecycle no longer owns persistence. This Phase-4 progress does not authorize Phase-5 Leap execution cutover; #258 still blocks that cutover until the bound-execution contract is satisfied.

### Settings

The shipping legacy action now refuses ordinary evaluator failures before preview/execution regardless of `strictOgnlMode`, and shipping evaluation explicitly disables `ignoreUnknownTags`; both switches remain only direct legacy-evaluator compatibility. `rememberLastInputs` defaults on.

**Disposition:** safety is not configurable. Remove or repurpose those compatibility switches; remembered inputs default off until the new #63 retention contract is complete.

## 3. Physical dependency boundaries

The target Gradle layout is deliberately split so architecture is enforced by compilation rather than convention alone.

```text
root IntelliJ plugin module
  -> :core
  -> :mybatis-engine

:mybatis-engine
  -> :core
  -> org.mybatis:mybatis

:core
  -> Kotlin/JDK only
```

This is a dependency DAG, not a linear root -> engine -> core chain. Root IntelliJ adapters consume core contracts directly, while `:mybatis-engine` depends on `:core` and MyBatis. The current repository includes both `:core` and `:mybatis-engine` in `settings.gradle.kts`, and CI runs both module checks. The root currently depends on `:core` only; the engine is a standalone preparation module and is absent from the packaged plugin. The module boundary is present, but the target root-to-engine edge and production consumer cutover remain future work under #258. #64 remains open because the semantic preparation/execution migration and legacy evaluator replacement are not complete merely because the module exists. The platform/core dependency direction does not invert.

The root module remains the IntelliJ plugin module so existing `buildPlugin`, verifier, signing, publishing, and CI entry points do not need a repository-wide workflow rewrite merely to establish architecture.

### `:core`

Contains framework-independent domain/application contracts:

- source snapshots and canonical statement identity;
- source dependency graph model;
- parameter/input provenance model;
- preparation/materialization models and typed failures;
- execution target descriptors;
- confirmation/safety policy inputs;
- use-case ports and orchestration that do not require IntelliJ or Database Tools.

`:core` must not depend on IntelliJ, Database Tools, Swing, MyBatis, Gson, or plugin settings services.

### `:mybatis-engine`

Contains the MyBatis semantic adapter:

- isolated `Configuration` ownership;
- mapper/script preparation through MyBatis APIs;
- `BoundSql` / ordered `ParameterMapping` capture;
- additional-parameter resolution;
- conversion into core `PreparedExecution` / typed preparation failures.

It must not depend on IntelliJ/Database Tools/UI.

### root plugin module

Contains adapters only:

- IntelliJ editor/Document/PSI/VFS/index source capture;
- XML/Java source discovery adapters;
- parameter/confirmation/target UI;
- settings persistence and migration;
- Database Tools target resolution and console execution;
- notifications/dialogs/clipboard/presentation formatting;
- the thin `AnAction` entry point.

If Gradle/Plugin packaging evidence later shows a smaller physical split is safer, #101 may adjust the exact module mechanics, but the dependency direction and core/platform separation are non-negotiable.

## 4. Core domain model

Exact Kotlin names may evolve during #101 review, but these semantic boundaries are fixed.

### Source

```text
SourceFileId
SourceRevision
SourceSnapshot(fileId, revision, content)
SourceRange
```

A snapshot is immutable. `SourceRevision` records enough adapter-specific identity (for example document modification stamp / VFS identity) to detect that source changed after preparation.

### Canonical statement identity

```text
StatementId
  XmlStatementId(sourceFileId, namespace, statementId)
  JavaStatementId(sourceFileId, qualifiedMapperType, methodSignature)

MethodSignature(name, ordered parameter type identities)
StatementKind(SELECT, INSERT, UPDATE, DELETE)
```

Display paths and simple class/method names are presentation metadata, not identity.

For Java, overloaded methods cannot collide. For XML, duplicate/ambiguous namespace+id resolution blocks.

### Source graph

```text
StatementSourceGraph(
  rootStatement,
  sourceSnapshots,
  dependencies,
  metadata
)
```

The graph represents the complete source required for supported preparation. XML fragment/include edges are explicit. Missing, ambiguous, and cyclic dependencies become typed source failures. `databaseId` variants are preserved as target-dependent MyBatis semantics rather than classified as generic unsupported source; unresolved effective database-id authority blocks only the statement whose correctness depends on it. Custom language-driver and runtime-only dependencies remain separately unsupported unless promoted by product policy.

### Parameter contract

```text
ParameterContract(requirements, internalBindings, unknowns)
InputRequirement(id, kind, expectedShape, provenance, requiredness)
InputKind = BOUND | RAW_INTERPOLATION
InputEnvironment(values)
InputProvenance(...)
```

MyBatis internal/additional variables are modeled separately from caller values. A lexical token is evidence, not automatically a caller input.

### Prepared execution

```text
PreparedExecution(
  statementId,
  statementKind,
  sourceRevisions,
  sqlWithPlaceholders,
  orderedBindings,
  rawInterpolationProvenance,
  preparationMetadata
)
```

`PreparedExecution` is not directly executable by the IDE adapter. It is the immutable result of source+input+MyBatis preparation.

Each ordered binding records enough metadata to let the execution adapter preserve and supply it safely without guessing, including property provenance and relevant Java/JDBC/type-handler identity where available.

### Execution artifact boundary

Stock MyBatis produces structured `PreparedExecution` (SQL-with-placeholders and verified ordered bindings, additional variables, provenance). `PreparedExecution` is not executable SQL; a separate **proof-bounded dialect/type materializer** creates one immutable `ExecutableSqlArtifact` (exact executable SQL text, verified placeholder mapping, statement/source/target/approval identity, safety and fingerprint). This replaces the *target-policy* assumption that Leap must always execute via true ordered JDBC binding.

The verified SQL-text artifact is submitted through the existing maintained native Database Tools console path, preserving its result/history UI. It is a compatibility contract, **not JDBC/TypeHandler equivalence**. An unsupported DBMS/type/placeholder situation fails before execution; datasource selection itself remains DBMS-neutral.

#251 still proves no demonstrated maintained 2026.2 API bridge for true JDBC binding *and* native console results/history. #258's strict no-fallback decision is a historical state for that earlier goal. #391 explicitly changes the product target after documentation merge; it does not magically implement the materializer. **Current** `MaintainedExecutionMaterializer` continues to return `BOUND_EXECUTION_REQUIRED` for nonzero bindings. A single full new execution path and runtime evidence are prerequisites for action cutover, with no old/new silent split.

Do not use regular-expression question-mark substitution, guessed null, unverified per-DB literalizers or undocumented integration. For mandatory envelope and proof cases see [Leap execution rebaseline](leap-execution-rebaseline.md).

### Execution target

```text
ExecutionTargetId(projectScope, stableDataSourceId, explicitSchemaOrSearchPath)
ExecutionTargetDescriptor(id, diagnosticDisplayName, version)
```

DBMS family is not part of target validity. The datasource selected in Database Tools already owns the driver, connection/session, vendor execution semantics, and native result handling.

No `JdbcConsole`, datasource PSI object, schema PSI object, editor, or `Document` is persisted in this model.

## 5. Source authority and dependency resolution

### Active editor authority

For the invoked file, the active `Document` content is authoritative even when unsaved to disk.

The IntelliJ adapter must:

1. capture project/editor/document/caret immediately;
2. create or synchronize the PSI/model view required for semantic source analysis from that Document;
3. emit immutable source data and revision identifiers;
4. release live editor/PSI references before entering core preparation.

A PSI commit is not a disk save. Leap never requires saving user files as a side effect of execution preparation.

### Dependent files

When a supported XML statement references fragments:

- if a dependent file has a live IntelliJ Document, capture that Document rather than stale disk content;
- otherwise capture VFS/file content with stable file identity/revision evidence;
- dependency resolution runs under bounded project/index access and emits immutable snapshots;
- no lazy PSI object is retained into the engine.

### XML strategy

Do not implement a second MyBatis include/dynamic-SQL engine.

The source adapter discovers the relevant mapper documents/dependencies and supplies their captured content to `:mybatis-engine`. The engine builds an isolated MyBatis `Configuration` and uses MyBatis mapper parsing where technically possible so MyBatis itself resolves fragment/include and dynamic semantics.

Cross-namespace dependencies are loaded into the same isolated configuration in deterministic order. Missing/ambiguous/cyclic resolution is surfaced as typed failure; zMyBatis never substitutes truncated statement text.

Leap v1 preserves standard MyBatis `databaseId` variants and `_databaseId` semantics. When correctness depends on them, the selected Database Tools target must provide a proven effective database id before final variant selection/preparation. Custom `DatabaseIdProvider` mappings that cannot be reconstructed from available project/configuration evidence fail narrowly for that dependent statement. Custom language-driver, provider, and runtime-only extension behavior remain separate unsupported capabilities.

#252 implements the first maintained authority slice without changing target validity: `DbDataSource.dbms` is consulted only after exact datasource/schema resolution, with stock MyBatis `DB_VENDOR` no-properties product ids PostgreSQL → `PostgreSQL` and Oracle → `Oracle`. XML/Java variants retain their declared ids, exact target-id variants take precedence over the default, and a default is used only when non-matching ids are the documented stock product ids. Other aliases are not guessed because they may represent a project-specific `DatabaseIdProvider` mapping. The core authority validator accepts an explicit set of proven ids so a later statically reconstructed custom provider mapping can be represented without turning DBMS families into an execution allowlist. The resolved id is set on the isolated MyBatis `Configuration` before mapper/script preparation, which also makes the same value available as MyBatis' reserved `_databaseId` binding; callers still cannot supply or override `_databaseId`. Target-aware XML/Java selection artifacts retain the database-id authority used for variant choice, and preparation rejects any conflicting database-id context instead of allowing selection/preparation drift.

### Java direct annotations

The Java adapter captures:

- qualified mapper type;
- complete method signature;
- statement annotation kind;
- ordered annotation SQL/script source;
- mapper parameter metadata / `@Param` evidence available from source;
- source revision.

It emits the same core statement/input preparation model as XML. It does not need to preserve `AnnotationSqlExtractor` as a separate abstraction.

Kotlin direct annotations and provider annotations are explicit Leap v1 unsupported outcomes.

## 6. Parameter/input architecture

Parameter discovery is a contract-construction problem, not a regex search problem.

Evidence sources may include:

- Java method parameter metadata and `@Param`;
- captured XML/annotation placeholders;
- OGNL expressions parsed/identified as part of supported source semantics;
- foreach/bind scope definitions;
- MyBatis naming rules that are actually provable from maintained source metadata.

The contract distinguishes:

- explicit caller values;
- generated aliases whose provenance is proven;
- single-object / map / collection environment shapes;
- MyBatis internal variables;
- foreach/bind additional variables;
- raw `${}` text;
- unknown/ambiguous requirements.

The UI receives a `ParameterContract`; it does not discover parameters itself.

Input codecs are pure code. JSON decoding preserves integer/arbitrary-precision decimal fidelity where possible; convenience conversion to `Double` is not the semantic default.

Remembered inputs are opt-in and keyed by canonical `StatementId`. Until the retention/sensitive-data contract is implemented, the target default is OFF. Example/scaffold text from #69 is presentation-only and never counted as an entered value without explicit user action.

## 7. Isolated MyBatis preparation

Every preparation has an explicitly owned MyBatis configuration scope. No call mutates process-global OGNL accessors or other application-global MyBatis state.

For XML, prefer MyBatis mapper parsing (`XMLMapperBuilder`/mapped statement APIs or an equivalently faithful supported API path) over hand-extracting a statement and approximating include semantics.

For Java direct annotations, use MyBatis script/language-driver APIs suitable for captured annotation SQL without pretending a compiled application mapper/runtime configuration exists.

The engine returns either:

- a valid `PreparedExecution`; or
- a typed `PreparationFailure`.

It never returns diagnostic comments as SQL.

`BoundSql.sql`, ordered mappings, additional parameters, and relevant binding metadata are captured before leaving the engine. Raw `${}` provenance remains explicit from the source/input contract even though MyBatis substitution affects the resulting SQL text.

## 8. Execution boundary

The user's configured Database Tools datasource/session, console and native result/history experience remain the execution destination. Leap does **not** require a new true-JDBC-binding adapter to replace its architecture: the selected **SQL-text compatibility** target preserves existing user-visible execution semantics, with correctness checked at an explicit materialization boundary.

1. MyBatis captures SQL with placeholder mapping and actual resolved values.
2. A dedicated materializer proves unambiguous bind locations plus DBMS/type/codec literal semantics. It returns an immutable exact executable SQL artifact or a typed unsupported/error result. A zero-binding case is trivially materializable without binding conversion.
3. Confirmation previews the same artifact, and source/target/cancellation are rechecked before dispatch.
4. The existing Database Tools console executes the exact text and provides native results/history. zMyBatis does not invent result rendering or a second parameter prompt.

Target selection must remain DBMS-neutral, but **literal rendering is not**; a datasource may be valid while statement materialization for its family/type is unsupported. #251's NO-GO applies only to the previous stricter native JDBC-binding requirement; the underlying evidence is preserved. #258 historically refused any fallback under that goal; the new policy is an explicit rebaseline, not an undocumented exception.

**Implementation stop:** current runtime materializer returns `BOUND_EXECUTION_REQUIRED` on nonzero mappings and the legacy action stays authoritative. No bypass/cutover until dialect/type/placeholder and actual console E2E proof pass. Do not keep adding standalone admission grammars while this boundary is unproven.

## 9. Target/session and Database Tools adapter

Persist only versioned `ExecutionTargetDescriptor`/session descriptor data plus migration metadata; descriptor lifecycle is independent of live-console registration/disposal.

Startup behavior:

- load and validate descriptor syntax;
- optionally prune obviously stale records when safe;
- do not create a `JdbcConsole` merely because a descriptor exists;
- never execute SQL.

Execution behavior:

1. resolve the persisted/current descriptor to exactly one live datasource and schema/search path;
2. fail on missing or ambiguity;
3. re-check target validity after user/modality/background boundaries;
4. acquire/reuse a console as an ephemeral resource according to policy;
5. apply schema/search path and verify success;
6. submit the single immutable, proved SQL-text artifact for both zero- and supported nonzero-binding statements; until the new materializer exists, current nonzero Leap preparations still fail with `BOUND_EXECUTION_REQUIRED` and no production cutover is allowed;
7. invoke the native Database Tools execution path without introducing a zMyBatis DBMS allowlist;
8. preserve/restore console document/editor state according to the adapter contract.

`REUSE` and `NEW_EACH` are resource policies, not target identities. In-memory reuse may be removed entirely if it cannot be made simpler and safer than creating an execution console on demand.

## 10. Application orchestration

The primary application use case is conceptually:

```text
ExecuteMapperStatement
  capture/resolve statement
  -> build parameter contract
  -> collect explicit input
  -> prepare with MyBatis
  -> resolve target
  -> build the DB-neutral execution request from PreparedExecution
  -> revalidate source + target
  -> apply safety confirmation
  -> execute through the Database Tools adapter
  -> present outcome
```

Preparation-only sub-use-cases may be exposed for preview/testing, but they cannot own an execution port.

The final `AnAction`:

- extracts an invocation request from IntelliJ data keys;
- delegates to the application coordinator;
- renders UI outcomes;
- contains no mapper parsing, MyBatis semantics, persistence, datasource identity, literalization, or console implementation.

`AnActionEvent` never crosses the adapter boundary.

## 11. Threading, cancellation, and lifecycle

Thread ownership is explicit per boundary.

### Action update

- BGT-compatible, cheap, bounded, side-effect free;
- only enough source/context evidence to decide visibility/enabled state;
- no source-graph construction, MyBatis preparation, datasource enumeration, or console creation.

The shipping action now implements this narrow presentation boundary with local `MyBatisContextAnalyzer` evidence: XML statement, direct Java statement annotation, and provider-annotation contexts are visible/enabled; unrelated/missing contexts are hidden and Java annotation classification fails closed while indexes are unavailable. This is presentation gating only, not the Phase-5 orchestration cutover.

### Invocation capture / UI

- capture editor/document/caret and display dialogs/popups on the EDT as required by IntelliJ;
- commit/synchronize only the specific document/model required for authoritative source analysis; do not save user files.

### Source/index work

- use IntelliJ read actions / non-blocking read actions as required;
- produce immutable snapshots before returning to core;
- cancellation invalidates the current invocation.

### Preparation / execution preparation

- background/cancellable CPU work;
- no Swing/IntelliJ project objects in `:core` or `:mybatis-engine` work items.

### Target/console execution

- adapter owns Database Tools threading requirements;
- revalidate project, datasource, schema, console, and source revision immediately before irreversible execution;
- cancellation before query invocation means no query invocation;
- stale callbacks cannot reuse a prior invocation's target/artifact.

### Disposal

Project-scoped coordinators/resources implement explicit disposal. No application-global active-selection guard or evaluator mutable state is allowed to preserve correctness.

## 12. Failure model

Expected product failures are values, not fatal IDE errors.

Representative categories:

```text
SourceFailure
  UnsupportedSource
  AmbiguousStatement
  StaleSource
  MissingDependency
  AmbiguousDependency
  DependencyCycle
  UnsupportedLanguageDriver
  UnsupportedProvider

InputFailure
  UnknownRequirement
  AmbiguousRequirement
  MissingValue
  InvalidValue
  UnsupportedInputShape

PreparationFailure
  MyBatisParseFailure
  OgnlFailure
  BindingResolutionFailure
  DatabaseIdAuthorityUnavailable
  UnsupportedSemantic
  PreparationInvariantFailure

MaterializationFailure
  BoundExecutionRequired
  RawInterpolationPolicyRequired

TargetFailure
  MissingDatasource
  AmbiguousDatasource
  MissingSchema
  AmbiguousSchema
  StaleTarget
  SchemaSwitchFailure

ExecutionFailure
  Cancelled
  ConsoleUnavailable
  DatabaseRejected
  PlatformCompatibilityFailure
```

Only genuine internal invariants use fatal/error-level diagnostic reporting. User/source/database/unsupported outcomes are presented normally with redacted context.

## 13. Security and privacy

- never log raw input values or final SQL at normal levels;
- never persist raw `${}` values by default;
- remembered inputs are opt-in and project/canonical-statement scoped;
- no credentials are copied into core models;
- source/error messages shown to users are bounded and do not dump full mapper content by default;
- unknown XML/OGNL is parsed as untrusted project source and must not gain additional host capabilities through custom global accessors;
- confirmation is a UX safety boundary, not authorization.

A separate immediate safety patch may remove existing INFO leakage before full Leap cutover; doing so does not freeze the legacy architecture.

## 14. Migration and deletion sequence (revised 2026-10-13)

Original Phase-0 through Phase-4 experiments produced useful foundations: core/engine modules, canonical identity and input contracts, source graph capture, target v3 descriptors, console lifecycle isolation and multiple MyBatis/OGNL preparation islands. Most do **not** run in the packaged product; the root uses `:core` but does not depend on `:mybatis-engine`. Current production still calls the legacy evaluator, input extractor and dialog.

### Phase R0 — frozen baseline and product-policy reset (#391)

- Preserve legacy execution and the proven safety fixes. Freeze new standalone Boolean/foreach/OGNL grammar-admission expansion.
- Rebaseline the shipping contract to verified SQL-text compatibility rather than mandatory true JDBC binding. Keep #251 API facts and #258 historical context.

### Phase R1 — first complete execution proof

- Select a named DBMS, scalar type and a real SELECT fixture with `#{id}` and a stock-OGNL `<if>`; capture one complete current-editor mapper source and explicit inputs.
- Use stock MyBatis to produce `BoundSql`; create a testable strict SQL-text materializer with lexical placeholder proof and type/dialect support. Reuse the existing Database Tools native console adapter and target identity.
- Demonstrate real result/history, matching preview/executed SQL, safety refusal for literals/operators/comments and unsupported values, cancellation and stale source/target. No production routing split.
- **Stop if any core safety/API/IDE proof fails.** Do not create another independent syntax-admission feature instead.

### Phase R2 — supported product parity and simplification

- Carry over actual shipping Mapper/XML/Java, `${}` confirmation, DML, UI/history, target/restart, source and lifecycle behavior using evidence rather than preserving every old class.
- Replace duplicated source/provenance/Boolean grammar validators with the smallest correct source authority + MyBatis boundary. Keep OGNL runtime isolation only where global-state hostile tests justify it.
- Extend dialect/type support only with explicitly named and falsifiable evidence; a valid but unsupported target/type fails closed.

### Phase R3 — single action cutover and cleanup

- Only after R1/R2 pass, connect the new orchestration to the product action as one complete execution authority. No partial Leap-for-zero/legacy-for-nonzero split.
- Delete old `MyBatisEvaluator`, `ParameterExtractor`, legacy dialog/history and temporary bridges as soon as their consumer is gone. Remove orphaned new admission implementations and their implementation-dependent tests only after equivalent regression/negative coverage exists.
- Keep persisted v2 migration reader until a separately documented supported-upgrade window permits deletion.

### Phase R4 — release/compatibility evidence

- #67 confirms real IDEA Ultimate/DataGrip target UX, unsupported/degraded cases, performance and clean resource disposal; release documentation/Marketplace must not claim true JDBC parameter binding or unsupported dialect fidelity.

See [the dated architecture reset](leap-execution-rebaseline.md) for the fidelity envelope and GO/NO-GO gates. Temporary bridges need an owner and deletion phase; do not maintain two production execution authorities.

## 15. Evidence architecture

### Pure JVM evidence

`:core` tests cover:

- canonical identity/collision resistance;
- source graph and dependency outcomes using captured source fixtures;
- parameter contract/provenance;
- safety policy;
- typed failure transitions;
- zero-binding materialized-artifact identity plus structured bound-execution refusal/handoff rules independent of IDE.

`:mybatis-engine` tests cover:

- isolated configuration behavior;
- full supported dynamic-tag semantics;
- `<sql>/<include>` including nested/cross-namespace and negative cases;
- ordered mappings/additional parameters;
- `${}`/`#{}` separation;
- OGNL failure and unsupported extension cases;
- no global state leakage between preparations.

### IntelliJ adapter evidence

Dedicated platform tests cover:

- active unsaved Document authority;
- PSI synchronization without disk save;
- Java qualified type + complete overload signature;
- rename/move/delete/duplicate source behavior;
- action visibility/update-thread/cancellation/disposal;
- no live platform object escaping source capture.

### Database Tools evidence

Automated tests cover what the API can credibly exercise. Plugin Verifier and targeted runtime/manual evidence cover:

- IDEA Ultimate and DataGrip separately;
- datasource/schema exact resolution;
- console acquisition/schema switch/document handling;
- native query/result path;
- stale/disposed resource behavior.

Do not fake platform evidence with mocks that cannot falsify the relevant behavior.

### Merge gate

Every PR remains exact-HEAD proof-obligation gated. Any HEAD move invalidates approval. Post-merge workflows are limited to evidence or actions that inherently require merged/default-branch state; the ordinary product-validation suite is not repeated after merge.

## 16. Track ownership after reset

- #61 — target product/capability/safety decisions; closes after #101 consistency, not after all implementation.
- #62 — source authority, dependency graph, canonical statement/method identity.
- #63 — input provenance, codecs, UI contract, remembered-input policy.
- #64 — isolated MyBatis preparation, `PreparedExecution`, DB-neutral zero-binding text bridge, and bound-execution handoff.
- #65 — stable target/session descriptors and Database Tools execution resources.
- #66 — thin action, threading, cancellation, confirmation, UX orchestration.
- #67 — diagnostics/privacy, compatibility, performance/resource evidence, documentation and legacy deletion closure.

Canonical Java overload identity is #62 work. Remembered-input isolation is #63 work. #67 validates these contracts; it does not own their design.

## 17. First code-bearing task after #391

The first implementation task is **not** another mapper/Boolean/foreach standalone proof. It is a full, testable **source+input → stock MyBatis BoundSql → verified SQL-text materialization → native Database Tools console/result** vertical path with a nonzero `#{id}` SELECT. The same fixture should include a basic OGNL `<if>` interpreted by MyBatis, not a homegrown conditional classifier.

Implementation cannot change the user-visible product until the chosen named DBMS/type slice, exact-preview artifact, supported Java/XML product behavior and fail-closed negative cases are proven. A test-only harness may exist until a single full production cutover is safe; a hidden dual production authority is forbidden. Read fresh main and one active PR before opening any new work.

Separate subsequent work should consolidate/rewrite overbuilt components and remove legacy or redundant new implementation only after actual replacement. See [rebaseline](leap-execution-rebaseline.md).

## 18. Explicitly rejected approaches

- **Refactor the god action in place:** rejected; it preserves the wrong ownership boundary.
- **Improve `ParameterExtractor` regexes until tests pass:** rejected; syntax heuristics are not caller-input provenance.
- **Keep `MyBatisEvaluator` and add more strict flags:** rejected; safety must not depend on settings and global semantic mutation remains unsound.
- **Claim a dialect-rendered SQL literal is equivalent to bound JDBC/TypeHandler execution:** rejected. A separately proven SQL-text compatibility artifact is the selected target and must disclose its fidelity envelope.
- **Partially cut over the shipping action for zero-binding statements while bound statements silently use legacy execution:** rejected; one complete product execution authority must own the cutover.
- **Couple persisted session truth to live `JdbcConsole` registration/reconstruction:** rejected; descriptor identity and platform resource lifetime are separate concerns.
- **Format SQL and then execute the formatted output:** rejected; presentation cannot mutate execution meaning.
- **Support Kotlin/providers/custom drivers now because tests can be written:** rejected for Leap v1; scope follows product value and maintainable evidence, not testability alone.
- **One giant rewrite PR:** rejected; target architecture is aggressive, delivery remains independently reviewable and rollback-bounded.
