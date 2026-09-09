# Leap target architecture

Status: target architecture for Epic #60 / architecture task #101.

Audit baseline: `main` `249d3a5058fee71b858cb7926dbb30864ee15858`.

This document defines the architecture zMyBatis is moving **to**. The current production classes are migration inputs and behavioral evidence; they are not preservation constraints. Where this document conflicts with an implementation technique in current `main`, the target architecture wins once the corresponding boundary is implemented and proven.

The product policy is defined by [product-contract.md](product-contract.md). Repository/review discipline remains defined by `AGENTS.md` and `.github/merge-gate-policy.yml`.

## 1. Architecture drivers

The Leap exists because the current implementation mixes too many correctness domains in a few mutable IDE-facing objects.

The target is driven by these invariants:

1. **Correctness over plausibility.** Unknown source, input, evaluation, materialization, or target state blocks execution.
2. **MyBatis is the semantic authority** for behavior claimed as MyBatis-compatible; zMyBatis must not maintain a parallel approximate dynamic-SQL interpreter.
3. **Current editor content is authoritative.** Active unsaved edits cannot be silently replaced by stale committed PSI or disk content.
4. **`#{}` and `${}` are different types of input.** This distinction survives every layer.
5. **Preparation and execution are separate capabilities.** Preparing, formatting, previewing, restoring state, or cancelling cannot execute SQL.
6. **Preview and execution cannot drift.** The immutable materialized artifact approved for execution is exactly what the Database Tools adapter executes.
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

### `ConsoleCacheService` and startup restoration

Hardening #57 established strong target-identity rules. Current v2 persistence stores string records (`mapperKey`, stable datasource UUID, datasource display name, explicit schema), not live `JdbcConsole` objects. The architectural coupling is that persisted-record creation/removal follows live-console cache registration/disposal, and startup eagerly reconstructs consoles from those records.

**Disposition:** preserve the stable-target contracts, replace the live-console-cache/persistence lifecycle coupling, and keep persisted target/session descriptor data independently meaningful. Consoles are ephemeral Database Tools resources created/reused in-memory only when needed.

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

This is a dependency DAG, not a linear root -> engine -> core chain. Root IntelliJ adapters consume core contracts directly, while the future `:mybatis-engine` adapter also depends on `:core` and MyBatis. The current implementation phase includes only `:core` in `settings.gradle.kts`; #111 established the direct root plugin -> `:core` dependency. `:mybatis-engine` remains a target module to be introduced by later #64 work. This is the exact-mechanics adjustment permitted by #101; the platform/core dependency direction does not invert.

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

The graph represents the complete source required for supported preparation. XML fragment/include edges are explicit. Missing, ambiguous, cyclic, unsupported `databaseId`, custom language-driver, or runtime-only dependencies become typed source failures.

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

Each ordered binding records enough metadata to decide whether the execution adapter can materialize it safely, including property provenance and relevant Java/JDBC/type-handler identity where available.

### Materialized execution

```text
MaterializedExecution(
  preparedIdentity,
  targetDialectIdentity,
  executionSql,
  safetyFlags,
  fingerprint
)
```

This is the immutable artifact supplied to preview/confirmation/copy/execution. A formatter may derive `displaySql`, but `displaySql` is never substituted back into `executionSql`.

### Execution target

```text
ExecutionTargetId(projectScope, stableDataSourceId, explicitSchemaOrSearchPath)
ExecutionTargetDescriptor(id, diagnosticDisplayName, dialectHint, version)
```

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

Leap v1 rejects source whose correctness depends on unsupported `databaseId`, custom language-driver, provider, or runtime-only extension behavior.

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

## 8. Materialization boundary

The current Database Tools adapter consumes SQL text. That fact must not leak backward and force the core to claim JDBC/TypeHandler parity.

`ExecutionMaterializer` converts a `PreparedExecution` for a resolved target/dialect into exactly one `MaterializedExecution`.

Rules:

- exact placeholder/mapping cardinality;
- no `toString()` fallback for unknown objects;
- no List/Map marker SQL;
- no unsupported/custom TypeHandler guessing;
- explicit supported type/dialect matrix;
- correct escaping/encoding rules per supported materializer;
- failure outside the matrix.

If a future credible Database Tools API permits parameterized execution while preserving native result/history behavior, a new materializer/adapter may use it without changing core preparation contracts.

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
6. supply exactly `MaterializedExecution.executionSql` to the isolated Database Tools adapter;
7. invoke the native console execution path;
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
  -> materialize for target/dialect
  -> revalidate source + target
  -> apply safety confirmation
  -> execute materialized artifact
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

### Preparation/materialization

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
  UnsupportedDatabaseId
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
  UnsupportedSemantic
  PreparationInvariantFailure

MaterializationFailure
  UnsupportedDialect
  UnsupportedType
  UnsupportedTypeHandler
  PlaceholderCardinalityMismatch
  EncodingOrEscapingFailure

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

### Phase 3 — MyBatis engine and materialization

- introduce isolated preparation and typed failures;
- introduce target-aware materializer and fidelity matrix;
- build negative/hostile tests before enabling execution;
- delete current `MyBatisEvaluator` once the new path is wired.

### Phase 4 — target/session adapter

- persist target descriptors;
- migrate/invalidate v2 records only when identity is provable;
- remove startup eager console recreation;
- isolate Database Tools console mechanics.

### Phase 5 — orchestration cutover

- wire the thin action to the new use case;
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
- materialized-artifact identity rules independent of IDE.

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

Every PR remains exact-HEAD proof-obligation gated. Any HEAD move invalidates approval. Post-merge main is revalidated before closing the owning task.

## 16. Track ownership after reset

- #61 — target product/capability/safety decisions; closes after #101 consistency, not after all implementation.
- #62 — source authority, dependency graph, canonical statement/method identity.
- #63 — input provenance, codecs, UI contract, remembered-input policy.
- #64 — isolated MyBatis preparation, `PreparedExecution`, target-aware materialization.
- #65 — stable target/session descriptors and Database Tools execution resources.
- #66 — thin action, threading, cancellation, confirmation, UX orchestration.
- #67 — diagnostics/privacy, compatibility, performance/resource evidence, documentation and legacy deletion closure.

Canonical Java overload identity is #62 work. Remembered-input isolation is #63 work. #67 validates these contracts; it does not own their design.

## 17. First code-bearing task after #101

After this architecture is merged and main is revalidated, the first code task should be a #62 child that introduces the physical `:core` boundary plus canonical source/statement identity **without changing the production execution path**.

Its independent proof obligation is architectural: core compiles without IntelliJ/MyBatis/Database Tools dependencies, XML/Java canonical identities cannot collide, and current production behavior is untouched while the replacement foundation is established.

Do not create later speculative implementation tasks until that slice is merged and fresh-main is re-read.

## 18. Explicitly rejected approaches

- **Refactor the god action in place:** rejected; it preserves the wrong ownership boundary.
- **Improve `ParameterExtractor` regexes until tests pass:** rejected; syntax heuristics are not caller-input provenance.
- **Keep `MyBatisEvaluator` and add more strict flags:** rejected; safety must not depend on settings and global semantic mutation remains unsound.
- **Treat final literal SQL as if it were JDBC execution:** rejected; materialization has explicit fidelity limits.
- **Couple persisted session truth to live `JdbcConsole` registration/reconstruction:** rejected; descriptor identity and platform resource lifetime are separate concerns.
- **Format SQL and then execute the formatted output:** rejected; presentation cannot mutate execution meaning.
- **Support Kotlin/providers/custom drivers now because tests can be written:** rejected for Leap v1; scope follows product value and maintainable evidence, not testability alone.
- **One giant rewrite PR:** rejected; target architecture is aggressive, delivery remains independently reviewable and rollback-bounded.
