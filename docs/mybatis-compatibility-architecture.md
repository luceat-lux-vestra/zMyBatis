# zMyBatis 1.0 MyBatis-compatibility architecture

Status: **TARGET BASELINE — pre-implementation**

Authority: issue #512 and documentation work unit #513.

Baseline recorded from protected `main` `402ee67d48123235828a2ef2bc1642c7000e53a2`.

This document defines the target architecture that must be proven before zMyBatis 1.0.
It supersedes the bounded SQL-text/materializer target from the 2026-10 Leap rebaseline wherever
the two conflict. It does **not** claim that the current registered action already implements this
target.

Historical documents such as [Leap execution rebaseline](leap-execution-rebaseline.md) and the
Database Tools parameterized-execution investigation remain evidence about decisions and observed
platform APIs. They are not preservation constraints for the pre-1.0 target.

## 1. Product objective

zMyBatis 1.0 must execute ordinary MyBatis mapper SQL without requiring users to rewrite a mapper
into a zMyBatis-specific dynamic-SQL or binding subset.

Required 1.0 capability includes:

- XML `select`, `insert`, `update`, and `delete`;
- mapper files that contain ordinary `resultMap`, `resultType`, and `parameterType`
  declarations when those declarations do not require arbitrary application-runtime execution;
- same-namespace and cross-namespace `<sql>/<include>`;
- standard MyBatis dynamic SQL: `if`, `choose/when/otherwise`, `foreach`, `where`, `set`,
  `trim`, and `bind`;
- mapper-method-backed `@Param`, multiple arguments, scalar values, List/Collection/array, Map, and
  ordinary parameter-object property paths;
- XML-only mapper execution through an explicit parameter-object/environment input;
- multiple ordered bound values and MyBatis additional parameters;
- common stock MyBatis/JDBC scalar binding;
- direct Java `@Select/@Insert/@Update/@Delete`;
- explicit raw `${}` safety/approval;
- mutation confirmation;
- `databaseId` authority;
- stable Database Tools datasource UUID plus explicit schema;
- restart/stale-target/cancellation/disposal safety;
- packaged IntelliJ IDEA evidence and a repository-owned maintainer acceptance project.

Explicitly outside 1.0 unless promoted by separate evidence:

- Provider annotations;
- Kotlin direct statement annotations;
- custom LanguageDriver behavior;
- application custom TypeHandler or runtime-plugin semantics that require arbitrary application code
  loading/execution;
- arbitrary application runtime configuration parity;
- native Database Tools result/history parity for the prepared backend;
- mapper behavior whose correctness fundamentally requires executing application code that zMyBatis
  cannot safely reconstruct.

Unsupported behavior must fail typed. It must never silently become approximate executable SQL.

## 2. Architecture invariants

The following Leap boundaries remain protected:

1. current editor content is authoritative;
2. canonical statement identity is explicit;
3. source dependencies are explicit and immutable before preparation;
4. stock MyBatis owns semantics claimed as MyBatis-compatible;
5. zMyBatis does not maintain a second OGNL/dynamic-SQL language;
6. caller-input provenance is distinct from MyBatis evaluation semantics;
7. `#{}` and `${}` remain distinct through every layer;
8. preparation, preview, formatting, persistence, and cancellation do not execute SQL;
9. execution target identity is stable datasource UUID plus explicit schema;
10. wrong-target execution is worse than refusal;
11. approval and immediate revalidation apply to one immutable execution plan;
12. cancellation/disposal invalidates execution authority;
13. raw inputs, final SQL, credentials, and source/target identities are not normal diagnostic
    payloads;
14. one orchestration authority does not require one physical execution backend;
15. no legacy/current semantic fallback may coexist silently.

## 3. Target flow

```text
IDE action
  -> SourceAuthority
       current SourceSnapshot
       canonical StatementId
       reachable SourceGraph
       MapperMethodSignature when available
  -> InvocationContract
       caller input authority
       raw interpolation risk evidence
       no dynamic-SQL grammar admission
  -> MyBatisPreparationProjection
       selected statement
       reachable sql/include fragments
       databaseId authority
       required parameter semantics
       standard dynamic tags unchanged
       unrelated result/cache metadata excluded from blocking authority
  -> IsolatedMyBatisEngine
       stock MyBatis
       BoundSql
       ordered ParameterMappings
       additional parameter values
       effective JDBC/TypeHandler metadata
  -> PreparedExecutionPlan
       immutable SQL-with-placeholders
       ordered typed bindings
       raw interpolation provenance/result
       source revisions
       target identity
       statement kind
       safety flags
       fingerprint
  -> Approval + immediate source/target/lifecycle revalidation
  -> ExecutionBackend
       bound -> DatabaseSessionPreparedBackend
       exact text / zero binding -> NativeConsoleSqlTextBackend
  -> ResultPresentation
       prepared backend -> bounded zMyBatis result adapter
       native text backend -> Database Tools native result/history
```

## 4. Source authority

Keep the current unsaved-editor authority, canonical XML/Java identities, source revisions, explicit
reachable include graph, and target-derived `databaseId` authority.

A dependency graph existing is not itself an input-contract failure. A parameterized statement that
uses `<sql>/<include>` must proceed when the graph is complete and unambiguous.

Source analysis may determine:

- statement identity;
- dependency edges;
- databaseId variants;
- raw `${}` presence/provenance;
- application-runtime-only constructs.

Source analysis must not decide whether a valid standard MyBatis dynamic program is allowed through a
zMyBatis syntax whitelist.

## 5. Invocation and input authority

### Mapper-method-backed XML

When the matching Java mapper method is available, the method signature and `@Param` metadata are the
primary caller-input authority. Declared scalar/container/object shapes form the input contract.
Stock MyBatis resolves dynamic expressions, property traversal, foreach/bind locals and additional
parameters.

### XML-only mapper

A Java mapper method is not mandatory.

For XML-only execution, zMyBatis accepts one explicit parameter object/environment. A JSON/object-tree
editor is an acceptable representation. Lexical placeholder/root discovery may assist the UI but is
not semantic authority, and zMyBatis must not invent a fake Java signature from placeholder names.

Raw interpolation remains separately classified for confirmation and retention policy.

### Direct Java annotations

The real Java method signature and `@Param` metadata are caller authority. Dynamic `<script>`
content is evaluated by stock MyBatis rather than a zMyBatis dynamic-language admission table.

## 6. MyBatis preparation projection

The current whole-document dangerous-attribute/element preflight is a migration implementation, not
the target architecture.

The target uses a structural, statement-scoped preparation projection containing only semantics
required to produce the selected statement's `BoundSql`:

- mapper namespace;
- selected statement;
- reachable `<sql>` fragments;
- target-selected databaseId variants;
- standard dynamic tags unchanged;
- binding metadata that actually affects parameter semantics.

Classify mapper declarations by semantic effect.

### SQL-generation irrelevant

Examples include ordinary result mapping and cache metadata. Their presence elsewhere in a mapper
must not block unrelated SQL generation.

### Parameter-semantics relevant

Examples include `parameterType`, `javaType`, `jdbcType`, and TypeHandler identity. Preserve
relevant information when safely reconstructable.

### Application-runtime or execution-semantic

Examples include custom LanguageDriver, custom/application TypeHandler, `selectKey`, and arbitrary
runtime plugins/configuration. These require explicit capability decisions and must not be collapsed
into one broad regex refusal with harmless result metadata.

The projection must be structural. Do not strip arbitrary unknown XML with regular expressions.

## 7. MyBatis semantic authority

Stock MyBatis owns:

- conditional evaluation;
- choose branching;
- foreach expansion;
- where/set/trim behavior;
- bind evaluation;
- additional parameter creation;
- property traversal;
- standard dynamic-SQL composition.

The following current mechanisms are migration implementation rather than target design:

- dynamic grammar restrictions in `XmlStatementParameterContractFactory`;
- Boolean-if admission islands;
- foreach admission islands;
- `DynamicOgnlAdmission`;
- `ForeachProvenanceAdmission`;
- fixed lexical UPDATE/WHERE/trim allowlists.

After replacement parity exists, these responsibilities must either shrink to provenance/safety only
or be deleted. Do not retain two semantic authorities.

## 8. PreparedExecutionPlan

`PreparedExecution` remains a useful structured preparation result. Final execution authority is an
immutable `PreparedExecutionPlan` containing at least:

```text
statementId
statementKind
sourceRevisions
executionTargetId
sqlWithPlaceholders
orderedBindings
  property/provenance
  actual resolved value
  additional-parameter identity where applicable
  effective Java/JDBC type
  TypeHandler identity where available
rawInterpolations
databaseId
safetyFlags
fingerprint
```

For prepared execution, preview/execution identity is not literalized SQL equality. The immutable
meaning is SQL-with-placeholders plus ordered typed bindings, raw interpolation result/provenance,
source revisions, target and statement kind.

The UI may show SQL plus a typed parameter table. The exact approved plan is what execution consumes.

## 9. Execution backends

### DatabaseSessionPreparedBackend

This is the target primary backend for statements with JDBC bindings.

Requirements:

- reuse the configured Database Tools datasource/session; do not duplicate credentials;
- execute a true prepared statement;
- preserve ordered bindings and MyBatis additional parameters;
- preserve exact datasource/schema authority;
- expose structured rows, update counts and database errors;
- own cancellation/disposal explicitly;
- do not require SQL literalization or native Database Tools result/history integration.

Binding should prefer stock MyBatis standard ParameterMapping/TypeHandler/JDBC semantics. Do not
replace the SQL literalizer with a new hand-written `setLong/setString/...` matrix as the default
architecture.

Custom/application TypeHandlers remain outside the positive envelope unless their runtime authority is
safely available.

#### P3 stop gate

This backend remains a design hypothesis until proven against maintained IntelliJ/Database Tools APIs.

If reliable session/connection ownership, prepared execution, cancellation, disposal and target
correctness cannot be achieved without unstable internal/reflection contracts, stop the migration and
reconsider the execution target before implementing more parity syntax.

### NativeConsoleSqlTextBackend

Keep the current Database Tools console path for:

- zero-binding statements;
- exact raw/text statements with no remaining JDBC bindings;
- optionally, separately proven materialized compatibility slices.

This is an explicit backend, not a silent fallback.

The current PostgreSQL BIGINT single-binding materializer is not the universal target architecture.
It may survive only as an optional bounded native-console compatibility path if still useful after
prepared execution exists.

## 10. Result presentation

Native Database Tools result/history parity is not a correctness gate for the bound prepared backend.

A bounded zMyBatis result adapter may own:

- column metadata;
- rows;
- update counts;
- database error presentation;
- cancellation state.

It must not recreate ORM/application result mapping.

Exact-text/native-console execution continues to use Database Tools result/history.

## 11. Approval and revalidation

Keep current safety strength.

Raw `${}`:

- remains a distinct input kind;
- is non-retainable by default;
- requires explicit acknowledgement and final plan approval.

Mutations:

- INSERT/UPDATE/DELETE require explicit final approval regardless of ordinary preview setting.

Immediately before irreversible execution, revalidate project/invocation lifecycle, source revisions,
exact datasource UUID, explicit schema, and execution-plan identity.

## 12. Evidence strategy

Automation follows this architecture; it does not define it.

The next work unit after this baseline is recorded is a stock-MyBatis parity oracle corpus. The oracle
must freeze expected stock MyBatis behavior for realistic mappers, including:

- ordinary `resultMap/resultType/parameterType` shapes;
- same/cross/nested include graphs and parameterized include;
- every standard dynamic-tag family plus representative real combinations;
- scalar, multi-parameter, object, map, list, collection and array inputs;
- nested properties and foreach additional parameters;
- typed null;
- direct Java annotation dynamic scripts;
- raw interpolation;
- sanitized historical `mybatisTest` Product/Customer-style mappers.

The oracle records expected `BoundSql`, ordered mappings, additional values, databaseId selection,
and relevant standard binding metadata directly from stock MyBatis. It must not encode current Leap
refusals as the desired contract.

Each implementation phase adds zMyBatis-vs-oracle parity for the slice it replaces.

## 13. Required work order

```text
D0  record this baseline in authoritative repository docs
  -> D1  stock MyBatis parity oracle
  -> P1  statement-scoped preparation projection
  -> P2  invocation/input contract replacement
  -> P3  prepared-backend spike and hard stop gate
  -> P4  unified execution-plan cutover
  -> P5  packaged + manual parity
  -> P6  superseded admission/preflight/materializer cleanup
```

No isolated #509/#510 syntax patch may bypass D1.

Do not pre-create all phase PRs. Activate one bounded work unit only after the previous gate closes.

## 14. Regression ownership

- #509 is standard dynamic-tag parity regression evidence.
- #510 is common XML mapper-declaration parity regression evidence.
- #508/#511 provides the repository-owned manual release-validation harness.
- #251 remains historical evidence about Database Tools prepared-execution/API tradeoffs.
- #391 remains historical evidence for the 2026-10 SQL-text rebaseline, but its bounded materializer
  target is superseded by this document for pre-1.0 parity recovery where the two conflict.

## 15. Architecture-ready definition

Runtime implementation may begin only after:

1. this target is represented without contradiction in product, architecture, test and engineering
   documentation;
2. the 1.0 required/unsupported capability boundary is explicit;
3. stock MyBatis semantic ownership is unambiguous;
4. mapper-method-backed and XML-only input authority are both defined;
5. statement-scoped preparation projection responsibility is defined;
6. immutable execution-plan identity is defined;
7. prepared/native backend responsibilities are defined;
8. the P3 prepared-backend stop gate remains explicit;
9. superseded Leap admission/preflight/materializer deletion targets are explicit.

After D0, D1 adds the parity oracle. D1 still makes no production runtime change.
