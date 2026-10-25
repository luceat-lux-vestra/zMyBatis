# zMyBatis test contracts

The required `Test` CI context is product evidence, not a line-coverage target. `./gradlew check` must fail when one of the automated contracts below regresses. Template fixtures, empty files, debug-only reproductions, and `println` output are not considered evidence. The old IntelliJ template rename test and evaluator debug/reproduction files were removed because they do not own current product behavior.

## Automated contract map

| Correctness boundary | Authoritative evidence | What it proves |
| --- | --- | --- |
| Contract-driven parameter/input boundary | `JavaAnnotationParameterContractFactoryTest`, XML parameter-contract tests, `ContractInputAdapterTest`, `ContractInputSubmissionPolicyTest`, `ContractInputSwingRendererProjectFixtureTest`, `ContractInputHistoryPolicyTest` | requirements and aliases come from source/method provenance; input parsing/validation is independent of Swing; raw interpolation is distinct and non-retainable; unsupported/ambiguous requirements block |
| Java direct-annotation source authority | `JavaAnnotationSourceCaptureAdapterProjectFixtureTest`, `JavaAnnotationSourceCaptureAdapterAdversarialProjectFixtureTest`, `LeapActionContextAdmissionCoordinatorTest` | immutable Java method identity, ordered signature, explicit `@Param`, constants/dependencies, databaseId variants, source revision authority, and typed unsupported cases feed the registered action |
| XML source/dependency authority | `XmlMapperSourceDiscoveryTest`, `XmlStatementSourceGraphResolverTest`, adversarial source-graph tests, `LeapActionContextAdmissionCoordinatorTest` | canonical XML statement identity and reachable `<sql>/<include>` dependencies are complete, source-backed, target-selected where applicable, and fail typed on missing/ambiguous/cyclic/stale authority |
| Isolated MyBatis preparation | `MyBatisPreparationEngineTest`, `XmlMapperPreparationEngineTest`, Boolean-if/foreach preparation suites | admitted source/input contracts run through isolated stock MyBatis semantics, preserving SQL/mapping/additional-parameter evidence without global evaluator state |
| Raw-interpolation confirmation | `LeapExecutionApprovalPolicyTest`, `LeapExecutionApprovalAdapterTest`, `LeapExecutionDeliveryCoordinatorTest`, `ZMyBatisStarterDriverE2ETest` | raw provenance forces final-artifact approval even when ordinary preview is off; cancellation is fail-closed; packaged XML and direct-Java raw composition is exercised |
| Direct Java annotation raw composition | `JavaAnnotationParameterContractFactoryTest`, `MyBatisPreparationEngineTest`, `ContractInputSwingRendererProjectFixtureTest`, `StarterDatabaseE2EIsolationContractTest`, `ZMyBatisStarterDriverE2ETest` | direct Java `@Select` + explicit `@Param` raw input reaches the registered packaged action, remains non-retained, previews exact SQL, cancels without native history, and executes approved SQL through Database Tools |
| Mutation-declaration confirmation | `LeapExecutionApprovalPolicyTest`, `LeapExecutionApprovalAdapterTest`, `LeapExecutionDeliveryCoordinatorTest` | mutation declaration forces approval but is not SQL authorization |
| Registered action boundary | `MyBatisActionUpdateProjectFixtureTest`, `MyBatisActionUpdateBoundaryTest`, `LeapActionContextAdmissionCoordinatorTest` | `MyBatisExecuteAction` keeps update bounded and delegates supported execution only to Leap admission; no low-level Database Tools or alternative execution route is present at the action edge |
| Target persistence and console lifecycle | `PersistedExecutionTargetSelectionTest`, `ExecutionTargetDescriptorStoreTest`, `MyBatisActionInterceptorActivityLifecycleTest`, `ConsoleCacheServiceLifecycleTest`, `ConsoleCacheOwnedResourceDisposalContractTest`, `DatabaseToolsTargetResolverTest`, `DatabaseToolsConsoleAdapterBoundaryTest` | current v3 source→target persistence is independent of live consoles; exact target identity gates REUSE; matching live consoles are reused under source-key ownership; same-source overlap, shutdown, invalid/disposed resources, and stale targets fail closed |
| Sensitive production logging | `SensitiveLoggingContractTest`, action/adapter diagnostic contract tests | production logs reject direct interpolation of known sensitive SQL/input/source/target identities and platform exception messages; typed expected failures expose metadata rather than raw payload |
| Real packaged execution | `ZMyBatisStarterDriverE2ETest` via `E2E / Starter / Driver E2E` | exact packaged plugin executes maintained zero-binding, PostgreSQL BIGINT, XML raw, direct-Java raw, cancellation/history and fail-closed scenarios through the registered action on IDEA Ultimate 2026.2.3 |

## Sensitive logging evidence boundary

PR #117 removed the shipping action's known INFO-level interpolation of resolved parameter values, extracted/dialog values, and rendered SQL. #458 extends that boundary to mapper/source identities, datasource/schema display names, console titles, persisted target-selection identifiers, and platform exception-message detail. `SensitiveLoggingContractTest` follows the production source boundary rather than one class: it scans logging-call windows across `src/main/kotlin` for direct interpolation of the maintained sensitive aliases/object properties and exception-message forms, while explicitly allowing metadata-only `size`/`length`, stage, and failure-type evidence. Expected Database Tools failures retain typed class identity without unused raw detail payloads; genuine fatal throwables remain attached only to fatal diagnostics and are rethrown.

This test is deliberately narrower than a semantic information-flow proof. It can falsify the maintained regression classes and catches those sensitive forms if logging responsibility moves between production Kotlin classes, but it cannot prove safety for arbitrary future aliases, reflection, third-party logging, or runtime-generated sinks. Review remains responsible for applying the product privacy contract to new variables and new logging surfaces.

## Contract-driven input provenance evidence boundary

The shipping input model is no longer a source-text heuristic. Source/method capture produces typed `ParameterContract` requirements and aliases with explicit provenance. Java and XML contract factories distinguish caller inputs, generated aliases only when proven, MyBatis internal values, foreach/bind locals, bound requirements, and raw-interpolation requirements.

`ContractInputAdapterTest`, submission/history policy tests, provenance/presentation tests and the real Swing fixture prove that codecs and validation are independent from presentation, required values fail closed, canonical identity scopes retention, and raw interpolation is never retained. These are current registered-path contracts rather than characterization of a removed extractor/dialog.

## Java direct-annotation capture evidence boundary

`JavaAnnotationSourceCaptureAdapterProjectFixtureTest` and `JavaAnnotationSourceCaptureAdapterAdversarialProjectFixtureTest` exercise the new non-wired Leap Java source adapter in the real Java code-insight sandbox. Capture starts from the immutable active-editor `SourceSnapshot`, commits only the active `Document` into PSI for semantic interpretation, then re-captures and requires the same source revision/content/caret evidence. The unsaved-root fixture proves the resulting SQL comes from the draft `Document` while the physical file remains unchanged and unsaved.

The adapter constructs `JavaStatementId` from source identity, fully qualified mapper type, and ordered canonical parameter type identities, so overloaded methods remain distinct. It preserves direct annotation array order as `sqlSegments` and records declaration-ordered parameter metadata with source name, canonical type identity, and statically proven `@Param` alias. That metadata is evidence for #63 only; this boundary does not infer MyBatis `paramN`/`argN`, collection aliases, placeholder requirements, codecs, UI state, or remembered values.

Compile-time String constants are captured as source dependencies instead of being reduced to unproven text. Same-file and cross-file references become source-backed `SourceDependencyEdge`s, dependent files are captured through the existing live-`Document`-first/VFS-fallback adapter, and an unsaved dependent constant fixture proves the committed PSI value, immutable dependent snapshot, and returned SQL all come from the same draft while disk remains unchanged. Qualified references retain only the outer field reference as dependency evidence so class qualifiers are not misclassified as constants. Unsupported or unprovable constant expressions, dependent-source races/unavailability, and constant dependency cycles fail typed.

The adversarial Java fixtures also prove exact `StatementKind` mapping for all four Leap-v1 direct annotations and typed failures for missing method/direct annotation, unresolved `@Param` alias, unresolved parameter type, anonymous/unqualified mapper identity, provider annotations, custom `@Lang`, `@Select(affectData = ...)`, and unresolved annotation values. #252 promotes standard `databaseId` variants without coupling source capture to target state: repeated direct annotations and explicit `@Select.List` containers are captured as variants first; statement kind is preserved per variant (including cross-kind database-id variants), then `selectJavaAnnotationStatementVariant` applies PostgreSQL/Oracle target authority and rebuilds the selected root statement kind. Missing authority returns `AuthorityUnavailable`, and unknown aliases return `MappingUnproven` rather than silently falling back. `JavaAnnotationSourceCaptureKotlinBoundaryTest` still proves Kotlin direct annotations return explicit `UNSUPPORTED_KOTLIN_SOURCE`. The captured core model retains no IntelliJ objects.

The source-capture boundary by itself does not prove MyBatis preparation or Database Tools execution; those responsibilities are covered by downstream contract and packaged E2E evidence.

## MyBatis preparation evidence boundary

Supported dynamic SQL meaning is delegated to isolated stock MyBatis after source and parameter authority are proven. The Boolean-if, foreach, wrapper/trim, source-graph and mapper-preparation suites compare maintained SQL/mapping behavior with stock MyBatis while preserving strict typed refusals for unsupported composition, stale/forged provenance, unsafe raw structural interpolation, and runtime isolation failures.

There is no compatibility evaluator, unknown-tag stripping mode, OGNL source rewrite, error-as-SQL path, or evaluator-owned generic literalization in the registered execution architecture. Unsupported preparation/materialization returns typed failure before approval or Database Tools delivery.

## Mutation and raw interpolation safety evidence boundary

Raw interpolation is represented explicitly in the parameter contract and `PreparedExecution`; it is never treated as a normal bound value. `LeapExecutionApprovalPolicyTest` and adapter/delivery tests prove that any raw interpolation or INSERT/UPDATE/DELETE declaration forces final-artifact confirmation even when ordinary `sqlPreview` is disabled.

Statement kind remains confirmation evidence, not SQL authorization. The immutable materialized artifact is the only executable payload, cancellation terminates the invocation, and packaged Starter/Driver scenarios prove XML and direct-Java raw confirmation/cancellation behavior against the maintained PostgreSQL Database Tools path.

## Preview and execution boundary

Preview/approval receives the immutable materialized execution artifact. Formatting may derive presentation text but cannot mutate `executionSql`; the Database Tools adapter receives the exact frozen artifact after source/target/lifecycle revalidation.

The maintained Starter/Driver E2E supplies representative full click-throughs for DB-neutral zero-binding, PostgreSQL BIGINT input/materialization/native history, XML raw cancellation, and direct-Java raw cancel/reinvoke/execute. These do not imply broader DBMS/type/DataGrip parity.

## Session and datasource platform gaps

Automated persistence/lifecycle evidence covers only current v3 state. Development-only v2 persistence was never distributed and has no compatibility reader.

The tests prove project-scoped v3 persistence, stable datasource UUID + explicit schema identity, stale-source pruning without console creation, exact target re-resolution, cache target matching, source-key REUSE ownership, terminal lease release, shutdown gating, disposal, and immutable artifact delivery. Structural evidence verifies that the shipping adapter checks a matching cached console before constructing a replacement and carries lease release through the execution terminal callback. Real host evidence is still required when changing JetBrains datasource/schema semantics, DataGrip behavior, or delayed native console lifecycle interactions; these tests do not claim scheduler fairness or DataGrip-specific concurrent-console behavior. Those gaps are explicit so a green `Test` context is not misrepresented as broader platform proof.

## Other gates

`Inspect code`/Qodana and `Verify plugin` remain separate required evidence for static analysis and JetBrains compatibility. They do not substitute for the product assertions in `Test`.

## XML foreach complete-mapper authority evidence

Every admitted XML foreach now requires `XmlMapperPreparationSource.mapperMethod`, the complete
immutable `XmlMapperMethodCapture` supplied independently by the #62 source boundary. A Java snapshot
and matching revision alone cannot authorize foreach preparation. Admission rebuilds the full producer
contract and compares every requirement, alias and internal binding, including caller uses outside the
loop. Hand-written partial alias/type coherence checks no longer define this consumer's authority.

`XmlForeachPreparationAdmissionTest` and `XmlMapperForeachPreparationTest` cover absent capture,
wrong statement/source identity or revision, changed method range, removed caller requirements/aliases/
placeholder evidence, changed caller placeholder inventory, coherent fabricated mapper types/aliases
and stripped foreach authority. Unused mapper parameters still matter: an explicit alias can suppress `paramN`
or shadow item/index locals without becoming a requested input. These failures occur before value
conversion and isolated runtime preparation, including when the collection is empty.

This proves consistency with supplied immutable source captures, not independent Java parsing or
live IDE revision validation. It does not authenticate exact lexical positions of XML placeholders
whose producer evidence has no source range. Multiple captured XML documents remain supported in
this bounded island; all their revisions participate in authority and their content still passes
the token/preflight guards.
Dependency/include resolution and other dynamic composition retain their existing refusals. Existing
stock parity, typed LIST/ARRAY/MAP conversion, generated local capture and concurrent isolation tests
continue to apply with complete mapper capture. Static XML request construction remains available
without it. This paragraph records the lower-level preparation evidence as originally proven; the engine is now packaged and the registered action applies the current bounded zero-binding/PostgreSQL-BIGINT materialization envelope.

E2E applicability: not applicable to this lower-level authority invariant, owned by pure core contract
and engine consumer tests. The shipping plugin does not consume this engine; installed action/UI,
Database Tools, packaging dependencies and workflow policy are unchanged. Any automatically scheduled
Starter/Driver run is shipping-path regression evidence, not foreach production-cutover evidence.

## XML foreach where-wrapper evidence

The source-proven single foreach may be direct or the direct child of one unqualified `<where>`
without attributes, or `<trim prefix="WHERE" prefixOverrides="AND |OR ">` with those two exact
unqualified XML-decoded attributes. Wrapper text may contain static SQL and caller bindings before
and after the loop. The loop must retain the bounded collection/item/index rules: foreach locals
never become caller requirements, and a local outside the loop is rejected. Empty/static-only
wrappers without an admitted foreach, Boolean-if composition, unsupported multiple-wrapper composition,
other dynamic tags, nested or duplicate loops/wrappers, arbitrary
trim attributes, qualified elements, raw interpolation and explicit nullable remain refused.
This is separate from the Boolean-if wrapper island; foreach and if still cannot compose.

`XmlForeachParameterContractTest` and `XmlForeachPreparationAdmissionTest` prove source/mapper alias,
shape and local authority, including explicit/generated/stock collection aliases and wrapper drift
against an older contract. `XmlMapperForeachPreparationTest` compares native and literal trim wrappers
against an independent stock mapper-parser oracle: exact SQL bytes, mapping order, Java/JDBC/handler/
mode/scale metadata and values, with scalar caller bindings outside/inside the wrapper, repeated
item/index mappings, arrays, Map keys/temporal values, null items and empty collections. Tests preserve
statement declaration kind without claiming SQL grammar validity. Stock MyBatis owns WHERE insertion,
override whitespace behavior and omission of an empty wrapper; preparation invents no predicate or
binding. An empty final SQL remains a typed failure.

The shared dynamic bound-token topology guard now runs before all admitted XML foreach evaluation,
including the existing direct form. Incomplete/escaped openers and unsafe fragment endings in source
text or loop open/close/separator attributes cannot synthesize mappings across loop/wrapper boundaries,
even when a collection is empty. Raw structural attributes remain refused by XML preflight. Concurrent
direct/where/trim foreach preparations under a poisoned parent OGNL accessor retain generated local
identity, per-invocation loader ownership and TCCL restoration. These tests remain lower-level preparation evidence only; current production authority is granted separately by the registered action's bounded materialization and delivery chain.

## XML foreach UPDATE SET-wrapper evidence

For UPDATE declarations, the source-proven single foreach may also be the sole dynamic child of
one unqualified attribute-free `<set>` or exact `<trim prefix="SET" suffixOverrides=",">`.
Both trim attributes must be unqualified; XML-decoded character references and attribute ordering
retain the same authority. Static text and caller bindings may surround the loop inside the wrapper
or outside it. Foreach locals remain internal and cannot escape the loop.

`XmlForeachParameterContractTest` and `XmlForeachPreparationAdmissionTest` prove this bounded
source shape and rebuild its complete independent mapper contract. SET foreach in SELECT/INSERT/
DELETE declarations, wrappers without an admitted foreach, modified/extra/qualified attributes, nested/repeated
loops or wrappers, Boolean-if/unsupported multiple-wrapper composition, local escape and other dynamic nodes
remain blocked. Missing complete mapper capture and forged local authority are still typed refusals.

`XmlMapperForeachPreparationTest` compares exact stock SQL bytes, ordered mapping properties,
Java/JDBC/handler/mode/scale metadata and values for native and literal trim SET: empty/single/multiple
collections, null items, repeated item/index mappings, scalar caller bindings before/after the loop,
list/collection/array and generic aliases, and Map keys/temporal values. Comments/CDATA, decoded
attributes and trailing comma/newline/comment cases retain stock trimming. An empty collection
omits SET when the wrapper has no remaining text; no assignment is invented. An empty final SQL
is a typed failure. These assertions preserve UPDATE declaration kind, not SQL grammar validity.

The existing source-bound token topology guard also rejects synthesis/escaping in SET source text
and foreach structural attributes before runtime, including empty collections; raw structural
interpolation and source/wrapper drift are refused. Concurrent direct/WHERE/SET/trim invocations
under poisoned parent OGNL preserve generated local identity, fresh runtime ownership and TCCL.
These lower-level tests do not grant production execution authority by themselves. The engine is now packaged; registered execution still requires the current bounded materializer envelope and exact target/delivery checks.

E2E applicability: not applicable to this pure source-contract/isolated-preparation invariant.
The source factories have no shipping action consumer and the engine remains unpackaged. Existing
Starter/Driver runs provide packaged-plugin regression evidence, not SET foreach execution proof.

## XML foreach combined UPDATE SET/WHERE evidence

UPDATE may combine one set-role wrapper and one where-role wrapper with exactly one direct foreach
inside either role. The other wrapper contains only static text and caller bindings and may be empty.
Both source orders and native/trim combinations retain the exact wrapper attributes above; duplicate
roles, nested wrappers, a second loop, direct loops outside the wrappers, Boolean-if composition,
raw interpolation and foreach locals escaping into the other wrapper remain refused. A statement
with no foreach still needs the separately admitted Boolean-if shape: empty/static-only wrappers
do not acquire authority from this extension.

The source scanner defers the static wrapper's admission until the complete statement proves the
single loop, so a static wrapper before the loop has the same authority as one after it. Complete
mapper capture remains mandatory. Admission rebuilds caller placeholder inventory in both wrappers
and rejects missing/forged caller evidence or source drift before runtime, including empty collections.

`XmlForeachParameterContractTest`, `XmlForeachPreparationAdmissionTest` and
`XmlMapperForeachPreparationTest` cover these positive and negative boundaries. The independent stock
mapper-parser oracle compares unmodified SQL bytes, ordered properties, Java/JDBC/handler/mode/scale
metadata and values across both loop positions and source orders, all maintained native/trim combinations,
XML-decoded/reordered attributes, empty/single/multiple/null items, repeated item/index mappings,
generic/stock collection aliases, arrays and temporal Map values. Comments/CDATA and empty wrappers
retain stock semantics. Empty collections may omit SET, WHERE or both; no assignment/predicate or
SQL grammar validity is invented. Empty final SQL is a typed failure. Source-bound token synthesis
across the two wrappers is rejected even for an empty collection. Concurrent direct/single-wrapper/
combined-wrapper invocations under poisoned parent OGNL retain fresh runtime ownership and TCCL.

E2E applicability: not applicable to this pure source-contract/isolated-preparation invariant by itself. The registered action now consumes these source/preparation boundaries, but production execution still depends on the separately proven materializer, target, approval and Database Tools delivery contracts.

## XML Boolean-if preparation admission evidence

`XmlBooleanIfPreparationAdmission.inspect` consumes an immutable XML source graph, the complete
`XmlMapperMethodCapture` from the source authority boundary, and the proposed `ParameterContract`.
It rebuilds the source and mapper contract rather than accepting a contract's alias evidence as
self-authenticating. The complete capture matters even when some parameters are not requested: an
unused explicit alias can suppress a generated `paramN` alias and change which value a condition reads.

This proves consistency with the supplied authoritative captures; it does not independently parse
Java source to authenticate mapper metadata or revalidate live IDE documents. Those source-capture
and revision responsibilities remain at the #62 boundary. The admitted result snapshots distinct core
condition requirement ids and aliases, with no source/platform object or runtime value retained.

E2E applicability: not applicable to this admission-only slice. The changed invariant is fully owned
by pure source/contract tests in `:mybatis-engine`; installed-plugin wiring, production action/UI,
Database Tools behavior and packaging dependencies are unchanged. The engine's true/false
Boolean-if producer-boundary test still requires typed refusal when complete mapper capture is absent.
The runtime consumer below now consumes admission only when that additional authority is present;
#258 continues to govern execution.

## XML Boolean-if isolated runtime evidence

`XmlMapperPreparationSource.mapperMethod` optionally carries the complete immutable mapper capture.
Its snapshot is automatically included in source-revision authority; an explicitly supplied duplicate
must match both bytes and revision. A conflicting snapshot is rejected rather than silently substituted.
The default source construction remains compatible with the static XML path and continues to refuse
dynamic XML without this capture. Request construction rejects stale mapper revisions before runtime.

For the admitted flat Boolean-if island (direct siblings or siblings inside bounded direct
`<where>` / UPDATE `<set>` wrappers and the exact trim forms below),
`XmlMapperPreparationEngine` owns a fresh classloader and Configuration, delegates evaluation to
stock MyBatis, restores the thread context loader and closes
the invocation loader. No MyBatis object or mutable runtime state survives in `PreparedExecution`.
Missing/invalid required caller roots remain failures even when the condition is false. Empty rendered SQL
and ordinary mapping errors return typed failure; reflection-wrapped cancellation and every non-Exception
fatal failure propagate with original object identity. Tests poison the parent OGNL accessor and parser
length limit and run concurrent true/false invocations to falsify isolation regressions.

The static path retains exact whole-source mapping cardinality. The conditional path first applies the
existing DOM/token-parser topology guard to source fragments: incomplete/escaped openers and unsafe
fragment endings cannot synthesize new mappings at node boundaries. Each emitted mapping must resolve
to an exact source-proven caller input. Each XML placeholder carries its immediate Boolean condition
alias in `enclosingOgnlExpression`, or null for unconditional source text; this metadata grants no
runtime authority by itself. Admission rebuilds it from captured source, so moved/missing/forged
scope cannot authenticate itself, even when aliases and total occurrence counts are unchanged.

For every true/false combination, mapping cardinality must exactly match the per-property counts of
unconditional occurrences plus occurrences whose proven flat Boolean guard is true. An inactive
branch cannot excuse losing an unconditional mapping or adding an inactive mapping. This is a
provenance check over typed Boolean values, not an OGNL evaluator or SQL renderer: stock MyBatis
continues to own condition evaluation, SQL bytes, order and WHERE/SET trimming semantics.

The bounded `<where>` wrapper contains one or more direct Boolean-if siblings and may also contain static
text and bound placeholders before or after it. It accepts no attributes or namespace-qualified
name. UPDATE may also carry the single set wrapper described below. Static-only/empty where
nodes, a second where, an if outside the wrappers, nested where/if,
mixed foreach/if, bind/include/other tags and raw interpolation remain typed refusals before runtime, even
when the supplied condition is false. Comments and CDATA cannot invent a conditional caller.

Stock MyBatis owns `WHERE` insertion, leading `AND`/`OR` trimming and omission of an empty where.
For example, `SELECT 1 <where><if test="enabled">AND id = #{id}</if></where>` produces
`SELECT 1 WHERE id = ?` when true and `SELECT 1` when false (whitespace normalized here only for
documentation). Tests compare the unmodified SQL bytes, mapping order and Java/JDBC/handler/mode/
scale metadata with an independent stock MyBatis mapper-parser oracle, including newline/tab/case
variants and identifiers such as `ANDROID` that must not lose their leading letters. Parent OGNL
poisoning and concurrent preparation tests exercise both direct and where-wrapped conditions.
`XmlSiblingBooleanIfPreparationTest` checks all eight combinations of three independent Boolean
conditions against stock MyBatis, repeated explicit/generic guards and Map missing/null leaves.
Concurrent invocations also interleave this Boolean path with the merged source-proven foreach
path; both own fresh loaders that bypass shared JAR-resource caching and restore each thread's
context loader. This proves coexistence of the two preparation islands, not support for combining
foreach and if in one XML statement.
The existing topology guard additionally rejects token synthesis/escaping across the new where
boundaries; missing active Map leaves remain failures, while a present-null leaf remains null.

The bounded UPDATE `<set>` island uses the same complete mapper admission and condition-scoped
mapping proof. One unqualified attribute-free direct `<set>` must contain one or more direct
simple Boolean-if siblings; unconditional text/bindings may occur before, within or after it.
`XmlBooleanIfParameterContractTest` and `XmlBooleanIfPreparationAdmissionTest` cover source/type/alias
and moved-placeholder authority. SELECT/INSERT/DELETE declarations with set, empty/static-only set,
nested/repeated wrappers, direct conditions outside the wrappers, raw interpolation and
other dynamic tags remain refused without partial caller authority.

`XmlSiblingBooleanIfPreparationTest` compares all eight combinations of three UPDATE conditions,
with and without a static assignment, against stock SQL bytes and ordered mapping metadata/values.
Stock MyBatis owns SET insertion and trailing-comma removal. All-false conditions with no static
assignment omit SET, preserving stock SQL even when that SQL is not a valid UPDATE; preparation is
not SQL grammar validation and adds no synthetic assignment. Empty prepared SQL remains a typed
failure. Missing active Map leaves, absent complete mapper capture, forged/stale source provenance
and token synthesis across set boundaries remain typed refusals. Concurrent set/where/foreach
invocations under poisoned parent OGNL exercise isolation and TCCL restoration. This fixture adds no execution authority by itself; production admission remains constrained by the registered action's bounded materializer envelope.

UPDATE may compose one direct `<set>` and one direct `<where>`. Each wrapper independently
requires at least one simple Boolean-if child; conditions in the first wrapper cannot authorize
an empty/static-only second wrapper. The source scanner retains immediate guard provenance in
source order across both wrappers, including repeated explicit/generic aliases. Admission rebuilds
the complete source/mapper contract before isolated runtime preparation; moving an unconditional
mapping into a condition or changing the second guard's mapper type cannot reuse old authority.
Both source orders are admitted: preparation delegates SQL grammar and trimming to MyBatis.

The core and admission tests retain duplicate/nested/attribute/namespace/raw/other-tag refusals
and the SELECT/INSERT/DELETE set refusal. `XmlSiblingBooleanIfPreparationTest` compares all eight
combinations of three guards, with and without static assignments/predicates, against the stock
mapper-parser oracle's SQL bytes and ordered metadata. A shared guard in both wrappers contributes
both proven occurrences when true. Tests additionally cover generic/repeated guards, comments/CDATA,
both source orders, Map present-null/missing active leaves, all-false/empty SQL, cross-wrapper token
synthesis and concurrent set/where/combined/foreach invocations under poisoned parent OGNL.
All-false conditions may omit SET, WHERE, or both; no assignment, predicate, or SQL-validity claim
is invented. #258 and the standalone, unpackaged engine boundary remain unchanged.

Two literal `<trim>` forms share these wrapper limits:

- `<trim prefix="WHERE" prefixOverrides="AND |OR ">` counts as the single where wrapper;
- `<trim prefix="SET" suffixOverrides=",">` counts as the single set wrapper and requires UPDATE.

Both attributes must be unqualified, with those exact XML-decoded values and no other attributes.
The element must be unqualified. In the Boolean-if island each trim requires a direct simple
Boolean-if child; the separate foreach island above permits one direct foreach inside WHERE trim or,
for UPDATE, SET trim, optionally with a static wrapper of the other role. Native and
trim forms may compose for UPDATE, but two wrappers of the same role are rejected even when their
element names differ. Empty/static-only trim, nested/duplicate/outside-condition structures, arbitrary
prefix/suffix/override values, property-substituted or bound attributes, raw interpolation and other
dynamic tags remain refused before runtime. XML character references and attribute ordering do not
alter the decoded authority; no attributes are rewritten into native where/set tags.

The core/admission tests cover mapper caller/type/scope authority and these refusals.
`XmlSiblingBooleanIfPreparationTest` compares native/native, native/trim, trim/native and trim/trim
UPDATE combinations across all eight guard combinations against stock SQL bytes, mapping order,
metadata and values. Additional cases cover generic/repeated guards, comments/CDATA, decoded
attributes, SET comma removal, all-false WHERE/SET omission, empty SQL, Map missing/null leaves,
absent complete mapper capture and source-bound token topology. Concurrent trim/native/foreach
preparations under poisoned parent OGNL retain per-invocation isolation and TCCL restoration.

The literal trim override string stays semantically distinct from native `<where>` defaults:
for example `AND` followed by a newline is not stripped by the admitted `AND ` override token.
Stock MyBatis owns that result; preparation does not normalize whitespace or supply default
override tokens. Native where/set remain attribute-free and general trim is unsupported. Production executability is decided later by the registered action's bounded materializer envelope rather than by this preparation fixture.

An independent stock-MyBatis oracle checks byte-identical SQL and metadata. In this named-Map runtime
path, a declared Boolean input can legitimately retain `Object` mapping type and `UnknownTypeHandler`;
preparation captures that stock result separately from declared input type rather than inventing a
Boolean handler or claiming JDBC parity. Named Map leaf reads remain presence-aware in emitted
mappings: present-null binds as null, a missing active leaf fails, and an inactive leaf does not become
an invented caller requirement. The materializer still refuses every non-zero binding.

E2E applicability: not applicable to this lower-level preparation slice. Core request authority and
standalone engine semantics change, while plugin dependency wiring, IDE actions/UI and Database Tools
execution remain unchanged. Real IU/DataGrip lifecycle and final production cutover remain #67/#258
obligations; these pure tests do not prove them.

## IDEA 2026.2.3 compatibility and console lifetime authority

The build and Starter/Driver IDE are pinned to IDEA Ultimate 2026.2.3 on JDK 25.
Plugin Verifier checks that target plus 2026.2, preserving evidence for the unchanged minimum build 262.
Historical #251 Database Tools parameterized-execution probes remain evidence from 2026.2;
this patch-baseline change does not rewrite those experiments or authorize a #258 execution fallback.

`DatabaseToolsConsoleAdapter` uses public `JdbcConsole.isValid()` before resource access, after
editor-open scheduling and immediately before native query invocation. The maintained API includes
its own disposal flag and view/target validity. Cache cleanup and rejected-acquisition cleanup use
resource-parented `CheckedDisposable` flags, so an invalid console whose children still need disposal
is not mistaken for an already-disposed resource. Production no longer relies on deprecated
`Disposer.isDisposed(Disposable)` and its clearable diagnostic history.

`ConsoleCacheServiceLifecycleTest` creates real Database Tools consoles in the IU platform fixture.
It proves that disposal invalidates the resource flag/cache and yields typed `ConsoleUnavailable`
without execution; it also invalidates a parent before child disposal and proves eviction still
cleans the owned children. The existing structural tests retain checks at asynchronous/native query
boundaries, and the resource-disposal policy tests retain cancellation/fatal failure ordering.
These fixtures do not prove every disposed editor, delayed UI callback or DataGrip runtime path;
those broader lifecycle obligations remain #67.

E2E applicability: the maintained packaged-plugin happy path, mandatory-preview cancellation paths
and no-datasource refusal apply on the exact final HEAD because the platform and production console
validity checks changed. Their IDE/artifact manifest must name 2026.2.3 and the tested final commit.

Unit/PSI fixture JVMs explicitly run headless so macOS desktop registration does not
prevent their execution; Starter/Driver remains a separate real UI process.
The unit fixture sandbox excludes only the unrelated `com.intellij.modules.ultimate`
startup wrapper (alongside the already documented Vue exclusion in Java/Kotlin fixtures).
In 2026.2.3 its `Z.Z.Z.Z.Z` activity collides with a different platform class when
Gradle flattens the SDK classpath; all 37 Java fixtures otherwise fail before their
product assertions run. Database Tools and Java remain enabled, every fixture/assertion
and strict test-logger check remains present. This unit-fixture profile does not prove
licensed Ultimate activation/startup. Packaged process-level E2E first starts without a
project and completes the normal supplied-license/trial activation before restarting
that same isolated config with the test project. 2026.2.3 initially disables Ultimate
without a license and its normal trial activation dynamically
re-enables it: observed scheme reloads raced project-tree color reads and raised
`ConcurrentModificationException` before the product assertions. Completing activation
before opening the project avoids that overlap; the normal license/trial flow and strict
exception checks remain active, with no new error exemption or retry. Broader licensing,
dynamic-plugin and DataGrip evidence remains owned by #67.
Platform evidence owner #67 must remove this isolation when the SDK/test loader can
preserve the two namespaces. The Java annotation and XML mapper-method fixtures permit
VFS access only to their actual MyBatis dependency JAR, scoped to each fixture disposable, so they also work
when the checkout/sandbox is outside the user's home directory.
