# zMyBatis Leap product capability and safety contract

This document is the product-policy authority for Leap Epic #60 and Track #61.

Architecture is defined by [leap-architecture.md](leap-architecture.md). Current implementation behavior is evidence and migration input, **not a preservation constraint**. When a target decision is explicit, downstream work may replace or delete the current classes, settings, persistence formats, and execution flow.

Target contract baseline: product decisions frozen from fresh `main` `249d3a5058fee71b858cb7926dbb30864ee15858` under #61/#101.

## 1. Status vocabulary

- **SUPPORTED** — part of the maintained Leap v1 product contract when its required evidence and dependencies are satisfied.
- **UNSUPPORTED** — deliberately outside Leap v1; the product stops visibly before execution.
- **COMPATIBILITY-ALTERED** — intentionally differs from stock MyBatis/application runtime and must be labeled; it is not silently treated as supported equivalence.
- **DEGRADED** — useful current behavior exists but is not acceptable as the final authoritative path.
- **UNKNOWN** — required semantics cannot be proven. UNKNOWN blocks execution.

**Invariant:** unsupported, unknown, ambiguous, stale, or failed preparation can never become plausible executable SQL.

## 2. Leap v1 capability matrix

| Area | Leap v1 decision | Required boundary / owner |
| --- | --- | --- |
| XML `<select>/<insert>/<update>/<delete>` | **SUPPORTED** when complete source/dependencies are resolvable | #62 source graph; #64 MyBatis preparation |
| XML `<sql>/<include>` | **SUPPORTED target** including explicit dependency resolution; missing/ambiguous/cyclic dependencies block | #62 + isolated MyBatis mapper parsing in #64 |
| Standard dynamic tags (`if`, `choose/when/otherwise`, `foreach`, `where`, `set`, `trim`, `bind`) | **SUPPORTED** through isolated maintained MyBatis semantics | #64 |
| Unknown/custom XML elements | **UNSUPPORTED** on authoritative execution path; never silently stripped | #62/#64 |
| Java `@Select/@Insert/@Update/@Delete` | **SUPPORTED target** with current-editor authority and complete method-signature identity | #62 |
| Kotlin direct statement annotations | **UNSUPPORTED** in Leap v1 | explicit adapter/product decision required later |
| Provider annotations | **UNSUPPORTED** in Leap v1 | stop before preparation; no speculative runtime provider model |
| `databaseId`-dependent selection | **SUPPORTED target** through stock MyBatis when the effective database id is proven from the selected target/project configuration; unresolved custom mapping blocks only the dependent statement | #62/#64/#65 + #252 |
| Custom language drivers / runtime-only mapper extensions | **UNSUPPORTED** in Leap v1 | future explicit product decision only |
| `#{}` | **SUPPORTED concept** through typed MyBatis binding preparation; Leap execution must preserve ordered bound values and delegate DB execution to Database Tools rather than require a zMyBatis DBMS allowlist | #63/#64/#65 |
| `${}` | **SUPPORTED only as explicit raw interpolation**, separately provenanced and confirmed | #63/#66 |
| Generated aliases / `@Param` / collection aliases | Supported only when provenance is actually established; never guessed solely by naming | #62/#63 |
| Unknown parameter requirements | **UNSUPPORTED for execution** until resolved explicitly | #63 |
| MyBatis/application custom TypeHandler runtime parity | **UNSUPPORTED unless explicitly reproduced/evidenced** | #64 |
| Literal final SQL through Database Tools | Current shipping compatibility behavior only. Zero-binding SQL text remains a DB-neutral transitional artifact; #258 forbids treating that subset as permission for a split production cutover while non-zero binding execution remains unresolved | #64/#65/#66 |
| SELECT | Supported after all source/input/preparation/target contracts pass | #66 orchestration |
| INSERT/UPDATE/DELETE | Supported only with mandatory final-artifact confirmation | #66 |
| Unknown semantic side-effect classification | Blocks; static SQL classification is not authorization | #64/#66 |
| Stable datasource UUID + explicit schema | **SUPPORTED target identity contract** | preserve #57 invariants in #65 |
| Default-schema restart | **UNSUPPORTED** until stable search-path identity can be proven | #65 |
| IntelliJ IDEA Ultimate | target host; maintained claim requires current verifier/runtime evidence | #67 |
| DataGrip | target host; maintained claim requires separate verifier/runtime evidence | #67 |
| Generic other JetBrains hosts | **UNSUPPORTED/UNKNOWN** without maintained matrix | #67 |

## 3. Current shipping implementation is not the target

Current `main` still contains legacy behaviors that Leap will replace. They must not be reclassified as target guarantees merely because they are tested today.

Known replacement targets include:

- `MyBatisExecuteProxyAction` god-object orchestration;
- `MyBatisContextAnalyzer` event-coupled source model;
- `AnnotationSqlExtractor` raw-string extraction boundary;
- regex/keyword-based `ParameterExtractor` as caller-input authority;
- `ParameterInputDialog` semantic/type/history ownership;
- raw-string `ParameterHistoryService` identity/persistence;
- `MyBatisEvaluator` global OGNL mutation, regex transformations, literal rendering, and error-as-SQL behavior;
- v2 persisted-record/live-console-cache lifecycle coupling and startup eager console recreation;
- execution-time formatting mutation;
- `Strict OGNL Mode` / `Ignore Unknown Tags` as execution-safety switches.

Current positive evidence remains useful as regression/falsification evidence during migration, but it does not dictate the target class or package design.

While the legacy shipping action remains authoritative under #258, XML statements containing a real `<include>` dependency are refused before extraction/evaluation/target work. The legacy evaluator does not own authoritative mapper-fragment resolution, and `Ignore Unknown Tags` must not turn an unresolved include into partial executable-looking SQL. This temporary refusal is not a change to the Leap v1 `<sql>/<include>` support target above; final support still requires the #62/#64 source-graph and MyBatis preparation path.

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

Canonical statement/method identity is owned by #62. #63 consumes it for remembered-input isolation; #67 only verifies the final compatibility/evidence contract.

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

Input parsing is independent of Swing. Numeric/decimal/type fidelity is deliberate, not a convenience conversion.

## 7. Remembered inputs and examples

Remembered inputs are convenience, not a correctness requirement.

Leap target rules:

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

## 9. Prepared execution and Database Tools execution

The authoritative core result is structured `PreparedExecution` (or equivalent), not a formatted SQL string.

It carries enough information to prove:

- canonical statement identity and source revisions;
- statement declaration kind;
- MyBatis-produced SQL placeholder structure;
- ordered binding descriptors/values and additional-parameter provenance;
- raw interpolation provenance;
- preparation metadata required by the execution adapter.

The configured IntelliJ/DataGrip datasource owns the DBMS driver, connection/session, vendor SQL semantics, and result handling. **zMyBatis does not define a DBMS support allowlist for an otherwise valid Database Tools datasource.** Oracle, MySQL/MariaDB, PostgreSQL, SQL Server, and other configured datasource families are target-resolution peers.

For standard MyBatis `databaseId` semantics, #252 treats DBMS metadata as **non-gating semantic context only**. The maintained 2026.2 `DbDataSource.dbms` API currently feeds a deliberately narrow fallback matching stock MyBatis `DB_VENDOR` with no translation properties: PostgreSQL → `PostgreSQL` and Oracle → `Oracle`. A datasource with no mapped database id remains a valid target; only a statement whose correctness depends on `databaseId`/`_databaseId` blocks when authority is unavailable. Mapper ids outside those stock product ids are treated as potentially custom/provider-translated `DatabaseIdProvider` mappings and block that dependent statement unless project configuration later proves the mapping. Core selection accepts explicitly proven provider ids rather than hard-coding custom aliases.

For non-zero `#{...}` bindings, Leap requires a maintainable Database Tools parameterized-execution path that can consume the prepared SQL plus ordered values without prompting the user a second time and without losing the native console/result experience.

#251 completed that proof attempt against the maintained 2026.2 baseline and found **NO-GO**: the console/user-parameter subsystem is text-substitution oriented, while the proven session-bound prepared-statement APIs do not expose a maintained bridge back into the native console result/history path. See [Database Tools parameterized execution proof](database-tools-parameterized-execution.md).

#258 resolves the required post-proof product/architecture decision for the maintained 2026.2 baseline: **no fallback is authorized**.

Therefore:

- bound values stay structured and non-zero bindings fail closed with `BOUND_EXECUTION_REQUIRED`; they are not converted into PostgreSQL/Oracle/MySQL-specific executable literals by core;
- the maintained SQL-text materializer admits only the DB-neutral zero-binding case;
- zero-binding `MaterializedExecution` remains a valid artifact, but that subset does not authorize a split production cutover where bound statements silently fall back to the legacy execution path;
- the current legacy action remains the shipping execution authority until a complete replacement execution contract exists; Phase-5 action cutover and legacy evaluator/action deletion remain blocked by the non-zero-binding execution gate;
- previous PostgreSQL literalization slices are historical research evidence, not the authoritative cutover path;
- unsupported/custom TypeHandler semantics remain explicit execution-adapter concerns rather than guessed SQL text;
- console text substitution/second prompting, reflection/internal API bridges, per-DB literalizers, and a zMyBatis-owned custom result UI are not fallback paths under the current contract;
- the parameterized-execution proof is reopened only when the maintained Database Tools baseline exposes relevant new public/maintained capability, or when an explicit separately reviewed product decision changes the native result/history requirement.

## 10. Preview, formatting, copy, and execution identity

The immutable prepared statement identity, ordered bindings, safety state, and exact resolved Database Tools target together form execution authority.

- preview/confirmation must represent the same prepared SQL/binding set that will execute;
- a display-only rendered SQL projection may exist for readability/copy, but it is never substituted back into the execution authority;
- formatting is presentation-only;
- source/target revisions are revalidated before irreversible execution;
- if source or target changed materially after preparation, the execution authority is invalidated and must be re-prepared/re-resolved;
- zero-binding statements may continue to use the immutable `MaterializedExecution` SQL-text artifact because no value literalization is involved.

## 11. Safety posture

### Raw interpolation

`${}` is raw source interpolation, not a normal binding. Its presence and values require explicit user confirmation before execution.

### Mutation

INSERT/UPDATE/DELETE mapper declaration kinds require explicit confirmation of the final immutable execution artifact.

Statement kind is UX evidence, not authorization. If execution meaning is unknown/unsupported, the operation blocks rather than merely asking for confirmation.

### Failure

Source, dependency, input, MyBatis, mapping, materialization, target, console, cancellation, and database failures are typed outcomes. No failure string can flow into the execution API as SQL.

### Side effects

Only the explicit zMyBatis execution action may invoke SQL. Source capture, preparation, preview, formatting, clipboard preparation, settings, history, startup, restoration, and diagnostics do not execute.

## 12. Target/session contract

Preserve the proven #57 safety invariants:

- project-scoped persisted state;
- stable datasource UUID rather than display-name matching;
- explicit named-schema restart identity;
- missing/ambiguous datasource/schema fails closed;
- malformed/interrupted persistence is cleaned safely;
- restoration never executes SQL.

Current v2 persistence already stores string records (`mapperKey`, stable datasource UUID, datasource display name, explicit schema); it does **not** serialize a live `JdbcConsole`. Leap changes the ownership and lifecycle boundary around that persisted identity.

Leap target rules:

- replace the current coupling where persisted-record creation/removal follows live-console cache registration/disposal;
- evolve the record into a versioned `ExecutionTargetDescriptor`/session descriptor that remains independently meaningful and revalidatable without a live console cache entry;
- do not eagerly recreate consoles on startup merely to restore persisted target selection;
- console/editor/document resources are ephemeral Database Tools adapter resources;
- `REUSE`/`NEW_EACH` are optional in-memory resource policies, not target identities;
- default-schema restart remains unsupported until stable search-path identity exists.

## 13. Threading, cancellation, and lifecycle

- `AnAction.update()` remains cheap, bounded, side-effect free, and BGT-compatible.
- action invocation captures IDE context and immediately converts it into adapter/application data; `AnActionEvent` never enters core.
- editor/dialog/popup operations obey EDT requirements.
- index/PSI source work obeys IntelliJ read-action requirements and emits immutable snapshots.
- MyBatis preparation, zero-binding materialization, and bound-execution handoff work are background/cancellable.
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

A small safety patch may remove current INFO leakage before the full Leap cutover without preserving the legacy action architecture.

## 15. IDE compatibility policy

- IDEA Ultimate and DataGrip are evaluated independently.
- Current IDEA Ultimate 2026.2 Plugin Verifier is maintained baseline evidence, not proof of DataGrip or every later IDE.
- Database Tools APIs are isolated behind adapters so platform drift has a bounded replacement surface.
- Public Marketplace/README compatibility claims must match #67 evidence before Leap release.

## 16. Product workflow

1. capture immutable authoritative current-editor source;
2. resolve one exact configured Database Tools target (datasource + schema/search path); DBMS family does not gate target validity;
3. derive target database-id context when mapper semantics require MyBatis `databaseId` / `_databaseId`;
4. resolve the canonical statement variant and complete supported source dependencies for that target context;
5. derive a provenanced input contract;
6. collect and validate explicit user input;
7. prepare through isolated MyBatis semantics, including the proven target database id when required;
8. keep non-zero bindings structured and fail closed until the Database Tools parameterized execution gate is satisfied; zero-binding SQL may cross the DB-neutral immutable text boundary without authorizing a partial production action cutover;
9. revalidate source and target revisions;
10. require mutation/raw confirmation where applicable;
11. execute through the configured Database Tools datasource/session without a zMyBatis DBMS allowlist;
12. present typed result/failure with redacted diagnostics.

## 17. Compatibility settings disposition

Current settings are not automatically product guarantees.

- **Strict OGNL Mode:** remove as a switch controlling fail-closed behavior. Leap execution always fails closed. A future diagnostic-verbosity option must not alter safety semantics.
- **Ignore Unknown Tags:** remove from authoritative execution. Any retained compatibility inspection mode must be explicitly non-executable.
- **SQL Preview:** optional presentation for safe read paths; mandatory confirmation rules override it.
- **Auto-format SQL:** presentation only.
- **Remember Last Inputs:** target default OFF until #63 completes safe retention.
- **Console Session Policy:** may remain as an ephemeral resource policy if #65 proves it worthwhile; it is not target identity.

## 18. Track ownership and closure

- #61 — this product contract. Close after #101 architecture and this document are mutually consistent and merged.
- #62 — source snapshots, dependency graph, canonical XML/Java statement identity.
- #63 — input provenance/codecs/UI/retention.
- #64 — isolated MyBatis preparation, DB-neutral zero-binding materialization, and structured bound-execution handoff.
- #65 — target/session descriptor and Database Tools execution resources.
- #66 — thin IDE action, threading/cancellation/confirmation/user workflow.
- #67 — diagnostics/privacy, host compatibility, performance/resources, migration cleanup, public-claim reconciliation.

#61 does **not** stay open until every legacy quirk is characterized or every downstream implementation is finished. Additional characterization is required only when it can falsify a concrete target decision.

## 19. Non-goals for Leap v1

- Kotlin mapper annotation execution;
- Provider runtime execution;
- arbitrary custom language drivers or runtime-only mapper extensions;
- arbitrary application TypeHandler/runtime configuration emulation;
- generic SQL authorization/classification engine;
- custom JDBC result-grid implementation;
- preserving legacy class/package/settings structure merely for compatibility with our own code.
