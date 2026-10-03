# Leap target architecture

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

Current responsibilities include context detection, source extraction, statement/history identity, parameter prompting/history, evaluation dispatch, formatting/preview, datasource/schema selection, console creation/reuse, console-document mutation, query invocation, clipboard, and error UI.

**Disposition:** replace the orchestration body. The final action is a thin IDE adapter over one application use case.

### `MyBatisContextAnalyzer` and `AnnotationSqlExtractor`

These operate directly on action/event PSI and produce coarse context / raw SQL strings. Java method identity is not a complete signature and current editor/PSI authority is not encapsulated.

**Disposition:** replace with IntelliJ source adapters that emit immutable core source/statement models. No live PSI leaves the adapter.

### `ParameterExtractor`, `ParameterInputDialog`, `ParameterHistoryService`

Lexical heuristics currently become caller-input authority, the Swing dialog owns parsing semantics, and raw remembered input is keyed by an insufficient string identity.

**Disposition:** replace with a provenanced parameter contract, independent input codecs, a presentation adapter, and canonical-identity-based opt-in retention. `ParameterExtractor` is deleted as semantic authority.

### `MyBatisEvaluator`

The current object mixes MyBatis `XMLScriptBuilder`, global OGNL accessor mutation, regex source rewriting, custom nested-map lookup, settings, literal rendering, and SQL-shaped error output.

**Disposition:** replace completely. No global OGNL mutation, no authoritative unknown-tag stripping, no error-as-SQL, no evaluator-owned guessed literalization.

### `SqlFormatter`

Formatting currently feeds the string that is later executed.

**Disposition:** retain only as a presentation adapter if useful. Formatting may produce display text but never changes the immutable execution artifact.

### Target persistence and `ConsoleCacheService`

Hardening #57 established strong target-identity rules. #260 introduced independent project-scoped v3 source -> target descriptor persistence, and #262 migrates valid v2 records into that authority without recreating consoles at startup. Persisted v3 identity is stable datasource UUID + exact explicit schema, associated with canonical source identity; datasource display name remains presentation-only.

The shipping legacy action now resolves a stored v3 target exactly on a live-console cache miss and acquires a console only for that explicit invocation. `REUSE` and `NEW_EACH` are resource policies over the same persisted target authority. Closing an ephemeral console no longer deletes v3 target identity. The old v2 reader/writer and portions of `ConsoleCacheService` remain migration compatibility/deletion debt under #65.

**Disposition:** continue isolating Database Tools console mechanics and delete the remaining migration-only v2/cache surface when no supported upgrade path needs it. This Phase-4 progress does not authorize Phase-5 Leap execution cutover; #258 still blocks that cutover until the bound-execution contract is satisfied.

### Settings

`strictOgnlMode` and `ignoreUnknownTags` currently influence whether unsafe compatibility behavior occurs on the executable path. `rememberLastInputs` defaults on.

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

This is a dependency DAG, not a linear root -> engine -> core chain. Root IntelliJ adapters consume core contracts directly, while `:mybatis-engine` depends on `:core` and MyBatis. The current repository includes both `:core` and `:mybatis-engine` in `settings.gradle.kts`, and CI runs both module checks. The physical boundary defined by #101 is therefore present; #64 remains open because the semantic preparation/materialization migration and legacy evaluator replacement are not complete merely because the module exists. The platform/core dependency direction does not invert.

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

`PreparedExecution` remains authoritative for non-zero bindings. #251 tested the maintained Database Tools 2026.2 surface and found no public/maintained path that simultaneously performs programmatic ordered binding through the configured session and preserves the native console result/history UX. The exact evidence and rejected candidates are recorded in [Database Tools parameterized execution proof](database-tools-parameterized-execution.md).

#258 freezes the post-proof disposition for that maintained baseline: **there is no bound-execution fallback**. Non-zero bindings remain non-executable through the Leap adapter and fail closed with `BOUND_EXECUTION_REQUIRED` until a separately proven replacement execution contract exists.

The execution boundary must preserve the exact SQL-with-placeholders plus ordered values; it must not rewrite those values into a vendor-specific SQL string merely because the selected datasource is PostgreSQL, Oracle, MySQL, or another DBMS. The #251/#258 decision also rejects console text-substitution/second-prompt execution, internal/reflection-based bridges, automatic per-DB literalizers, and a zMyBatis-owned custom result UI as implicit fallbacks.

For zero-binding statements only, the existing immutable `MaterializedExecution` text artifact remains valid because no literalization occurs. A future display/copy projection may render values for humans, but that projection is not execution authority. The zero-binding subset is evidence for the replacement boundary, not permission to split the shipping action between Leap and legacy execution based on binding cardinality.

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

The shipping Database Tools integration already executes against a datasource configured by the user. Leap must preserve that DBMS-neutral product boundary rather than introduce a zMyBatis vendor allowlist.

For zero-binding statements, SQL text may cross the existing immutable materialization boundary byte-for-byte.

For non-zero bindings, #251 already performed the mandatory Database Tools parameterized-execution proof against the maintained 2026.2 baseline and returned NO-GO. #258 therefore keeps the gate closed rather than selecting an unsafe fallback.

Any future re-probe must still prove an adapter that:

- consumes the exact MyBatis-produced SQL placeholder order and prepared values;
- uses the already-configured datasource/driver/session;
- does not prompt for the same values again;
- preserves native console/result/history behavior under the then-current product contract;
- preserves cancellation and target/source revalidation;
- keeps raw `${}` provenance and confirmation policy separate from `#{...}` bound values.

Until that proof succeeds, authoritative Leap code returns `BOUND_EXECUTION_REQUIRED` for non-zero bindings and does not vendor-literalize them. PostgreSQL-specific literalization work remains non-authoritative historical evidence. The proof may be reopened when a maintained Database Tools baseline exposes relevant new public/maintained capability, or by a separately reviewed product change to the native result/history requirement.

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
6. for zero-binding statements, supply the immutable SQL text directly; for bound statements, require the separately proven parameterized Database Tools execution path and fail with `BOUND_EXECUTION_REQUIRED` while that gate is closed;
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

## 14. Migration and deletion sequence

Implementation is incremental for reviewability, but the target is a replacement architecture.

### Phase 0 — architecture gate

- merge #101 documentation/decision PR;
- close #61 once product contract and architecture are consistent;
- create only the first PR-sized #62 implementation task.

### Phase 1 — physical core boundary and source identity

- add `:core` and `:mybatis-engine` module skeletons with dependency-direction tests/build checks;
- introduce immutable source/statement identity models;
- implement authoritative snapshot capture + XML/Java adapters;
- implement source graph / include dependency resolution;
- keep the legacy execution path active until a new end-to-end executable path exists, but do not add features to it.

### Phase 2 — provenanced input

- introduce #63 contract/codecs;
- migrate UI to consume the contract;
- keep remembered input off until the new identity/privacy model is complete;
- delete lexical extractor authority when no production caller depends on it.

### Phase 3 — MyBatis engine and execution preparation

- introduce isolated preparation and typed failures;
- preserve SQL-with-placeholders plus ordered bindings as the authoritative non-zero-binding result;
- feed proven target database-id authority into isolated MyBatis configuration where standard `databaseId` / `_databaseId` semantics require it;
- keep only the DB-neutral zero-binding immutable SQL-text materialization bridge;
- respect #251's NO-GO evidence and #258's no-fallback disposition; bound-statement and Phase-5 production cutover stay blocked until a later proof satisfies the execution contract;
- build negative/hostile tests before enabling execution;
- delete current `MyBatisEvaluator` only once the DB-neutral bound execution path is wired.

### Phase 4 — target/session adapter

Current progress: v3 target descriptors are persisted independently; provable v2 records migrate to v3; startup eager console recreation is removed; the shipping action performs exact target re-resolution and deferred console acquisition through a migration bridge.

Remaining:
- finish isolating Database Tools console mechanics from the legacy action;
- retire the migration-only v2 reader/writer when the supported upgrade window permits;
- keep console/resource policy independent from persisted target identity.

### Phase 5 — orchestration cutover

Prerequisite: a complete replacement execution contract must exist for both zero-binding and supported non-zero-binding statements. #258 explicitly forbids using the zero-binding subset to create a production split where other statements silently remain on legacy execution.

- wire the thin action to the new use case only after that prerequisite is satisfied;
- add mandatory mutation/raw confirmation and stale-source/target revalidation;
- remove legacy action orchestration, old parameter/history/settings paths, and compatibility switches in the same cutover series with explicit deletion criteria.

### Phase 6 — production-readiness closure

- #67 closes host compatibility, DataGrip runtime evidence, diagnostics, performance/resource behavior, migration cleanup, docs/Marketplace claims, and dead legacy code.

Temporary bridges must be named as migration-only and carry an owning issue + deletion phase. No permanent dual architecture.

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

## 17. First code-bearing task after #101

After this architecture is merged and the reviewed merge result is read back from main, the first code task should be a #62 child that introduces the physical `:core` boundary plus canonical source/statement identity **without changing the production execution path**.

Its independent proof obligation is architectural: core compiles without IntelliJ/MyBatis/Database Tools dependencies, XML/Java canonical identities cannot collide, and current production behavior is untouched while the replacement foundation is established.

Do not create later speculative implementation tasks until that slice is merged and fresh-main is re-read.

## 18. Explicitly rejected approaches

- **Refactor the god action in place:** rejected; it preserves the wrong ownership boundary.
- **Improve `ParameterExtractor` regexes until tests pass:** rejected; syntax heuristics are not caller-input provenance.
- **Keep `MyBatisEvaluator` and add more strict flags:** rejected; safety must not depend on settings and global semantic mutation remains unsound.
- **Treat vendor-rendered final literal SQL as equivalent to bound JDBC/Database Tools execution:** rejected; non-zero bindings stay structured and #258 authorizes no fallback on the maintained 2026.2 baseline.
- **Partially cut over the shipping action for zero-binding statements while bound statements silently use legacy execution:** rejected; it creates two production execution authorities and weakens preview/execution and deletion invariants.
- **Couple persisted session truth to live `JdbcConsole` registration/reconstruction:** rejected; descriptor identity and platform resource lifetime are separate concerns.
- **Format SQL and then execute the formatted output:** rejected; presentation cannot mutate execution meaning.
- **Support Kotlin/providers/custom drivers now because tests can be written:** rejected for Leap v1; scope follows product value and maintainable evidence, not testability alone.
- **One giant rewrite PR:** rejected; target architecture is aggressive, delivery remains independently reviewable and rollback-bounded.
