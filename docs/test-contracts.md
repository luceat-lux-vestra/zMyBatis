# zMyBatis test contracts

The required `Test` CI context is product evidence, not a line-coverage target. `./gradlew check`
must fail when a maintained automated contract regresses.

This document distinguishes **current shipping evidence** from the
[1.0 MyBatis-compatibility target](mybatis-compatibility-architecture.md). A green current refusal
test proves that the current Leap path fails closed; it does not by itself prove that the same
refusal is desired 1.0 behavior.

Template fixtures, empty files, debug-only reproductions, and `println` output are not considered
evidence. The old IntelliJ template rename test and evaluator debug/reproduction files were removed because they do not own current product behavior.

## Current shipping automated contract map

| Correctness boundary | Authoritative evidence | What it proves |
| --- | --- | --- |
| Caller-authority parameter/input boundary | `P2CallerAuthorityParityTest`, `P2RegisteredPathParityTest`, `LeapExecutionInvocationCoordinatorTest`, `LeapExecutionCandidateCoordinatorTest`, `ContractInputAdapterTest`, `ContractInputSubmissionPolicyTest`, `ContractInputSwingRendererProjectFixtureTest`, `XmlParameterObjectInputSupportTest`, `XmlParameterObjectInputEditorProjectFixtureTest`, `ContractInputHistoryPolicyTest` | mapper method metadata or the explicit XML-only parameter object owns caller requirements; stock-compatible aliases/parameter objects are built independently of dynamic SQL syntax; XML-only source provenance may project confidently observed dotted caller paths into convenient Fields and an editable generated JSON template without changing the single-object core contract or promoting `<bind>` / foreach locals to caller input; bound/raw usage is separate from caller value type; raw-used input remains non-retainable |
| Java direct-annotation source authority | `JavaAnnotationSourceCaptureAdapterProjectFixtureTest`, `JavaAnnotationSourceCaptureAdapterAdversarialProjectFixtureTest`, `LeapActionContextAdmissionCoordinatorTest`, `ZMyBatisStarterDriverE2ETest` | immutable Java method identity, ordered signature, explicit `@Param`, constants/dependencies and databaseId variants feed the registered action; Provider annotations remain explicitly unsupported and the packaged action presents that refusal |
| XML source/dependency authority | `XmlMapperSourceDiscoveryTest`, `XmlStatementSourceGraphResolverTest`, adversarial source-graph tests, `LeapActionContextAdmissionCoordinatorTest` | canonical XML statement identity and reachable `<sql>/<include>` dependencies are complete, source-backed, target-selected where applicable, and fail typed on missing/ambiguous/cyclic/stale authority |
| Isolated MyBatis preparation | `P2RegisteredPathParityTest`, `P2OgnlSafetyAdmissionTest`, `MyBatisPreparationEngineTest`, `XmlMapperPreparationEngineTest`, `XmlMapperPreparationProjectionParityTest`, `XmlSiblingBooleanIfPreparationTest`, `XmlMapperForeachPreparationTest` | selected Java/XML statements prepare through stock MyBatis after caller authority and capability-only safety checks; standard if/choose/foreach/where/set/trim/bind semantics are not gated by syntax-specific admission owners; source projection, topology, runtime isolation and typed capability failures remain enforced |
| Stock MyBatis parity oracle and registered P2 parity | `StockMyBatisParityOracleTest`, `P2CallerAuthorityParityTest`, `P2RegisteredPathParityTest` + `mybatis-engine/src/test/resources/parity/**` | the frozen stock oracle defines expected MyBatis 3.6.0 semantics, while P2 registered-path tests prove caller-object construction and standard dynamic-tag execution now reach the maintained Java/XML preparation paths without a second zMyBatis semantic grammar |
| Raw-interpolation confirmation | `LeapExecutionApprovalPolicyTest`, `LeapExecutionApprovalAdapterTest`, `LeapExecutionDeliveryCoordinatorTest`, `ZMyBatisStarterDriverE2ETest` | raw provenance forces final-artifact approval even when ordinary preview is off; cancellation is fail-closed; packaged XML and direct-Java raw composition is exercised |
| Direct Java annotation raw composition | `P2CallerAuthorityParityTest`, `MyBatisPreparationEngineTest`, `LeapExecutionInvocationCoordinatorTest`, `ContractInputSwingRendererProjectFixtureTest`, `StarterDatabaseE2EIsolationContractTest`, `ZMyBatisStarterDriverE2ETest` | direct Java `@Select` / dynamic `<script>` uses mapper-signature caller authority; one String value may participate in both bound and raw usage; raw use remains non-retained and approval-gated before Database Tools delivery |
| Mutation-declaration confirmation | `LeapExecutionApprovalPolicyTest`, `LeapExecutionApprovalAdapterTest`, `LeapExecutionDeliveryCoordinatorTest`, `ZMyBatisStarterDriverE2ETest` | mutation declaration forces approval but is not SQL authorization; packaged IDEA proves SQL Preview OFF cannot bypass final-plan confirmation and cancellation prevents execution |
| Registered action boundary | `MyBatisActionUpdateProjectFixtureTest`, `MyBatisActionUpdateBoundaryTest`, `LeapActionContextAdmissionCoordinatorTest` | `MyBatisExecuteAction` keeps update bounded and delegates supported execution only to Leap admission; no low-level Database Tools or alternative execution route is present at the action edge |
| Target persistence, databaseId and console lifecycle | `PersistedExecutionTargetSelectionTest`, `ExecutionTargetDescriptorStoreTest`, `MyBatisActionInterceptorActivityLifecycleTest`, `ConsoleCacheServiceLifecycleTest`, `ConsoleCacheOwnedResourceDisposalContractTest`, `DatabaseToolsTargetResolverTest`, `DatabaseToolsConsoleAdapterBoundaryTest`, `ZMyBatisStarterDriverE2ETest` | current v3 source→target persistence is independent of live consoles; exact target identity gates REUSE; packaged PostgreSQL selection derives the PostgreSQL databaseId variant rather than the default; same-source overlap, shutdown, invalid/disposed resources, and stale targets fail closed |
| Sensitive production logging | `SensitiveLoggingContractTest`, action/adapter diagnostic contract tests | production logs reject direct interpolation of known sensitive SQL/input/source/target identities and platform exception messages; typed expected failures expose metadata rather than raw payload |
| Real packaged execution | `ZMyBatisStarterDriverE2ETest` via `E2E / Starter / Driver E2E` | exact packaged plugin must execute the registered zero-binding native route, multi-binding prepared route + prepared-result presentation, XML/direct-Java raw approval behavior, and maintained fail-closed/lifecycle scenarios on IDEA Ultimate 2026.3; P5 adds representative promoted parity acceptance before release sign-off (exact-HEAD evidence required) |

## Pre-1.0 parity-recovery evidence

D1 establishes a **stock MyBatis parity oracle corpus** with no production runtime change.
`StockMyBatisParityOracleTest` derives expected behavior directly from stock MyBatis 3.6.0 and
freezes:

- final `BoundSql.sql`;
- ordered `ParameterMapping` properties;
- dynamic/internal binding values, including MyBatis 3.6 mapping-captured values and `BoundSql` additional parameters;
- target-selected databaseId behavior;
- relevant standard TypeHandler/JDBC metadata;
- stock `ParamNameResolver` caller-object construction for mapper-method inputs.

The corpus covers ordinary `resultMap/resultType/parameterType` mappers, same/cross/nested
include graphs, explicit load-time parameterized `<include><property .../></include>` substitution,
databaseId fragment selection, every standard dynamic-tag family, representative combinations,
scalar/multi/object/map/list/collection/array inputs, nested properties, foreach/bind dynamic
internal values, typed null, direct Java dynamic annotations, runtime raw interpolation, and
sanitized source-derived historical `mybatisTest` Product/Customer mapper shapes.

The parameterized-include oracle deliberately passes colliding runtime parameter names and proves
that stock MyBatis has already substituted the include property into `BoundSql.sql` with no normal
`ParameterMapping`. That load-time mapper feature is distinct from caller-controlled runtime
`${}` raw interpolation.

The oracle deliberately does **not** encode historical Leap refusal as target truth. P1 added
`XmlMapperPreparationProjectionParityTest` for structural selected-statement/reachable-fragment
projection. P2 now extends that frozen oracle into the registered invocation/input authority:
`P2CallerAuthorityParityTest`, `P2RegisteredPathParityTest` and the Java/XML engine suites prove
mapper-signature / explicit XML-object caller authority and stock dynamic-SQL execution. P3/P4 then
promoted the maintained prepared Database Tools backend and immutable execution-plan routing into the
registered path.

Historical Boolean-if/foreach syntax-admission owners are no longer registered semantic authorities.
Their retained successor tests exercise stock behavior plus provenance/topology/security invariants,
not a second dynamic-language permission table.

## Manual release acceptance boundary

The repository-owned [manual release validation sample](../samples/manual-release-validation/README.md)
is the shared acceptance fixture for an exact packaged release candidate. Its M/J/R/U/T matrix maps
current P1-P4 behavior to explicit automated owners; it is **not** a mandate to replay the full
semantic/routing matrix manually.

Manual acceptance is restricted to three residual IDEA UX checks that automation does not judge
well: exact-ZIP install/settings/action visibility, one native + one prepared result visual sanity
check, and restart/persisted-target UX. DataGrip has no separate manual requirement because its exact
packaged service/refusal/prepared route is already exercised independently by Starter/Driver.

Historical #509/#510 reproductions are promoted to normal `Parity*` acceptance fixtures only
because P1/P2 and packaged evidence own the underlying behavior. Human smoke never substitutes for
those automated contracts.

## Sensitive logging evidence boundary

PR #117 removed the shipping action's known INFO-level interpolation of resolved parameter values, extracted/dialog values, and rendered SQL. #458 extends that boundary to mapper/source identities, datasource/schema display names, console titles, persisted target-selection identifiers, and platform exception-message detail. `SensitiveLoggingContractTest` follows the production source boundary rather than one class: it scans logging-call windows across `src/main/kotlin` for direct interpolation of the maintained sensitive aliases/object properties and exception-message forms, while explicitly allowing metadata-only `size`/`length`, stage, and failure-type evidence. Expected Database Tools failures retain typed class identity without unused raw detail payloads; genuine fatal throwables remain attached only to fatal diagnostics and are rethrown.

This test is deliberately narrower than a semantic information-flow proof. It can falsify the maintained regression classes and catches those sensitive forms if logging responsibility moves between production Kotlin classes, but it cannot prove safety for arbitrary future aliases, reflection, third-party logging, or runtime-generated sinks. Review remains responsible for applying the product privacy contract to new variables and new logging surfaces.

## Contract-driven input provenance evidence boundary

The shipping input model is no longer a source-text heuristic. `MyBatisInvocationContractFactory`
derives caller requirements from mapper method metadata or one explicit XML-only parameter object.
MyBatis-compatible explicit/generic/collection aliases are derived from declaration authority.
SQL syntax contributes usage/risk evidence only: a caller requirement may carry both
`BOUND_PLACEHOLDER` and `RAW_INTERPOLATION` usage, while foreach/bind/additional values remain
MyBatis-generated internal bindings rather than caller inputs.

`ContractInputAdapterTest`, submission/history policy tests, provenance/presentation tests and the real Swing fixture prove that codecs and validation are independent from presentation, required values fail closed, canonical identity scopes retention, and raw interpolation is never retained. These are current registered-path contracts rather than characterization of a removed extractor/dialog.

## Java direct-annotation capture evidence boundary

`JavaAnnotationSourceCaptureAdapterProjectFixtureTest` and `JavaAnnotationSourceCaptureAdapterAdversarialProjectFixtureTest` exercise the new non-wired Leap Java source adapter in the real Java code-insight sandbox. Capture starts from the immutable active-editor `SourceSnapshot`, commits only the active `Document` into PSI for semantic interpretation, then re-captures and requires the same source revision/content/caret evidence. The unsaved-root fixture proves the resulting SQL comes from the draft `Document` while the physical file remains unchanged and unsaved.

The adapter constructs `JavaStatementId` from source identity, fully qualified mapper type, and ordered canonical parameter type identities, so overloaded methods remain distinct. It preserves direct annotation array order as `sqlSegments` and records declaration-ordered parameter metadata with source name, canonical type identity, and statically proven `@Param` alias. That metadata is evidence for #63 only; this boundary does not infer MyBatis `paramN`/`argN`, collection aliases, placeholder requirements, codecs, UI state, or remembered values.

Compile-time String constants are captured as source dependencies instead of being reduced to unproven text. Same-file and cross-file references become source-backed `SourceDependencyEdge`s, dependent files are captured through the existing live-`Document`-first/VFS-fallback adapter, and an unsaved dependent constant fixture proves the committed PSI value, immutable dependent snapshot, and returned SQL all come from the same draft while disk remains unchanged. Qualified references retain only the outer field reference as dependency evidence so class qualifiers are not misclassified as constants. Unsupported or unprovable constant expressions, dependent-source races/unavailability, and constant dependency cycles fail typed.

The adversarial Java fixtures also prove exact `StatementKind` mapping for all four Leap-v1 direct annotations and typed failures for missing method/direct annotation, unresolved `@Param` alias, unresolved parameter type, anonymous/unqualified mapper identity, provider annotations, custom `@Lang`, `@Select(affectData = ...)`, and unresolved annotation values. #252 promotes standard `databaseId` variants without coupling source capture to target state: repeated direct annotations and explicit `@Select.List` containers are captured as variants first; statement kind is preserved per variant (including cross-kind database-id variants), then `selectJavaAnnotationStatementVariant` applies PostgreSQL/Oracle target authority and rebuilds the selected root statement kind. Missing authority returns `AuthorityUnavailable`, and unknown aliases return `MappingUnproven` rather than silently falling back. `JavaAnnotationSourceCaptureKotlinBoundaryTest` still proves Kotlin direct annotations return explicit `UNSUPPORTED_KOTLIN_SOURCE`. The captured core model retains no IntelliJ objects.

The source-capture boundary by itself does not prove MyBatis preparation or Database Tools execution; those responsibilities are covered by downstream contract and packaged E2E evidence.

## MyBatis preparation evidence boundary

**Current-state evidence note:** P2 replacement parity is now the registered preparation model.
Mapper method / explicit XML-object metadata owns caller authority and stock MyBatis owns standard
dynamic-SQL semantics. There is no registered Boolean-if/foreach grammar-admission island.

`P2CallerAuthorityParityTest`, `P2RegisteredPathParityTest`, `XmlSiblingBooleanIfPreparationTest`
and `XmlMapperForeachPreparationTest` compare maintained SQL/mapping behavior with stock MyBatis.
The remaining preflight logic is capability/safety-only: unsafe executable OGNL/class loading,
custom application TypeHandler/LanguageDriver boundaries, resource budgets, raw non-retention/
approval provenance, source revision identity and bound-token topology remain fail closed.

There is no compatibility evaluator, unknown-tag stripping mode, OGNL source rewrite, error-as-SQL
path, or evaluator-owned generic literalization in the registered execution architecture.
Unsupported preparation, plan freezing, binding, target, or backend execution returns typed failure
without switching to a legacy/materializer route.

## Mutation and raw interpolation safety evidence boundary

Raw interpolation is represented explicitly in the parameter contract and `PreparedExecution`; it is never treated as a normal bound value. `LeapExecutionApprovalPolicyTest` and adapter/delivery tests prove that any raw interpolation or INSERT/UPDATE/DELETE declaration forces final-artifact confirmation even when ordinary `sqlPreview` is disabled.

Statement kind remains confirmation evidence, not SQL authorization. The immutable approved
`PreparedExecutionPlan` is the execution authority; cancellation terminates the invocation, and
packaged Starter/Driver scenarios prove XML/direct-Java raw confirmation behavior without weakening
the prepared/native routing boundary.

## Preview and execution boundary

Current shipping preview/approval receives the immutable `PreparedExecutionPlan`: placeholder SQL,
ordered typed bindings, raw interpolation provenance/result, source revisions, target/databaseId and
statement kind. Formatting derives presentation text only and cannot mutate the approved plan.

The exact approved plan is immediately revalidated before explicit backend dispatch:
- bindings > 0 -> `DatabaseSessionPreparedBackend`;
- bindings == 0 -> `NativeConsoleSqlTextBackend`.

The maintained Starter/Driver E2E currently supplies representative full click-throughs for:
- zero-binding native execution;
- multi-binding prepared execution + zMyBatis result presentation;
- promoted built-in `parameterType="long"` metadata through the registered prepared route;
- target-derived PostgreSQL `databaseId` selection over the default variant;
- mutation-declaration approval with ordinary preview disabled and cancellation before execution;
- explicit packaged `@SelectProvider` refusal;
- XML raw cancellation;
- direct-Java raw cancel/reinvoke/execute;
- typed no-datasource refusal.

The parameterType scenario is P5 packaged acceptance for historical #510/R05. These representative
scenarios still do not replace the lower-layer stock MyBatis oracle or project-fixture lifecycle/
prepared-cancellation evidence.

## Session and datasource platform gaps

Automated persistence/lifecycle evidence covers only current v3 state. Development-only v2 persistence was never distributed and has no compatibility reader.

The tests prove project-scoped v3 persistence, stable datasource UUID + explicit schema identity, stale-source pruning without console creation, exact target re-resolution, cache target matching, source-key REUSE ownership, terminal lease release, shutdown gating, disposal, and immutable artifact delivery. Structural evidence verifies that the shipping adapter checks a matching cached console before constructing a replacement and carries lease release through the execution terminal callback. These lower-level fixtures still do not claim scheduler fairness or host-specific native console behavior.

## Separate DataGrip host evidence boundary

#478 adds DataGrip as an explicit, independently visible host proof rather than reusing IDEA Ultimate evidence.

`verifyPlugin` targets DataGrip 2026.3 independently of IDEA Ultimate 2026.3, with the minimum build raised to 263. The process layer uses `ZMyBatisDataGripStarterDriverE2ETest` through the separate `dataGripIntegrationTest` task and `Starter / Driver E2E (DataGrip)` CI job. P5 adds one bounded XML-only prepared PostgreSQL scenario: the DataGrip job provisions a pinned PostgreSQL service and local pgJDBC driver, the registered XML action selects the explicit `public` schema, one stock-MyBatis `<bind>` value remains a JDBC placeholder, and `DatabaseSessionPreparedBackend` returns `41` through the zMyBatis prepared-result dialog. The host suite also retains service loading, typed no-datasource refusal, strict IDE-failure reporting and clean shutdown.

DataGrip evidence is deliberately narrower than the IU PostgreSQL golden path. It does not prove Java annotation source behavior, native Database Tools result/history parity, every datasource/schema lifecycle case, every DBMS/dialect, or wider JetBrains host compatibility. A successful IU run cannot satisfy the DataGrip obligation, and a successful DataGrip run cannot widen the Java-source or native-result capability matrix. Those gaps are explicit so a green `Test` context is not misrepresented as broader platform proof. `DataGripStarterDriverE2EContractTest` pins that bounded claim and the pinned PostgreSQL workflow prerequisite.

## Other gates

`Inspect code`/Qodana and `Verify plugin` remain separate required evidence for static analysis and JetBrains compatibility. They do not substitute for the product assertions in `Test`.

## P2 caller-authority and dynamic-SQL parity evidence

P2 removes syntax-derived caller admission from the registered preparation path. For mapper-backed
Java/XML statements, the canonical method signature and explicit `@Param` metadata own caller
requirements. A single unannotated scalar/object remains the whole parameter object; multi-parameter
calls use stock-compatible primary/generic aliases; List/Collection/array wrappers and nested
Map/object values follow the frozen `ParamNameResolver` parity oracle. XML-only statements expose
one explicit object-tree parameter environment when caller-dependent runtime content exists.

`P2CallerAuthorityParityTest` proves the invocation object and alias model. `P2RegisteredPathParityTest`
proves the maintained Java/XML preparation entries for static CRUD, mapper objects, collections,
standard dynamic families and realistic if/choose/foreach/where/set/trim/bind combinations.
`XmlSiblingBooleanIfPreparationTest` and `XmlMapperForeachPreparationTest` retain deeper
stock-behavior, mapping-order, nullability/type-fidelity, generated-additional-parameter and
concurrency coverage without granting syntax-derived caller authority.

Raw interpolation is usage/risk, not a separate caller-value authority. The same String caller
requirement may be consumed by both `#{...}` and `${...}`. `PreparedExecution.rawInterpolations`
preserves the affected requirement identity/origin so approval and non-retention policy remain
enforceable. Raw text must not synthesize, delete, escape or mutate authoritative `#{...}`
topology.

The registered path still fails closed for capability boundaries that P2 does not own: arbitrary
application TypeHandlers/LanguageDrivers, executable class/static OGNL outside the maintained
safe envelope, malformed MyBatis/OGNL, resource limits, source/statement/databaseId drift and
runtime isolation failures. `P2OgnlSafetyAdmissionTest`, adversarial engine suites and the dynamic
bound-token topology tests own those constraints.

The isolated MyBatis runtime is per invocation. `MyBatisRuntimeArtifactTest` proves exact local
artifact recovery, including IntelliJ custom `jar:` resource URLs, while rejecting remote,
missing or ambiguous artifact authority. Fresh loaders, TCCL restoration and JDK-owned cross-loader
payloads prevent application/plugin classes or shared OGNL state from becoming hidden execution
authority.

The root execution tests (`LeapExecutionInvocationCoordinatorTest`,
`LeapExecutionCandidateCoordinatorTest`, and the project-fixture handoff tests) use the same
`MyBatisInvocationContractFactory` as production. This prevents test-only legacy contracts from
masking registered-path drift. P3/P4 are complete: nonzero ordered bindings are ordinary prepared
backend input, while zero-binding/exact-text plans use the explicit native backend. Historical
materializer tests are no longer execution-authority evidence.

## IDEA 2026.3 compatibility and console lifetime authority (2026.2.3 historical)

The build and Starter/Driver IDE now target IDEA Ultimate 2026.3 on JDK 25.
The declared minimum is **263**; independent Plugin Verifier targets are IDEA Ultimate 2026.3 and DataGrip 2026.3. Historical #251 Database Tools parameterized-execution probes and the previous IDEA 2026.2.3 lifetime tests remain historical evidence, **not** a current support promise. The 263 shipping console boundary does not open an editor tab. It borrows an existing Database Tools console/session client and uses a transient off-screen editor only when native SQL parsing requires an `Editor`; exact source/target and console validity are still revalidated before native execution. No internal or experimental editor-open APIs are permitted.

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
These fixtures do not prove every disposed editor, delayed UI callback or DataGrip runtime path.
Those broader lifecycle obligations are covered independently by the completed #460/#462/#464/#466/
#468/#476 lifecycle evidence and the #478/#480/#481 DataGrip evidence; this fixture remains narrow.

E2E applicability: the maintained packaged-plugin happy path, mandatory-preview cancellation paths
and no-datasource refusal apply on the exact final HEAD because the platform and production console
validity checks changed. Their IDE/artifact manifest must name 2026.3 and the tested final commit.

Unit/PSI fixture JVMs explicitly run headless so macOS desktop registration does not
prevent their execution; Starter/Driver remains a separate real UI process.
The IntelliJ 2026.3 unit/PSI test sandbox keeps the genuine Ultimate/DatabaseTools
plugin graph available, rather than carrying forward the 2026.2.3-only exclusion
of `com.intellij.modules.ultimate`. That old exclusion addressed an obfuscated
class-name collision in the flattened 2026.2.3 test loader; on 2026.3 it can
prevent the required `com.intellij.database.dataSourceManager` and
`com.algorist.zMyBatis.javaPsiCapability` extension points from registering.
The Java/Kotlin fixture-specific Vue exclusion remains. Existing project fixtures
still require real registered extension points, and continue to fail rather than
silently replacing DatabaseTools or the Java PSI capability with test stubs.
The separate real Starter/Driver process remains the authority for licensed IDE
startup and registered-action execution, not these unit fixtures. Packaged process-level E2E first starts without a
project and completes the normal supplied-license/trial activation before restarting
that same isolated config with the test project. 2026.2.3 initially disables Ultimate
without a license and its normal trial activation dynamically
re-enables it: observed scheme reloads raced project-tree color reads and raised
`ConcurrentModificationException` before the product assertions. Completing activation
before opening the project avoids that overlap; the normal license/trial flow and strict
exception checks remain active, with no new error exemption or retry. Broader licensing and
dynamic-plugin behavior remain outside this fixture's claim; DataGrip is maintained separately by its dedicated verifier/runtime evidence.
The 2026.2.3 Ultimate-wrapper unit-fixture exclusion is historical and is not applied to the 2026.3 test SDK. Full exact-HEAD fixture validation, not this documentation change, establishes whether the 2026.3 bundled plugin graph is healthy. The Java annotation and XML mapper-method fixtures permit
VFS access only to their actual MyBatis dependency JAR, scoped to each fixture disposable, so they also work
when the checkout/sandbox is outside the user's home directory.

## Leap candidate background responsiveness and cancellation handoff evidence

`LeapExecutionCandidateAdapterProjectFixtureTest` runs the shipping
`LeapExecutionCandidateAdapter` inside the real IntelliJ test application rather than treating
source-order assertions as scheduler evidence. A latch-controlled injected candidate deliberately
holds the `Task.Backgroundable` worker while an independent EDT sentinel is posted; the sentinel
must complete before the candidate is released, and the candidate records that it is not running
on the dispatch thread.

The same fixture captures the Progress API indicator associated with the shipping background task,
cancels it through `ProgressIndicator.cancel()`, releases the controlled producer, and requires
the next bounded `checkCanceled()` probe to take the task through cancellation without emitting a
normal candidate result. A separate delayed-completion case invalidates the invocation while the
candidate is outstanding and proves the adapter converts that completion to
`InvocationInvalidated`; the delivery coordinator's existing typed branch returns before approval
or Database Tools delivery.

All coordination is latch/future based. The fixture explicitly runs with IntelliJ's
`intellij.progress.task.ignoreHeadless=true` test switch through
`PlatformTestUtil.withSystemProperty`, because the platform deliberately executes background progress
tasks synchronously in unit/headless mode unless that production-style asynchronous mode is requested.
The property is scoped and restored by the platform test helper, and every queued task is awaited before
that scope exits. Fixture timeouts only fail a hung test; they are not product latency targets.
This evidence proves the #462 candidate scheduling/cancellation handoff, not a hard
interruption bound inside one stock-MyBatis parse/evaluation call, large-mapper/source-graph
responsiveness, large-input responsiveness, retained-resource behavior, or DataGrip host parity.

E2E applicability: the fixture owns the scheduler/cancellation invariant directly. The normal
Starter/Driver suite remains required on the exact final HEAD as packaged-plugin regression evidence,
but it does not replace this controlled IntelliJ scheduler proof.


## Post-target XML source-selection threading and cancellation evidence

`LeapPreparationSourceSelectionAdapterProjectFixtureTest` exercises the shipping asynchronous
selection boundary in a real IntelliJ test application. The fixture runs test orchestration off the
EDT and enables IntelliJ's production-style asynchronous progress behavior in headless mode with the
same scoped `intellij.progress.task.ignoreHeadless=true` test switch used by the candidate fixture.

A latch-controlled selector proves that post-target XML selection work runs outside the dispatch
thread while an independently queued EDT sentinel completes before selection is released. The same
fixture exercises real `ProgressIndicator.cancel()` handoff and delayed invocation invalidation;
neither path may emit a normal selection result that could continue toward input, candidate
preparation, approval, or Database Tools delivery.

The production selector keeps source-graph resolution and scalable mapper-method lookup on the
background worker. `XmlMapperMethodCaptureAdapter` wraps PSI/index reads in IntelliJ read actions;
only synchronization of an already-cached uncommitted document is marshalled narrowly to the EDT.
After selection completes, the coordinator reuses its existing source-revision and exact-target
revalidation before normal invocation continuation.

This is threading/cancellation evidence, not a product latency SLA or a broad source-graph benchmark.
Subsequent #470, #472/#474, #476, and #478 slices now provide the separate source-graph, input, resource-lifecycle, and DataGrip host evidence.


## Pre-target project XML expansion threading and cancellation evidence

`LeapProjectXmlMapperExpansionAdapterProjectFixtureTest` exercises the shipping pre-target project
XML mapper expansion boundary in a real IntelliJ test application. The fixture uses the same scoped
`intellij.progress.task.ignoreHeadless=true` production-style task semantics as the candidate and
post-target source-selection fixtures.

A latch-controlled expander proves project XML expansion runs outside the dispatch thread while an
independently queued EDT sentinel completes before the worker is released. The cancellation case
captures the real task `ProgressIndicator`, calls `cancel()`, releases the worker and requires the
next cooperative probe to prevent a normal expansion completion. A delayed-completion case invalidates
the invocation while expansion is outstanding and requires `InvocationInvalidated` rather than a
continuation toward target selection.

The production `XmlProjectMapperSourceCaptureAdapter` now accepts the task cancellation probe and
checks it before and after project XML enumeration, before each candidate file, after dependent
snapshot capture, and after mapper discovery before accumulation. Existing deterministic URL
ordering, root exclusion, the 2 MiB per-dependent-source limit, typed `IndexUnavailable`, and
fail-closed omission behavior are unchanged.

All fixture coordination is latch/future based; timeouts are hung-test guards rather than latency
targets. This evidence closes the structural cancellation-ownership gap only. The subsequent #470,
#472/#474, #476, and #478 slices now own the separate source-graph, input, resource-lifecycle, and DataGrip evidence.

## Large XML mapper/source-graph responsiveness measurement evidence

`LeapXmlMapperScaleProjectFixtureTest` extends the #468 shipping-boundary evidence from controlled
scheduler handoff to a bounded real project scan. The fixture creates one root mapper plus **128
dependent mapper XML documents**, each with an 8 KiB inert payload, then runs the production
`LeapProjectXmlMapperExpansionAdapter` and `XmlProjectMapperSourceCaptureAdapter` path. The test
asserts that all 129 mapper snapshots/discoveries survive capture, computes the exact aggregate
UTF-8 source bytes from the captured immutable snapshots, and emits a
`ZMYBATIS_XML_SCALE phase=project-expansion` record containing candidate count, aggregate bytes,
cooperative-probe count and observed elapsed time with the deterministic fixture-gate wait excluded.

The responsiveness case pauses the real capture loop at its 64th cooperative probe, after project
enumeration has entered per-candidate work, and requires an independently queued EDT sentinel to
complete before the scan is released. The cancellation case pauses at the same bounded production
probe, cancels the task's real `ProgressIndicator`, then requires cancellation to stop the capture
at that probe without a normal expansion continuation. It records observed cancellation handoff
time but does not assert a latency SLA.

`XmlStatementSourceGraphResolverScaleTest` measures the resolver separately so candidate-enumeration
cost and reachable-graph cost remain distinguishable. It exercises the maintained iterative
**2,000-deep** include chain and a **512-wide cross-document** include graph, asserting complete
dependency/source capture and emitting elapsed time plus exact graph/source dimensions under the same
`ZMYBATIS_XML_SCALE` prefix.

The Test job promotes only the dedicated measurement prefixes needed for CI readback without
enabling arbitrary test standard streams: the root test task owns `ZMYBATIS_XML_SCALE`,
`ZMYBATIS_INPUT_SCALE`, and `ZMYBATIS_RESOURCE_LIFECYCLE`, while `:mybatis-engine:test`
promotes `ZMYBATIS_PREPARATION_SCALE`. These are reproducible CI evidence dimensions, not
product-wide performance limits. Test timeouts
remain hang guards only, and elapsed values are observations for before/after comparison. The slice
adds no mapper cache/index and no new XML count/byte limit. Retained-resource behavior is measured by #476, and DataGrip host compatibility/runtime evidence is maintained separately by #478.

## Large parameter/input responsiveness measurement evidence

`ContractInputScaleProjectFixtureTest` measures the shipping contract-driven input boundary without
changing its semantics or introducing a product limit. The large-dialog case builds a real
`ContractParameterInputDialog` on the EDT from **128 required text inputs** with **128 retained
drafts**, each 4 KiB. This exercises production presentation creation, project-scoped history load,
one Swing editor/component set per field, dialog initialization, and the retained-draft population
path. The fixture records the exact retained bytes and construction elapsed time.

The dialog case also queues an independent EDT sentinel from inside the same EDT turn immediately
before construction. The sentinel must remain pending until the synchronous constructor returns and
must complete immediately afterward. That evidence characterizes the current shipping ownership:
large eager dialog construction is bounded synchronous EDT work; the fixture does not claim or
simulate background rendering.

A separate large-submission case uses **256 required text inputs**, each 4 KiB, for approximately
1 MiB of explicit input text. It invokes the real `ContractInputSubmissionPolicy` ->
`ContractInputAdapter` -> core `InputEnvironment` validation pipeline synchronously on the EDT,
asserts that all 256 validated values survive, and uses the same queued-sentinel pattern to
characterize EDT ownership of submission/decode/validation.

Successful runs emit `ZMYBATIS_INPUT_SCALE` lifecycle records containing requirement/editor counts,
aggregate input or retained bytes, validated-value count, EDT ownership, sentinel observation, and
elapsed time. The root test task surfaces only this prefix and the existing XML-scale prefix rather
than enabling arbitrary test stdout.

These values are reproducible comparison evidence, not latency SLAs. Timeouts are hang guards only.
This slice adds no pagination, virtualization, lazy rendering, cache, parameter-count limit, or
input-byte limit. If the measured baseline exposes a material responsiveness/resource defect, that
optimization is owned by a separate evidence-backed issue. #476 now supplies retained-resource/lifecycle evidence and #478 supplies separate DataGrip host evidence.

## Incremental large input dialog population responsiveness evidence

#474 consumes the scale baseline recorded by #472 without changing parameter/input semantics.
`ContractParameterInputDialog` no longer creates every field/editor/component in its constructor.
For a submittable non-empty contract it returns the shell first with OK disabled, then materializes
fields in ordered **8-field EDT chunks**. Each next chunk is scheduled only after the current EDT
turn returns, so unrelated queued EDT work has an opportunity to advance between chunks. Swing
component creation and mutation remain on the EDT; no input meaning, codec, provenance, retention,
or submission authority moves into background work.

`ContractInputScaleProjectFixtureTest` reuses the #472 **128 required text fields + 128 retained
4 KiB drafts** fixture. It queues an independent EDT sentinel before constructing the shipping
dialog and requires both constructor completion and that first sentinel to observe **zero materialized
fields**. It then waits through the real incremental population chain without sleeps, requires all
128 fields to appear in exact contract order, and verifies all 128 retained drafts were applied.
The emitted `ZMYBATIS_INPUT_SCALE phase=retained-dialog-construction` record now includes
constructor elapsed time, full-population elapsed time, chunk size, first-sentinel field count, and
completion state for direct comparison with the #472 eager-construction baseline.

A separate lifecycle case closes the dialog before the first deferred chunk. The already-queued
population callback must observe the disposed `DialogWrapper` disposable and leave the materialized
field count unchanged. Project disposal is checked at the same scheduling boundary. This proves
deferred population does not continue mutating a dead dialog/project.

The maintained 256-input / approximately 1 MiB submission/decode/validation measurement remains
unchanged because #472 did not identify that path as the dominant cost. Chunk size is an
implementation responsiveness batch, not a product parameter limit or latency SLA. The fix adds no
pagination, virtualization, field omission, input-size rejection limit, or background Swing mutation.
Retained-resource/lifecycle evidence beyond this dialog callback ownership is covered independently
by #476, while separate DataGrip host evidence is covered by #478/#480/#481; this dialog fixture
does not subsume either boundary.

## Repeated console resource lifecycle and retention evidence

`ConsoleResourceLifecycleScaleProjectFixtureTest` measures the repeated resource envelope of the
shipping Database Tools acquisition boundary using real IntelliJ `JdbcConsole` instances.

The REUSE case performs **32 same-source/same-target completed acquisitions** through
`DatabaseToolsConsoleAdapter`. Every terminal callback releases keyed ownership before the next
iteration. The fixture records the number of distinct console identities, verifies that the cache
retains exactly the single matching console, proves the lease can be reacquired after every
completion, then evicts that bounded owner and requires the console lifetime to be disposed.

Fresh-console acquisition is no longer a shipping capability. Scale evidence repeatedly borrows one
Database Tools-owned console, requires zero zMyBatis cache retention, and verifies no source-key lease is stranded.
The real console lifetimes are deliberately left untouched by fixture cleanup so the platform test
project teardown can establish the actual post-terminal lifetime boundary. The fixture records how
many NEW_EACH consoles remain live immediately after terminal handoff and, from `tearDown()`, how
many are disposed when the owning IntelliJ test project is torn down. A live NEW_EACH console after
terminal handoff is therefore recorded as outside zMyBatis cache ownership rather than being
silently classified as a leak.

Terminal cleanup evidence separately injects `ProcessCanceledException` and an ordinary delivery
failure from the real acquisition callback. Both paths must remove any newly registered REUSE
console and release keyed ownership so the same key can be acquired again. An isolated real-console
service fixture additionally owns **8 REUSE resources** and requires explicit
`ConsoleCacheService` disposal to dispose all eight.

Successful runs emit `ZMYBATIS_RESOURCE_LIFECYCLE` records for repeated completion, terminal
ownership, cleanup, service disposal, and project disposal. The root test task surfaces this prefix
alongside the existing XML/input measurement prefixes without enabling arbitrary test stdout.

The retained materialized-delivery fixture predates P4 and still runs repeated cycles through the
former `acquireAndDeliverConsole -> executeMaterialized -> onFinished` boundary. It remains useful
only as historical console/editor cleanup characterization and is **not** a source contract for the
registered shipping path. Current shipping routing is covered separately by
`LeapExecutionDeliveryCoordinatorTest`, prepared cancellation ownership tests, and packaged
Starter/Driver E2E.

That historical repeated fixture deliberately records `native_invocations=0`; it must not be cited
as proof of current native or prepared execution success.

These fixtures measure zMyBatis cache/lease ownership plus real console lifetime convergence; they
do not claim every native Database Tools result/editor/history object is owned by zMyBatis. Native
visible-console/history behavior remains platform-owned evidence; the separate DataGrip host claim is maintained by #478 and does not widen this resource fixture. No forced GC, heap-size threshold, arbitrary console-count
limit, or REUSE/NEW_EACH policy redesign is introduced by this measurement slice.



## Preparation scale and retired materializer characterization

Large-collection preparation remains a relevant stock-MyBatis responsiveness boundary even though
the former single-BIGINT SQL-text materializer is no longer shipping execution authority.
`MyBatisValueConversion` currently bounds structured conversion at 65,536 input nodes, while #470
measures source-graph scale and #472/#474 measure many UI requirements/drafts rather than one large
runtime collection flowing through isolated MyBatis preparation.

#494 measured representative large-collection preparation through the same stock-MyBatis boundary
used by the registered candidate path and then characterized the **then-shipping** materializer's
multi-binding refusal. That terminal refusal is retained as historical migration evidence only.
Current P4 execution instead freezes the resulting mappings into `PreparedExecutionPlan` and routes
nonzero ordered bindings to `DatabaseSessionPreparedBackend`. Timings remain comparison
observations, not product SLAs; any future concrete defect must be split into its own bounded fix.


`MyBatisPreparationScaleTest` makes that scale obligation executable in
`:mybatis-engine:test`, where the real MyBatis 3.6.0 dependency has a local file-backed runtime
artifact. The fixture asserts that precondition through `MyBatisRuntimeArtifact` before measuring;
it does not bypass or weaken the production fail-closed artifact resolver.

The fixture uses one source-authorized Java-annotation `<foreach>` contract and the same
`MyBatisPreparationBoundary` used by the registered candidate path at increasing collection
cardinalities **128 / 1,024 / 4,096**. Each case records collection items, structured input-node
count, emitted ordered-binding/placeholder cardinality, SQL character count, preparation elapsed,
materialization elapsed and combined pipeline elapsed under the `ZMYBATIS_PREPARATION_SCALE`
prefix. Exact linear mapping growth is required.

The historical fixture then passes the prepared artifact to
`PostgreSqlBigintExecutionMaterializer` and records its former
`BINDING_COUNT_UNSUPPORTED` behavior. That assertion characterizes the retired compatibility
boundary; it does **not** describe current registered execution, where those mappings are ordinary
prepared bindings.

The root IntelliJ unit harness is deliberately not used as authority for the dynamic-MyBatis scale
measurement. Its flattened IntelliJ test classloader can load MyBatis without a recoverable local
code-source artifact, and the production isolation boundary correctly rejects that condition as
`mybatis-code-source-unavailable`. Relocating the measurement to `:mybatis-engine:test` preserves
that fail-closed rule instead of introducing a test-only artifact override.

Shipping scheduling and stale-work ownership remain independently authoritative in
`LeapExecutionCandidateAdapterProjectFixtureTest`: the real `Task.Backgroundable` candidate
boundary runs off the EDT, keeps an independent EDT sentinel responsive, propagates
ProgressIndicator cancellation, and suppresses delayed completion after invocation invalidation.
Those scheduler/lifecycle invariants compose with this scale measurement; #494 does not duplicate
them with a harness that cannot satisfy the dynamic-runtime artifact precondition.

The cardinalities are reproducible evidence points, not product limits. The fixture deliberately
records deterministic resource cardinalities (input nodes, mappings/placeholders and SQL size)
rather than forced-GC heap guesses. Timeouts elsewhere remain hang guards and these elapsed values
remain comparative observations, not latency SLAs.


### P5 legacy UX parity evidence

P5 adds an explicit UX regression contract on top of functional parity. `DatabaseToolsConsoleAdapterBoundaryTest` pins existing-session/client discovery and forbids `JdbcConsole.newConsole`, editor-tab navigation, console focus stealing, zMyBatis console ownership, and duplicate branded console identities. Human smoke verifies the same packaged behavior in IDEA. UI improvements to parameter entry are allowed when they clarify state without changing input authority.
