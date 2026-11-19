# zMyBatis 1.0 product capability and safety contract

**2026-11-16 pre-1.0 parity baseline:** [MyBatis-compatibility architecture](mybatis-compatibility-architecture.md)
is the current target architecture under #512. It supersedes the bounded SQL-text/materializer target
from #391 wherever the two conflict. #251 remains valid historical API evidence; the current
registered action remains bounded until the new architecture is implemented and proven.

This document is the **1.0 product-policy authority**. Architecture is defined by
[mybatis-compatibility-architecture.md](mybatis-compatibility-architecture.md). The current Leap
implementation is migration evidence, not a preservation constraint.

Baseline recorded from protected `main` `402ee67d48123235828a2ef2bc1642c7000e53a2`.

## 1. Status vocabulary

- **REQUIRED** — must be supported before 1.0 release.
- **UNSUPPORTED-1.0** — deliberately outside 1.0 and must fail visibly before execution.
- **CURRENTLY-BOUNDED** — current Leap implementation supports only a narrower slice; this is not the
  target product contract.
- **UNKNOWN** — required semantics cannot be proven. UNKNOWN blocks execution.

**Invariant:** unsupported, unknown, ambiguous, stale, or failed preparation can never become
plausible executable SQL.

## 2. zMyBatis 1.0 capability matrix

| Area | 1.0 decision | Target owner |
| --- | --- | --- |
| XML `<select>/<insert>/<update>/<delete>` | **REQUIRED** | source + stock-MyBatis preparation |
| Ordinary `resultMap/resultType/parameterType` mapper declarations | **REQUIRED** when they do not require arbitrary application-runtime execution; harmless result/cache metadata must not block unrelated SQL generation | statement-scoped preparation projection |
| XML `<sql>/<include>` | **REQUIRED**, including same/cross-namespace and parameterized dependency graphs | source graph + preparation projection |
| Standard dynamic tags (`if`, `choose/when/otherwise`, `foreach`, `where`, `set`, `trim`, `bind`) | **REQUIRED** through stock MyBatis semantics; zMyBatis must not define a second dynamic grammar | isolated MyBatis engine |
| Mapper-method-backed XML input | **REQUIRED** for `@Param`, multi-argument, scalar/container/map/object shapes | invocation contract |
| XML-only mapper input | **REQUIRED** through one explicit parameter-object/environment; Java mapper method is not mandatory | invocation contract |
| Java `@Select/@Insert/@Update/@Delete` | **REQUIRED** with the same stock-MyBatis/execution-plan architecture | source + invocation + preparation |
| Kotlin direct statement annotations | **UNSUPPORTED-1.0** | future explicit capability |
| Provider annotations | **UNSUPPORTED-1.0** | future explicit capability |
| `databaseId` selection | **REQUIRED** when effective authority is proven from the target/configuration evidence | source/preparation/target |
| Custom LanguageDriver / arbitrary runtime mapper extension | **UNSUPPORTED-1.0** unless separately promoted | explicit application-runtime capability |
| `#{}` ordered bindings | **REQUIRED** through immutable prepared execution plans and the prepared backend; no universal DBMS literalization requirement | preparation + execution backend |
| Multiple bindings and foreach/bind additional parameters | **REQUIRED** | stock MyBatis + prepared backend |
| Common stock scalar/JDBC binding | **REQUIRED** for integral/floating/decimal/boolean/string/binary/date/time/timestamp and typed null | stock MyBatis binding + prepared backend |
| Application custom TypeHandler runtime parity | **UNSUPPORTED-1.0** unless runtime authority is explicitly proven | explicit capability boundary |
| `${}` | **REQUIRED only as explicit raw interpolation**, separately provenanced and confirmed | invocation/preparation/approval |
| SELECT | **REQUIRED** after source/input/preparation/target contracts pass | orchestration/backend |
| INSERT/UPDATE/DELETE | **REQUIRED** with mandatory final-plan confirmation | approval/backend |
| Stable datasource UUID + explicit schema | **REQUIRED** | target identity |
| Default-schema restart | **UNSUPPORTED-1.0** until stable search-path identity is proven | target identity |
| IntelliJ IDEA Ultimate | primary 1.0 host evidence | packaged process evidence |
| DataGrip | separately scoped host evidence; do not infer IDEA parity automatically | host-specific evidence |
| Generic other JetBrains hosts | **UNSUPPORTED-1.0/UNKNOWN** without maintained evidence | future host matrix |

#509 and #510 are parity-regression evidence against this matrix, not reasons to narrow the matrix.

## 3. Current shipping implementation during parity recovery

The registered product currently uses one contract-driven Leap execution authority. The former proxy
action, heuristic SQL/parameter extractors, compatibility evaluator, legacy parameter dialog/history,
and development-only v2 persistence migration are not production fallbacks.

This section describes **current behavior only** after the P4 unified execution-plan cutover.

Current ownership is:

- action/context presentation: `MyBatisExecuteAction` + bounded `MyBatisContextAnalyzer`;
- authoritative source/statement capture: Leap Java/XML source adapters and source graphs;
- caller input authority: typed invocation contracts, codecs, contract UI and canonical-identity retention;
- preparation: isolated stock MyBatis in `:mybatis-engine`;
- execution authority: one immutable `PreparedExecutionPlan` approved and revalidated as an exact identity;
- bound execution: `DatabaseSessionPreparedBackend` with stock MyBatis TypeHandler/JDBC metadata;
- zero-binding/exact-text execution: `NativeConsoleSqlTextBackend`;
- prepared result presentation: bounded zMyBatis result adapter;
- target persistence: v3 `ExecutionTargetDescriptorStore`;
- live Database Tools resources: `ConsoleCacheService` + `DatabaseToolsConsoleAdapter`.

Prepared/native backends are explicit capability routes, not fallbacks. Safety is not configurable
through compatibility evaluator switches, and unsupported behavior does not degrade to an older
evaluator/materializer path.

## 4. Source authority and canonical identity

### Active editor

The active editor `Document` snapshot is authoritative for an invocation. Unsaved editor content cannot be silently replaced by last-committed PSI or disk content.

Adapters may synchronize PSI from the Document for project/index semantics, but this does not imply a disk save. Saving user files is never an execution-preparation side effect.

### XML identity

Canonical identity includes:

- stable source-file identity;
- mapper namespace;
- statement id.

Duplicate/ambiguous resolution blocks.

### Java identity

Canonical identity includes:

- stable source-file identity;
- qualified mapper type;
- complete method signature, including ordered parameter type identity.

Overloads cannot share statement/history identity.

Canonical statement/method identity is owned by #62. #63 consumes it for remembered-input isolation; #67 supplied the final compatibility/readiness verification rather than owning either design.

## 5. XML composition policy

Leap v1 intentionally supports ordinary MyBatis fragment composition rather than treating it as an unbounded runtime extension.

- `<sql>/<include>` source dependencies are explicit in the source graph.
- Same-namespace and qualified references are resolved from captured project mapper sources.
- Live dependent Documents are authoritative over stale disk content when they exist.
- Missing, ambiguous, or cyclic dependency paths block. `databaseId` variants are preserved and selected by stock MyBatis from proven target-derived database-id authority; if that authority or a custom provider mapping cannot be proven, only the dependent statement blocks. Custom language-driver paths remain unsupported unless separately promoted.
- zMyBatis does not invent a parallel include-expansion semantics when maintained MyBatis mapper parsing can be used as the semantic authority.

## 6. Parameter and input policy

A parameter is not “whatever identifier a regex finds.”

The target input model distinguishes:

- explicit caller/logical values;
- source/method `@Param` provenance;
- generated aliases only when their origin is known;
- single-object/map/collection environments;
- MyBatis internal/context variables;
- foreach item/index and bind/additional parameters;
- `#{}` bound-value requirements;
- `${}` raw interpolation requirements;
- unknown/ambiguous requirements.

Unknown/ambiguous requirements block rather than becoming guessed fields or implicit nulls.

For mapper-method-backed XML, the real method signature and `@Param` metadata are primary caller
authority. For XML-only mappers, the 1.0 target accepts one explicit parameter-object/environment
instead of inventing a fake mapper signature or treating a dynamic-tag whitelist as caller authority.

Input parsing is independent of Swing. Numeric/decimal/type fidelity is deliberate, not a convenience conversion.

## 7. Remembered inputs and examples

Remembered inputs are convenience, not a correctness requirement.

zMyBatis 1.0 retention rules:

- default **OFF** until #63 provides canonical identity, project scope, explicit retention/clearing, and sensitive-data policy;
- values never bleed across distinct canonical statements or projects;
- raw `${}` values are not remembered by default;
- remembered/example values never become execution input without explicit user action;
- if safe retention is not worth its complexity, remove the feature.

Historical JSON-default request #69 is accepted only as non-authoritative example/scaffold presentation over the new input contract. Parameter-name suffix guessing is not semantic authority.

## 8. MyBatis semantic boundary

MyBatis is the semantic authority for behavior zMyBatis claims as MyBatis-compatible.

The target engine:

- owns an isolated `Configuration` per preparation or equivalent explicitly scoped lifecycle;
- does not mutate application-global OGNL accessors/state;
- does not regex-strip unknown tags on the authoritative path;
- does not rewrite OGNL into a separate compatibility language without an explicit non-authoritative mode;
- consumes complete supported source graphs;
- preserves ordered `BoundSql` mappings and MyBatis additional parameters;
- returns typed preparation results/failures.

Evaluation errors are never SQL text.

## 9. Prepared execution and execution backends

The stock-MyBatis preparation result remains structured: canonical statement/source identity,
SQL-with-placeholders, ordered mappings and resolved values including additional parameters, raw
interpolation provenance, and relevant binding metadata.

The 1.0 target freezes that meaning as one immutable **PreparedExecutionPlan** bound to the exact
source revisions and target. Bound statements are not required to become literal SQL text.

Two explicit backends sit below one orchestration/approval authority:

- **DatabaseSessionPreparedBackend** — target primary backend for statements with JDBC bindings.
  It reuses the configured Database Tools datasource/session, executes a true prepared statement,
  preserves ordered/additional bindings, and presents structured rows/update counts/errors through a
  bounded zMyBatis result adapter.
- **NativeConsoleSqlTextBackend** — retained for zero-binding/exact-text statements, preserving
  Database Tools native result/history. Historical materializer slices are not registered fallback routes.

The prepared backend should use stock MyBatis standard ParameterMapping/TypeHandler/JDBC semantics
where safely available rather than introducing another home-grown scalar binder matrix. Application
custom TypeHandlers remain outside 1.0 unless runtime authority is explicitly proven.

#251 remains historical evidence that a Database Tools-owned connection/prepared mechanism exists
while the investigated 2026.2 surface lacked a proven maintained bridge back into native console
result/history. Native result/history parity is therefore not a correctness prerequisite for the
prepared backend.

**P3 stop gate — passed:** maintained APIs now have production evidence for Database Tools-owned
session/connection use, prepared execution, cancellation, disposal and datasource/schema correctness.
Those guarantees remain hard constraints for subsequent phases.

The registered action now implements the planned split:
- ordered bindings execute through `DatabaseSessionPreparedBackend`;
- zero-binding/exact-text plans execute through `NativeConsoleSqlTextBackend`.

The former PostgreSQL BIGINT SQL-text materializer is migration history, not current execution authority.

## 10. Preview, formatting, and execution identity

For prepared execution, exact identity is the immutable plan:

```text
SQL with placeholders
+ ordered typed bindings
+ raw interpolation result/provenance
+ source revisions
+ target identity
+ statement kind
```

Preview may render the SQL plus a typed parameter table. Presentation formatting cannot mutate the
approved plan.

For exact-text/native-console execution, the immutable exact SQL artifact remains the payload.

- Preparation, parsing, formatting, persistence, history and preview do not execute SQL.
- Revalidate source, target, cancellation/disposal and approval immediately before dispatch.
- DML and raw `${}` require explicit final-plan confirmation.
- One orchestration authority selects an explicit backend by capability; no legacy or silent
  correctness fallback is allowed.

## 11. Safety posture

### Raw interpolation

`${}` is raw source interpolation, not a normal binding. Its presence and values require explicit user confirmation before execution.

### Mutation

INSERT/UPDATE/DELETE mapper declaration kinds require explicit confirmation of the final immutable execution artifact.

Statement kind is UX evidence, not authorization. If execution meaning is unknown/unsupported, the operation blocks rather than merely asking for confirmation.

### Failure

Source, dependency, input, MyBatis, plan, binding, target, backend, cancellation, and database failures
are typed outcomes. No failure string can flow into the execution API as SQL.

### Side effects

Only the explicit zMyBatis execution action may invoke SQL. Source capture, preparation, preview, formatting, settings, history, startup, restoration, and diagnostics do not execute.

## 12. Target/session contract

Preserve the proven #57 safety invariants:

- project-scoped persisted state;
- stable datasource UUID rather than display-name matching;
- explicit named-schema restart identity;
- missing/ambiguous datasource/schema fails closed;
- malformed/interrupted persistence is cleaned safely;
- restoration never executes SQL.

Current shipping v3 target persistence stores project-scoped canonical source-to-target descriptors (stable datasource UUID plus explicit named schema), not live `JdbcConsole` objects. Development-only v2 persistence was never distributed and has no runtime migration reader; see [session persistence](session-persistence.md).

Current target/session rules:

- the v3 `ExecutionTargetDescriptor` remains independently meaningful and revalidatable without a live console cache entry;
- startup may prune stale current-v3 source associations but does not recreate consoles or migrate deleted legacy state;
- console/editor/document resources are ephemeral Database Tools adapter resources;
- `REUSE`/`NEW_EACH` are optional in-memory resource policies, not target identities;
- default-schema restart remains unsupported until stable search-path identity exists.

## 13. Threading, cancellation, and lifecycle

- `AnAction.update()` remains cheap, bounded, side-effect free, and BGT-compatible.
- action invocation captures IDE context and immediately converts it into adapter/application data; `AnActionEvent` never enters core.
- editor/dialog/popup operations obey EDT requirements.
- index/PSI source work obeys IntelliJ read-action requirements and emits immutable snapshots.
- MyBatis preparation, execution-plan construction and backend handoff run in the appropriate cancellable background execution context.
- project/source/target validity is rechecked after asynchronous or modal boundaries and before execution.
- cancellation before query invocation means no query invocation.
- stale callbacks cannot reuse a prior artifact or target.

## 14. Privacy and diagnostics

Normal logs must not contain:

- raw parameter values;
- remembered inputs;
- raw `${}` values;
- rendered/materialized SQL;
- credentials or equivalent sensitive target data.

Expected user/source/unsupported/database failures are normal typed outcomes, not IntelliJ fatal errors. `Logger.error` is reserved for genuine internal invariants/platform faults that require operator attention.

Existing privacy hardening remains protected during parity recovery; regaining mapper parity never authorizes sensitive logging or restoration of the deleted legacy action architecture.

## 15. IDE compatibility policy

- IDEA Ultimate and DataGrip are evaluated independently.
- The minimum compatible IDE build is **263**. IDEA Ultimate 2026.3 is the compile/test SDK, Plugin Verifier target and real Starter/Driver PostgreSQL execution target under #521. The 2026.2 line is deliberately unsupported: its editor API differs, and supporting it via `@Internal` is not an acceptable product boundary. Only exact-HEAD CI can establish the 2026.3 runtime evidence.
- DataGrip 2026.3 is independently configured by `dataGripPlatformVersion` and is an independent Plugin Verifier and Starter/Driver target. Its process proof is bounded to packaged-plugin/service loading, the registered XML action's typed no-datasource refusal and clean shutdown. This is a required check, not a claim of a passed result.
- DataGrip host evidence does not imply Java-annotation parity or native PostgreSQL result/history parity; those claims require explicit host-specific proof before they can be added.
- Database Tools APIs are isolated behind adapters so platform drift has a bounded replacement surface. The SQL-console editor-open flow uses the 263 public `OpenFileDescriptor.navigate(true)` navigation entry point, then revalidates the immutable SQL artifact, live console and actual editor in its EDT continuation before any injection. This avoids `@Internal` and `@Experimental` editor-open APIs.
- Public Marketplace/README compatibility claims must match the maintained exact-head verifier/runtime evidence established by #67 and be refreshed whenever the compatibility claim changes.

## 16. Target product workflow

1. capture immutable authoritative current-editor source and canonical statement identity;
2. resolve reachable source dependencies and target-derived databaseId authority;
3. construct caller input authority from the real mapper method when available, otherwise one explicit
   XML-only parameter-object/environment;
4. build a statement-scoped MyBatis preparation projection without unrelated result/cache blocking;
5. collect and validate explicit user input;
6. let stock MyBatis produce BoundSql, ordered mappings, additional values and relevant binding metadata;
7. freeze one immutable PreparedExecutionPlan bound to source revisions and exact target identity;
8. require raw/mutation approval and revalidate source, target, cancellation and lifecycle authority;
9. route bound statements to the prepared backend through the proven P3 maintained-API boundary;
10. route zero-binding/exact-text statements to the native-console backend;
11. present structured result/failure with redacted diagnostics.

P4 cutover is complete. The registered action executes only the exact approved
`PreparedExecutionPlan` through its selected explicit backend and fails closed rather than
switching to an alternate backend or legacy materializer.

## 17. Current settings disposition

Current settings are product behavior only where an active Leap owner consumes them:

- **SQL Preview:** optional presentation for safe read paths; mandatory mutation/raw confirmation overrides it.
- **Auto-format SQL Preview:** presentation only; it never changes the immutable execution artifact.
- **Remember Last Inputs:** opt-in canonical-statement retention subject to the #63 retention policy; raw interpolation is never retained.
- **Console Session Policy:** ephemeral live-resource policy only; it is not target identity.

Removed evaluator/dialog compatibility fields are intentionally absent from current runtime state and UI.

## 18. Ownership during pre-1.0 parity recovery

Historical Leap tracks #61-#67 remain evidence for source, input, preparation, target, orchestration
and readiness work already completed. They do not override the new 1.0 target.

Active parity ownership:

- #512 — canonical architecture/product rebaseline;
- #509 — standard dynamic-tag parity regression evidence;
- #510 — ordinary XML mapper-declaration parity regression evidence;
- #508/#511 — repository-owned manual release-validation harness;
- #513 — D0 documentation recording of the target architecture.

D0-D1 and P1-P4 are complete. P5 owns packaged/manual acceptance and #509/#510 evidence
reconciliation; P6 follows with deletion/cleanup. Do not reopen #61-#67 semantics by incrementally
restoring their old bounded admission/materializer islands.

## 19. Non-goals for zMyBatis 1.0

- Kotlin direct mapper annotation execution;
- Provider runtime execution;
- arbitrary custom LanguageDriver behavior;
- arbitrary application custom TypeHandler/runtime-plugin emulation;
- generic SQL authorization/classification;
- ORM/application result mapping in the prepared-backend result adapter;
- native Database Tools result/history parity for the prepared backend;
- preserving superseded Leap admission/materializer class structure merely because it still exists;
- restoring a deleted legacy execution fallback.

A true prepared backend **is** the registered execution backend for ordinary bound statements; the
P3 maintained-API stop gate has passed and remains a regression constraint.
