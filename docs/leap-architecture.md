# Leap current architecture and retained safety boundaries

**2026-11-16 pre-1.0 parity note:** the target architecture is now
[MyBatis-compatibility architecture](mybatis-compatibility-architecture.md) under #512. That target
supersedes the bounded SQL-text/materializer architecture in this document wherever the two conflict.

This document records the **current Leap implementation**, the outer boundaries worth retaining, and
historical rationale from Epic #60. It is not the pre-1.0 target authority.

Current implementation baseline after #511: protected `main`
`402ee67d48123235828a2ef2bc1642c7000e53a2`.

The product-policy target is [product-contract.md](product-contract.md). The canonical pre-1.0
architecture is [mybatis-compatibility-architecture.md](mybatis-compatibility-architecture.md).
Repository/review discipline remains defined by `AGENTS.md` and `.github/merge-gate-policy.yml`.

## 1. Architecture drivers

The Leap exists because the current implementation mixes too many correctness domains in a few mutable IDE-facing objects.

The retained architecture is driven by these invariants:

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

### Registered action

`MyBatisExecuteAction` is the single registered IDE execution adapter. It owns bounded context presentation/provider refusal and delegates supported Java/XML execution to `LeapActionContextAdmissionCoordinator`. There is no second execution action or fallback evaluator.

Low-level console creation, schema switching, editor/document mutation, script-model construction, and native query invocation remain isolated behind `DatabaseToolsConsoleAdapter`.

### Source authority

`MyBatisContextAnalyzer` remains only a cheap action-presentation classifier. Authoritative execution source comes from immutable Leap source adapters: `ActiveEditorSourceSnapshotAdapter`, Java/XML capture adapters, source-graph resolution, and pre-execution revalidation. No raw-string annotation extractor participates in execution authority.

### Parameter/input authority

Parameter requirements are typed/provenanced contracts. Contract codecs/submission policy, `ContractParameterInputDialog`, and `ContractInputHistoryService` own validation, presentation, and opt-in retention. Raw interpolation remains a distinct non-retainable input kind. There is no regex parameter extractor, legacy JSON dialog, or raw-string history service in production.

### MyBatis preparation and materialization

The root plugin packages `:mybatis-engine`. Supported source shapes prepare through isolated stock MyBatis semantics and return structured `PreparedExecution`. Execution is allowed only after an explicitly proven materializer creates one immutable target-bound SQL-text artifact. Unsupported dialect/type/semantic shapes fail typed and do not route to a compatibility evaluator.

### Target persistence and `ConsoleCacheService`

`ExecutionTargetDescriptorStore` is the sole persisted target owner. It stores current v3 canonical source → stable datasource UUID + explicit schema descriptors independently of live console resources.

Development-only v2 persistence was never distributed to users, so no reader/converter/cleanup compatibility path remains. Startup may prune stale v3 source associations only. `ConsoleCacheService` owns live-resource reuse/selection/shutdown lifecycle and has no persistence surface.

### Settings

Current runtime settings are limited to active Leap consumers: optional SQL preview, presentation-only auto-formatting, console resource policy, and opt-in remembered inputs. Removed evaluator/dialog compatibility switches do not influence execution safety.

## 3. Physical dependency boundaries

The current Gradle layout is deliberately split so architecture is enforced by compilation rather than convention alone.

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

This is a dependency DAG, not a linear root → engine → core chain. Root plugin adapters consume core models; the MyBatis adapter depends on core and stock MyBatis. The root plugin currently depends on both `:core` and `:mybatis-engine`; the engine is packaged in the registered Leap path. Separate engine tests remain lower-layer evidence and are not by themselves packaged UX proof. The platform/core dependency direction does not invert.

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

### Current execution artifact boundary

The current registered Leap implementation converts structured `PreparedExecution` into an exact
target-bound SQL-text artifact only for proven materialization shapes. Zero-binding is DB-neutral and
the maintained nonzero slice is PostgreSQL BIGINT. Unsupported nonzero shapes fail typed.

This remains valid **current behavior** and useful safety evidence, but it is no longer the 1.0 target.
The pre-1.0 architecture freezes an immutable `PreparedExecutionPlan` and routes bound statements to
a true prepared backend if the P3 platform proof succeeds. See
[MyBatis-compatibility architecture](mybatis-compatibility-architecture.md).

#251 remains historical evidence about the investigated 2026.2 Database Tools APIs. #391 remains
historical evidence explaining why SQL-text materialization was selected for the first Leap cutover.
Neither requires 1.0 to preserve single-binding literalization as the general execution model.

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

It emits the same core statement/input preparation model as XML; no separate raw-string annotation extraction abstraction participates in execution.

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

## 8. Current execution boundary and pre-1.0 replacement

Current shipping behavior:

1. stock MyBatis produces structured prepared evidence;
2. a bounded materializer creates an exact SQL-text artifact only for a proven shape;
3. approval and source/target/cancellation checks apply to that artifact;
4. Database Tools native console executes the exact text.

The registered action currently admits DB-neutral zero-binding and the proven PostgreSQL BIGINT
nonzero slice. Other nonzero shapes fail closed and do not route to legacy execution.

This current boundary is protected from unsafe fallback, but its materializer restriction is not the
1.0 target. The target defined in
[MyBatis-compatibility architecture](mybatis-compatibility-architecture.md) uses one immutable
execution-plan authority with explicit backends:

- bound statements -> `DatabaseSessionPreparedBackend` after the P3 maintained-API stop gate;
- zero-binding/exact-text statements -> `NativeConsoleSqlTextBackend`.

Native Database Tools result/history parity is not a correctness requirement for the prepared
backend. Source/target/lifecycle/approval invariants remain unchanged in strength.

## 9. Target/session and Database Tools adapter

Persist only current v3 `ExecutionTargetDescriptor` data. Descriptor lifecycle is independent of live-console registration/disposal, and there is no older-generation migration reader.

Startup behavior:

- validate/prune current v3 descriptor syntax and stale `vfs:` source associations;
- stop maintenance when project shutdown wins;
- do not resolve Database Tools resources merely because a descriptor exists;
- do not create a `JdbcConsole`, switch schema, open an editor, or execute SQL.

Execution behavior:

1. resolve the persisted/current descriptor to exactly one live datasource and explicit schema;
2. fail on missing or ambiguity;
3. re-check target validity after user/modality/background boundaries;
4. acquire/reuse a console as an ephemeral resource according to policy;
5. apply schema and verify success;
6. submit the single immutable proven SQL-text artifact; unproven nonzero shapes fail typed;
7. invoke the native Database Tools path without a zMyBatis DBMS target allowlist;
8. preserve/restore console document/editor state according to the adapter contract.

`REUSE` and `NEW_EACH` are resource policies, not target identities.

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

PR #117 removed the known normal-level SQL/input leakage before cutover. Current logging evidence remains intentionally bounded rather than a semantic taint proof; #67 completed the final privacy/diagnostic review, and there is no retained legacy execution architecture to preserve.

## 14. Pre-1.0 parity recovery

Leap has one registered execution authority and the former legacy evaluator/action is deleted. That
outer cleanup remains valid.

The pre-1.0 recovery does **not** reintroduce the legacy path. It replaces bounded middle-layer
semantics under the single authority.

Mandatory order:

```text
D0  record the parity architecture in authoritative docs
  -> D1  stock MyBatis parity oracle
  -> P1  statement-scoped preparation projection
  -> P2  mapper-method/XML-only invocation contract replacement
  -> P3  prepared-backend spike + hard stop gate
  -> P4  unified execution-plan cutover
  -> P5  packaged/manual parity
  -> P6  superseded admission/preflight/materializer cleanup
```

#509 and #510 are parity-regression evidence. Do not patch them independently into the current
dynamic-admission/materializer architecture before D1.

## 15. Evidence ownership during recovery

Current tests remain authoritative evidence for the current shipping safety boundaries they actually
exercise: source identity, target identity, cancellation/lifecycle, privacy, approval, packaged host
behavior, and bounded current preparation/materialization.

They are **not** automatically evidence that a current refusal is desired 1.0 behavior.

D1 must add a stock-MyBatis parity oracle for ordinary mapper declarations, include graphs, standard
dynamic tags, realistic input shapes, additional parameters, direct Java annotations, raw
interpolation, and sanitized historical `mybatisTest` mapper shapes.

Each replacement phase adds zMyBatis-vs-oracle parity for its slice. A phase cannot redefine the
oracle merely because the current Leap implementation refuses that case.

## 16. Retained ownership boundaries

Keep:

- current-editor source authority and canonical statement identity;
- explicit source dependency graph and databaseId authority;
- platform/core/MyBatis module boundaries;
- stable datasource UUID + explicit schema target identity;
- target persistence separate from live console resources;
- thin action, threading/cancellation and lifecycle revalidation;
- typed failures and privacy/logging rules;
- raw and mutation approval boundaries.

Replace after parity evidence exists:

- dynamic grammar allowlists in `XmlStatementParameterContractFactory`;
- Boolean-if/foreach/trim admission islands;
- `DynamicOgnlAdmission` and `ForeachProvenanceAdmission`;
- whole-document dangerous result/parameter metadata preflight;
- PostgreSQL BIGINT single-binding materialization as the universal nonzero gate.

Do not retain old and new semantic authorities simultaneously.

## 17. Explicitly rejected approaches

- **Return to the deleted legacy evaluator:** rejected.
- **Extend zMyBatis dynamic-tag/OGNL allowlists until every example passes:** rejected; stock MyBatis
  owns those semantics.
- **Treat current fail-closed behavior as the desired product contract merely because tests exist:**
  rejected; D1 stock-MyBatis parity defines the target semantic oracle.
- **Strip arbitrary mapper XML with regexes:** rejected; preparation projection is structural.
- **Replace SQL literalization with another hand-written scalar JDBC binder matrix:** rejected as the
  default target; prefer stock MyBatis standard binding semantics.
- **Require native Database Tools result/history at the cost of refusing ordinary bound MyBatis
  statements:** rejected for the prepared backend.
- **Hide a second execution authority behind fallback routing:** rejected.
- **Weaken source/target/cancellation/privacy protections to regain parity:** rejected.
- **One giant rewrite PR:** rejected; delivery stays phase-gated and rollback-bounded.
