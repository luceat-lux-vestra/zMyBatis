# zMyBatis test contracts

The required `Test` CI context is product evidence, not a line-coverage target. `./gradlew check` must fail when one of the automated contracts below regresses. Template fixtures, empty files, debug-only reproductions, and `println` output are not considered evidence.

## Automated contract map

| Correctness boundary | Authoritative evidence | What it proves |
| --- | --- | --- |
| MyBatis parameter discovery | `ParameterExtractorTest`, `ParameterExtractorBoundaryTest`, `ParameterProvenanceBaselineTest` | scalar/object roots, bind/foreach exclusions, nested collections, indexed `#{}`/`${}` paths, placeholder options, generated-alias/collection-alias source heuristics, lexical false-positive boundaries, deterministic large-fixture deduplication |
| JSON parameter input | `JsonParameterTest`, `OgnlEvalTest` | the `parseValue()` path used by the parameter dialog, nested object/array values, malformed input, integer precision boundaries |
| Dynamic SQL and literal rendering | `MyBatisEvaluatorContractTest`, `MyBatisEvaluatorDynamicTagBaselineTest`, `OgnlEvalTest` | representative direct evaluator behavior for `if`, `choose`/`when`/`otherwise`, `foreach`, `where`, `set`, `trim`, and `bind`; nested OGNL; `#{}` vs `${}`; quote escaping; NULL/boolean literals; visible current failure markers for unsupported direct list/map values |
| Negative evaluator boundaries | `MyBatisEvaluatorNegativeBoundaryTest` | current classified-vs-unclassified OGNL failure behavior under Strict mode, unknown-tag stripping, unresolved-include truncation under compatibility mode, malformed XML diagnostic strings, and executable-looking unsupported-value markers |
| Legacy error-as-SQL shipping refusal | `MyBatisEvaluatorNegativeBoundaryTest`, `LegacyEvaluationExecutionGuardContractTest` | direct legacy `evaluate(...)` calls retain the characterized compatibility error-comment string, while the shipping-only `evaluateForExecution(...)` boundary converts ordinary evaluation exceptions into an explicit non-executable result; `MyBatisExecuteProxyAction` terminates that worker before formatting, preview, console mutation, or native query execution; cancellation and fatal non-`Exception` throwables remain non-downgradable |
| Legacy unknown-tag compatibility isolation | `MyBatisEvaluatorNegativeBoundaryTest`, `LegacyUnknownTagExecutionGuardContractTest`, `LegacyEvaluationExecutionGuardContractTest` | direct legacy `evaluate(...)` may still honor `Ignore Unknown Tags` by regex-stripping unsupported wrappers for characterization, while shipping-only `evaluateForExecution(...)` explicitly disables that compatibility policy regardless of the application setting; unknown elements therefore become non-executable evaluation failures before formatter/preview/console/native execution, and supported MyBatis dynamic tags remain evaluable |
| Legacy OGNL-placeholder compatibility isolation | `MyBatisEvaluatorContractTest`, `MyBatisEvaluatorNegativeBoundaryTest`, `LegacyEvaluationExecutionGuardContractTest` | direct legacy `evaluate(...)` retains the characterized rewrite of `#{...}` inside OGNL-bearing attributes, while shipping-only `evaluateForExecution(...)` explicitly disables that source rewrite; non-standard expressions therefore fail through MyBatis before formatter/preview/console/native execution, while valid stock-MyBatis OGNL remains evaluable |
| Legacy OGNL global-state isolation | `MyBatisEvaluatorNegativeBoundaryTest`, `LegacyOgnlIsolationContractTest`, `OgnlEvalTest`, `MyBatisEvaluatorContractTest` | the shipping legacy evaluator no longer registers a process-global OGNL `PropertyAccessor`; qualified nested Map/list navigation remains stock-MyBatis-backed, while a missing property on one Map does not scan descendant sibling Maps to invent a value |
| Legacy unsupported-literal shipping refusal | `MyBatisEvaluatorNegativeBoundaryTest`, `LegacyLiteralizationExecutionGuardContractTest`, `LegacyEvaluationExecutionGuardContractTest` | direct legacy calls retain List/Map marker-bearing `NULL` and arbitrary-object `toString()` compatibility characterization, while shipping-only evaluation enables fail-closed literalization and returns a non-executable failure for direct List/Map/other unsupported objects before the action can reach formatting, preview, console mutation, or native query execution; existing null/Number/Boolean/String/Date/Temporal scalar compatibility remains bounded legacy behavior, not JDBC/TypeHandler parity |
| Legacy missing-bound-parameter shipping refusal | `MyBatisEvaluatorNegativeBoundaryTest`, `LegacyEvaluationExecutionGuardContractTest` | presence-aware legacy property resolution distinguishes explicit `null` from an absent flat/nested/indexed `#{...}` path; shipping-only evaluation returns a non-executable missing-parameter failure while direct legacy evaluation retains its characterized missing-to-`NULL` behavior; explicit null and MyBatis additional parameters remain on the existing bounded scalar path |
| Mutation/raw interpolation safety baseline | `MyBatisExecutionSafetyBaselineTest` | common read/mutation/DDL/unclassified SQL families remain ordinary evaluator strings, statement wrapper kind does not constrain SQL semantics, and `${}` preserves statement-shaped raw text without a typed safety boundary |
| Legacy raw-interpolation confirmation | `MyBatisEvaluatorNegativeBoundaryTest`, `LegacyEvaluationExecutionGuardContractTest`, `ZMyBatisStarterDriverE2ETest` | shipping evaluation carries a conservative raw-interpolation confirmation flag; the action forces production SQL Preview when raw `${...}` is present even if `sqlPreview=false`; the packaged IU 2026.2 path proves the resolved final SQL is shown and Cancel does not create a native result grid |
| Legacy mutation-declaration confirmation | `LegacyStatementConfirmationPolicyTest`, `LegacyEvaluationExecutionGuardContractTest`, `ZMyBatisStarterDriverE2ETest` | XML insert/update/delete and Java @Insert/@Update/@Delete declarations force production SQL Preview even if `sqlPreview=false`; declaration kind is confirmation-policy evidence only, not SQL authorization; packaged IU 2026.2 proves a mutation declaration wrapping SELECT still forces preview and Cancel prevents the result grid |
| Sensitive production logging baseline | `SensitiveLoggingContractTest` | production Kotlin logging-call windows reject direct interpolation of known sensitive query/input variables while allowing metadata-only counts/lengths; this is a source-pattern regression guard, not semantic taint analysis or runtime sink proof |
| Mapper dependency baseline and legacy shipping refusal | `MyBatisEvaluatorDynamicTagBaselineTest`, `MyBatisEvaluatorNegativeBoundaryTest`, `LegacyXmlIncludeExecutionGuardProjectFixtureTest`, `LegacyXmlIncludeExecutionGuardContractTest` | evaluator characterization remains explicit: unresolved `<include>` can still become truncated SQL under the legacy `Ignore Unknown Tags` compatibility transform when the evaluator is called directly; the shipping XML action now prevents that unsafe path by detecting real descendant `<include>` PSI and refusing before statement extraction, parameter UI, target resolution, evaluator work, console acquisition, or execution; comments/CDATA containing include-looking text do not trigger; this temporary refusal does not replace #62/#64 authoritative mapper dependency support |
| Annotation SQL extraction | `AnnotationSqlExtractorTest`, `AnnotationSqlExtractorProjectFixtureTest` | literal/array/constant shapes at the PSI-interface contract plus real Java PSI/project-index resolution of cross-file constant references, including ordered constant arrays |
| Java action source authority / overload identity | `JavaActionContextProjectFixtureTest` | direct legacy analyzer/extractor calls continue to read last-committed PSI until explicit `commitDocument`; the shipping execution action separately refuses an uncommitted editor/PSI boundary before extraction rather than executing stale PSI; a real saved Java editor/caret selects the exact overloaded `PsiMethod` and annotation SQL; shipping remembered-input keys now include qualified mapper type + ordered method parameter-type signature, and exact project history lookup remains isolated across overloads while XML keys retain `file::statementId` form |
| IDE action presentation boundary | `MyBatisActionUpdateProjectFixtureTest`, `MyBatisActionUpdateBoundaryTest` | the shipping BGT `update()` enables/shows only local supported XML/direct-Java/provider contexts, hides unrelated or missing-editor contexts, fails closed for Java when indexes are unavailable, and delegates only to bounded context classification without SQL extraction, parameter analysis, datasource enumeration, persistence, console creation, or execution |
| Leap XML Boolean-if isolated runtime preparation | `XmlMapperMethodPreparationSourceTest`, `XmlBooleanIfRuntimePreparationTest`, `XmlSiblingBooleanIfPreparationTest`, `XmlBooleanIfProducerBoundaryTest` | complete mapper capture and its exact snapshot participate in the request; source/contract admission precedes conversion and a fresh owned MyBatis/OGNL runtime evaluates flat Boolean-if siblings directly or inside bounded direct wrappers: one where role, one UPDATE set role, or both for UPDATE; native tags and exact literal trim forms share those limits; stock SQL and ordered caller mappings/handler metadata survive true/false branches, condition-only inputs add no fabricated binding, token-fragment topology and condition-scoped exact source/property counts block synthesized or missing mappings for every true/false combination, missing/invalid inputs and source drift fail closed, parent OGNL poisoning and concurrent invocations preserve isolation/TCCL, and non-zero bindings remain `BOUND_EXECUTION_REQUIRED`; absent capture still refuses dynamic XML and no production action wiring changes |
| Leap XML Boolean-if preparation admission | `XmlBooleanIfPreparationAdmissionTest`, `XmlBooleanIfProducerBoundaryTest` | source-derived condition evidence plus the complete authoritative mapper-method capture rebuild the producer contract; exact caller requirements, aliases, internal bindings and source revisions must match before returning the distinct condition requirement ids/aliases; unused mapper parameters that suppress generated aliases, forged/missing/duplicate evidence, type/requiredness/range/identity/revision drift, extra XML snapshots/dependencies, and unsupported sources fail typed; the engine consumes this admission only with complete mapper capture, before isolated Boolean-if preparation; it grants no production execution authority |
| Leap XML Boolean-if input provenance | `XmlBooleanIfParameterContractTest`, `XmlBooleanIfProducerBoundaryTest` | flat `<if test="alias">` siblings directly or inside bounded wrappers request required Boolean caller inputs only through proven explicit/generic mapper aliases; one where role and one UPDATE set role share limits across native tags and exact literal trim forms; OGNL and placeholder evidence stay distinct, and scalar whole-object fallback never supplies condition names; non-Boolean, unproven, nested/mixed tags, unsupported wrappers/trim attributes, complex/reserved, raw-interpolation and source-authority drift remain blocked; missing conditions are refused and absent complete mapper capture still returns typed dynamic-SQL refusal; the input contract alone grants no runtime or production execution authority |
| Leap active-editor snapshot boundary | `ActiveEditorSourceSnapshotAdapterProjectFixtureTest` | the new non-wired source adapter captures saved and unsaved active `Document` text/revision/caret without committing PSI or saving disk, fails closed for missing file identity/source mutation/invalid caret evidence, and returns no retained IntelliJ platform object; it does not prove production action cutover or dependent-source resolution |
| Leap dependent mapper snapshot boundary | `DependentMapperSourceSnapshotAdapterProjectFixtureTest` | for one already-resolved dependent `VirtualFile`, a loaded unsaved `Document` wins over stale VFS/disk content; otherwise bounded VFS text is captured with the same source identity scheme; oversize/invalid/unreadable/racing source fails closed and no IntelliJ platform object is retained; it does not discover namespaces/includes or construct dependency graphs |
| Leap XML mapper discovery boundary | `XmlMapperSourceDiscoveryTest`, `XmlMapperSourceDiscoveryExternalResolutionTest` | immutable `SourceSnapshot` input is parsed without external DTD/entity resolution; mapper namespace, direct statement/fragment declarations, repeated nested `<include refid>` occurrences, exact source-backed start-tag ranges, and standard `databaseId` variants are preserved while custom `lang` remains unsupported evidence; duplicate logical ids are allowed only across distinct database ids; malformed/unsafe/duplicate-variant/structurally invalid source fails closed |
| Leap XML source-graph resolution boundary | `XmlStatementSourceGraphResolverTest`, `XmlStatementSourceGraphResolverAdversarialTest` | canonical XML root identity plus immutable captured snapshots/discoveries resolve same-namespace and qualified includes into a reachable-only `StatementSourceGraph`; target-derived database id selects exact statement/fragment variants with default fallback only for known conventional ids; missing authority/custom mappings fail narrowly; missing/ambiguous/cyclic/input-mismatch/reachable-unsupported cases remain typed |
| Leap Java direct-annotation capture boundary | `JavaAnnotationSourceCaptureAdapterProjectFixtureTest`, `JavaAnnotationSourceCaptureAdapterAdversarialProjectFixtureTest`, `JavaAnnotationSourceCaptureKotlinBoundaryTest` | authoritative active `Document` source is synchronized to Java PSI without saving disk; direct/repeatable/container statement annotations preserve all database-id variants without consulting a target; ordered SQL segments, method parameter/`@Param` metadata, and constant dependencies are retained; provider/Kotlin/`@Lang`/`affectData`/duplicate-database-id/unresolved source fail typed; mixed statement kinds remain per-variant evidence and target-dependent selection is a separate pure-core boundary |
| Leap source-graph revision revalidation boundary | `StatementSourceGraphRevalidatorProjectFixtureTest` | captured `document:<stamp>` and `vfs:<stamp>` authority is rechecked without VFS refresh, document loading, PSI commit, disk save, or mapper rediscovery; unchanged unsaved documents remain valid, edit-then-revert revision drift stays stale, a saved document load does not invalidate prior VFS authority, unsaved document takeover does, moved/missing/unsupported identities fail typed, and multi-source failure order follows canonical source identity; #66 owns when this adapter is invoked before execution |
| Leap XML single-parameter-object binding boundary | `XmlSingleParameterObjectContractTest`, `XmlMapperSingleParameterObjectPreparationTest`, `XmlMapperMethodParameterContractFactoryTest`, `XmlMapperStaticBoundInputPreparationTest` | for one authoritative unannotated scalar/temporal Java mapper parameter whose exact runtime type has a stock MyBatis 3.5.19 built-in TypeHandler, a simple static XML `#{...}` property may resolve through whole-parameter fallback without being misrepresented as a Java/source alias; contract provenance records `PARAMETER_OBJECT` authority, isolated preparation passes the direct converted value to MyBatis and preserves mapping order/property metadata, while multi-parameter, structured, raw, nested, dynamic, and no-stock-TypeHandler scalar cases such as UUID remain outside this island |
| Leap XML single-Map parameter-object property boundary | `XmlSingleMapParameterObjectContractTest`, `XmlMapperSingleMapParameterObjectPreparationTest`, `ContractInputProvenancePresenterTest` | for one authoritative unannotated exact `Map<String,V>` mapper parameter where `V` is a maintained scalar/temporal stock-MyBatis type, simple static bound properties may resolve through parameter-object Map property lookup; one MAP caller requirement retains explicit `ParameterObjectProperty` provenance for every mapping property, isolated preparation converts the Map with declared generic value fidelity, passes the Map itself to MyBatis, captures each ordered entry value, distinguishes absent key from present-null, and fails closed on provenance tampering or value-type mismatch; raw/non-String-key/nested/container/custom Maps and raw/nested/dynamic XML remain outside this island |
| Leap XML named-Map property binding boundary | `XmlNamedMapPropertyContractTest`, `XmlMapperNamedMapPropertyPreparationTest`, `ContractInputProvenancePresenterTest` | a statically proven explicit `@Param` or deterministic `paramN` alias whose exact type is `Map<String,V>` may authorize one simple leaf path such as `#{payload.id}`; the contract keeps the root caller alias and records separate `NamedMapProperty` provenance for the full MyBatis mapping property, isolated preparation passes the typed Map under that alias and captures the resolved entry value in order, while missing keys, deep/bracket paths, raw interpolation, unproven aliases, unsupported Map shapes/value types, and provenance tampering fail closed |
| Leap XML foreach preparation boundary | `XmlForeachPreparationAdmissionTest`, `XmlMapperForeachPreparationTest` | source-derived foreach collection and exact item/index authority are revalidated against complete independent mapper capture before a fresh isolated XML MyBatis runtime prepares the bounded single-foreach island, direct or inside one attribute-free where / exact literal WHERE trim wrapper; typed LIST/ARRAY/MAP conversion preserves stock generated additional bindings, mapping order, handler metadata and local provenance; empty collections, null items, repeated mappings, stock aliases and caller bindings outside foreach are covered; source drift, nested/property/raw/include/nullable cases, forged local/additional identity and element type mismatch fail closed; concurrent preparations ignore parent OGNL sentinels and restore context loaders; non-zero bindings still fail materialization with `BOUND_EXECUTION_REQUIRED` under #258 and no production action or Database Tools cutover is wired |
| Leap target-derived MyBatis databaseId boundary | `DatabaseIdTest`, `DatabaseToolsTargetResolverTest`, `XmlStatementSourceGraphResolverTest`, `JavaAnnotationSourceCaptureAdapterAdversarialProjectFixtureTest`, `PreparationTest`, `XmlMapperPreparationEngineTest`, `MyBatisPreparationEngineTest` | DBMS metadata is non-gating target semantic context; PostgreSQL/Oracle stock `DB_VENDOR` ids select XML/Java variants and reach isolated `Configuration.databaseId`; selection artifacts retain that authority, preparation rejects conflicting context, `_databaseId` observes the same value, missing/custom mappings fail narrowly, and explicitly proven custom-provider ids remain representable without a DBMS execution allowlist |
| Execution-target persistence and deferred console lifecycle | `PersistedConsoleSessionTest`, `LegacyV2ConsoleSessionMigrationStoreTest`, `ConsoleCacheServiceLifecycleTest`, `PersistedExecutionTargetSelectionTest`, `ExecutionTargetDescriptorStoreTest`, `MyBatisActionInterceptorActivityLifecycleTest`, `StoredExecutionTargetBridgeTest`, `DatabaseToolsConsoleAdapterBoundaryTest` | migration-only v2 state is owned by a dedicated project store and migrates only from proven canonical source identity into project-scoped v3 descriptors; malformed/interrupted/orphan state fails closed; existing v3 wins interrupted duplicate migration; `ConsoleCacheService` has no persistence surface and owns only live console/selection/shutdown lifecycle; startup composes its shutdown-linearized transition gate with v2 cleanup, prunes stale source associations without VFS refresh, and creates no console; action-time stored targets exact-resolve before console acquisition; lazy acquisition is serialized by the existing per-source selection guard; explicit target choice updates/clears v3 before console-resource acquisition; datasource IDs use the maintained normalized stable-id rule while schema identity is preserved exactly; default-schema/unproven identity is non-persistable; live REUSE console lifecycle is not target-persistence authority; source-boundary evidence keeps `JdbcConsole.newConsole`, schema switching, script-model construction, write-command document injection, editor opening, and native query invocation out of `MyBatisExecuteProxyAction` and behind `DatabaseToolsConsoleAdapter`, while the adapter contains no target persistence store/bridge |
| Legacy persisted-target pre-execution revalidation | `LegacyActionTargetRevalidationContractTest`, `StoredExecutionTargetBridgeTest`, `ConsoleCacheServiceLifecycleTest` | the still-shipping action resolves persisted target authority before REUSE lookup, cache entries carry optional immutable `ExecutionTargetId`, cross-target mapper-key reuse is rejected, and persisted exact targets are re-resolved through parameter/preview/delayed Database Tools/native-query boundaries; pre-execution revalidation is non-mutating and ordinary resolver failure fails closed, while default-schema or stable-id-unavailable in-process targets remain explicitly outside this persisted-target proof |
| Database Tools handled-failure diagnostic severity | `DatabaseToolsDiagnosticSeverityContractTest`, `DatabaseToolsConsoleAdapterBoundaryTest` | adapter cancellation remains cancellation; ordinary handled `Exception` outcomes use non-fatal typed failure paths rather than `Logger.error`; non-`Exception` fatal `Throwable` remains error-level and is rethrown; cleanup does not swallow cancellation or JVM errors; this is a bounded adapter contract, not the complete #67 diagnostic taxonomy |
| Legacy evaluator handled-failure diagnostic severity | `LegacyEvaluationDiagnosticSeverityContractTest`, `MyBatisEvaluatorNegativeBoundaryTest`, `LegacyEvaluationExecutionGuardContractTest` | direct legacy evaluator calls rethrow cancellation, preserve the existing ordinary-`Exception` compatibility string, and do not catch non-`Exception` fatal `Throwable`; the shipping action now consumes a separate explicit success/failure result so ordinary evaluation failures are WARN-level user-visible refusals rather than SQL candidates, while fatal throwables remain error-level and are rethrown; this is bounded temporary hardening only, and the direct evaluator compatibility/error-string architecture remains replacement/deletion debt under #64/#67 |
| Legacy action/presentation handled-failure diagnostic severity | `LegacyActionPresentationDiagnosticSeverityContractTest` | the still-shipping action preparation boundary and datasource chooser rethrow cancellation, keep ordinary `Exception` outcomes non-fatal, and rethrow non-`Exception` fatal `Throwable`; SQL formatting falls back only for ordinary `Exception`, never catches fatal throwables, and does not log exception messages that may contain source-derived SQL; this is bounded temporary hardening only and does not validate the legacy action/formatter architecture |
| Live console lifecycle registration and owned-resource disposal | `ConsoleCacheDiagnosticSeverityContractTest`, `ConsoleCacheServiceLifecycleTest`, `ConsoleCacheOwnedResourceDisposalPolicyTest`, `ConsoleCacheOwnedResourceDisposalContractTest` | project-scoped REUSE sentinel registration preserves cancellation/fatal semantics; superseded cache entries and service-owned cached consoles are detached under the lifecycle lock and disposed outside it; cleanup attempts all live resources while cancellation/fatal failures outrank ordinary cleanup exceptions and additional failures are suppressed; stale sentinel callbacks remain entry-identity-safe; this changes no target persistence authority and does not replace real Database Tools console-disposal/plugin-unload evidence |
| Database Tools execution resource liveness | `DatabaseToolsExecutionResourceValidityContractTest` | the shipping Database Tools adapter structurally rejects a disposed `JdbcConsole` before console document/file access, excludes disposed editors, rechecks console/editor liveness in the delayed editor-open callback and before native query invocation, restores the original console document if project/shutdown lifecycle closes after injection but before execution, and surfaces typed `ConsoleUnavailable`; this is source-level fail-closed evidence only and does not replace real IU/DataGrip console disposal/reuse runtime evidence |
| Real IDE parameter-to-native-query golden path | `ZMyBatisStarterDriverE2ETest` via `E2E / Starter / Driver E2E` | the exact packaged plugin runs in IntelliJ IDEA Ultimate 2026.2, a real project Database Tools H2 datasource is loaded from temporary-project `.idea/dataSources.xml` state, the registered action drives datasource/default-schema choice -> production parameter dialog -> production SQL preview -> native Database Tools execution, and the expected value is observed in a real `TableResultView`; the separate no-datasource process scenario remains fail-closed evidence. H2 is integration-test-only and this representative path does not prove broader DBMS/DataGrip/lifecycle compatibility |
| Legacy invocation source revision invalidation | `LegacyActionSourceRevisionGuardProjectFixtureTest`, `AnnotationSqlExtractorProjectFixtureTest`, `LegacyActionSourceRevisionGuardContractTest` | the still-shipping action refuses an uncommitted active editor/PSI boundary and also captures immutable revision metadata for cross-file Java annotation constants actually resolved by `AnnotationSqlExtractor`; cached dependency Documents require committed exact modification stamps, uncached dependencies use exact VFS revision authority, edit-then-revert/move/delete/authority takeover/revision drift fail closed, and the composed root/dependency guard is rechecked after parameter/preview/delayed Database Tools boundaries and immediately before native query; direct literal annotations retain an empty dependency set; this is temporary legacy hardening only and does not replace #62 source-graph authority, XML `<sql>/<include>` dependency resolution, or final production source cutover |

The old IntelliJ template rename test and evaluator debug/reproduction files were removed when the product-specific tests above replaced them.

## Sensitive logging evidence boundary

PR #117 removed the shipping action's known INFO-level interpolation of resolved parameter values, extracted/dialog values, and rendered SQL. `SensitiveLoggingContractTest` now follows the production source boundary rather than one legacy class: it scans logging-call windows across `src/main/kotlin` for direct interpolation of the maintained set of sensitive query/input variable forms, while explicitly allowing metadata-only `size`/`length` evidence.

This test is deliberately narrower than a semantic information-flow proof. It can falsify the known regression classes and catches the same sensitive variables if logging responsibility moves between production Kotlin classes, but it cannot prove safety for arbitrary future aliases, reflection, third-party logging, or runtime-generated sinks. Review remains responsible for applying the product privacy contract to new variables and new logging surfaces.

## Parameter provenance evidence boundary

`ParameterExtractorTest`, `ParameterExtractorBoundaryTest`, and `ParameterProvenanceBaselineTest` characterize the current **source-only** discovery heuristic. They do not establish MyBatis runtime caller-input provenance.

The direct boundary evidence is intentionally asymmetric:

- generated `paramN` names are filtered by the current extractor, while `argN` source identifiers are still returned like ordinary caller inputs;
- `list`, `collection`, and `array` are returned when those names appear in source, but the extractor cannot prove whether they are MyBatis runtime aliases or real mapper parameter names;
- `@Param` provenance is not available to this unit boundary because `ParameterExtractor` receives SQL/XML text only and has no mapper-method or annotation metadata;
- `#{}` and `${}` currently share the same discovery path, which is evidence of a missing provenance distinction rather than evidence that raw interpolation is a bound parameter;
- placeholders inside quoted SQL text and comments remain lexical false positives, while malformed placeholders without a closing brace are not discovered;
- the large repeated fixture proves deterministic sorted deduplication and stable structured-root classification only. It is not a runtime-parity or performance guarantee.

These cases are falsification fixtures for #61. The method/runtime provenance redesign remains owned by #63; downstream implementation must not preserve these heuristics as caller-input authority merely because the characterization tests are green.

## Java annotation parser/index evidence boundary

`AnnotationSqlExtractorTest` remains the fast PSI-interface contract. `AnnotationSqlExtractorProjectFixtureTest` adds a materially different proof path: it boots the IntelliJ Java code-insight fixture, installs separate project Java classes, parses a real mapper Java file, verifies `fixture.SqlConstants` is discoverable through `JavaPsiFacade` with project scope, resolves annotation value `PsiReferenceExpression`s to real `PsiField`s across files, and then invokes the production extractor.

The fixture covers both a direct constant annotation value and an ordered annotation array containing cross-file constants. This closes the specific parser/project-index evidence gap for constant resolution. `JavaActionContextProjectFixtureTest` separately exercises action-time source authority and the shipping history-key bridge. Canonical mapper-method identity remains owned by #62; the still-shipping legacy action now reuses its qualified mapper + complete parameter-type signature semantics for remembered-input isolation, while the final #62/#63 source/input architecture cutover remains separate.

## Java action source authority and overload identity evidence boundary

`JavaActionContextProjectFixtureTest` boots the same real Java code-insight environment but drives the production action boundaries. Its saved-state case creates two overloaded annotation mapper methods, moves the real editor caret to each overload, supplies project/editor/PSI data through an `AnActionEvent`, and asserts that production `MyBatisContextAnalyzer.analyze` classifies both positions as annotation context. It then invokes the production action extraction boundary and proves that each caret returns the exact SQL attached to that overloaded `PsiMethod`.

The same saved-state case exercises the remembered-parameter statement-key boundary. The two methods have distinct parameter signatures and distinct SQL, and the shipping keys are built from the qualified mapper type plus each real `PsiType.canonicalText` in declaration order (the lightweight fixture observes `find(int)` versus `find(String)`). The fixture writes separate raw history under those exact production keys and proves exact lookup does not cross-load values; the prior name-only key remains separate and is not consulted as fallback. XML history identity remains `file::statementId`. This is bounded legacy safety hardening, not the final #62 canonical source or #63 typed input/history architecture.

The unsaved-state case edits only the active editor `Document` from `SELECT saved` to the equal-length `SELECT draft` while retaining the existing `PsiJavaFile`. It proves all of the following before any explicit synchronization:

- `PsiDocumentManager.isCommitted(document)` becomes false;
- the editor `Document` contains the draft SQL while `getLastCommittedText(document)` still contains the saved SQL;
- production `MyBatisContextAnalyzer.analyze` still classifies the caret from the existing PSI;
- production `extractSqlContent` returns `SELECT saved`, not the draft text;
- invoking those production boundaries does not implicitly commit the mapper Document.

After the fixture explicitly calls `PsiDocumentManager.commitDocument(document)`, the same production analyzer/extractor sees `SELECT draft`. This remains characterization of the **shipping legacy path**: the production action does not itself establish an authoritative current-document PSI snapshot before reading Java PSI. It is not evidence that stale PSI is acceptable target behavior.

`ActiveEditorSourceSnapshotAdapterProjectFixtureTest` now proves a separate Leap adapter boundary that does establish the active `Document` as authoritative without committing PSI or saving the physical file. Its disk-backed case proves saved bytes remain unchanged while the captured immutable snapshot contains the unsaved draft and a changed revision. Its fail-closed cases cover a `Document` with no `VirtualFile`, mutation between the before/after revision reads, and caret evidence outside the captured content. The captured value retains only core/JDK values. This adapter is not yet wired into `MyBatisExecuteProxyAction`, so the legacy production characterization above and the new target-adapter evidence are intentionally both true at the same time.

`DependentMapperSourceSnapshotAdapterProjectFixtureTest` proves the next #62 host boundary for a **known dependent file**. It first proves that once a dependent mapper has a live loaded `Document`, an unsaved draft is captured instead of stale physical bytes while PSI remains uncommitted and the physical file remains unchanged. It separately proves the unloaded path uses a bounded `VfsUtilCore.loadText` probe, that VFS-backed and later Document-backed captures retain the same `SourceFileId`, that both authorities enforce the explicit caller-provided content bound, and that invalid, unreadable, or revision-changing source fails closed. The captured result retains only `SourceSnapshot`; dependency discovery itself remains outside this adapter.

`XmlMapperSourceDiscoveryTest` and `XmlMapperSourceDiscoveryExternalResolutionTest` prove the following immutable-source discovery slice. They feed only captured `SourceSnapshot` values into a JDK StAX parser configured with DTD support and external entities disabled, while a separate lexical start-tag scanner supplies exact source ranges and is cross-checked against the parser's start-element event sequence. The fixtures prove standard MyBatis DOCTYPE input does not require external resolution, an active loopback HTTP listener receives zero DTD connections, a nonexistent file DTD does not affect discovery, comments/CDATA cannot create ghost include references, repeated include occurrences remain distinct and source-ordered, multiline attributes and quoted `>` characters preserve exact ranges, declaration/include structural misuse and duplicate variants fail closed, internal DTD/entity declarations are rejected, standard `databaseId` statement/fragment variants survive discovery, and custom `lang` evidence remains explicit unsupported evidence. The returned model retains only core/JDK values.

`XmlStatementSourceGraphResolverTest` and `XmlStatementSourceGraphResolverAdversarialTest` prove the next immutable dependency-resolution slice. They supply only a canonical `XmlStatementId`, matching `SourceSnapshot` values, and their `XmlMapperDocumentDiscovery` results. The resolver requires exact source-file/revision alignment, resolves bare references in the owning namespace and qualified references by full canonical fragment name, follows nested cross-document fragments, preserves repeated and same-file include occurrences as distinct source-backed dependency edges, and emits a reachable-only `StatementSourceGraph` independent of caller collection ordering. Missing and ambiguous fragments, direct/indirect cycles, missing or mismatched captured evidence, property-substituted `${...}` refids, property-substituted mapper namespace/statement/fragment identifiers, dotted statement/fragment declaration ids, and reachable documents carrying unsupported custom `lang` evidence fail typed. Standard `databaseId` variants are target-selected instead: exact conventional ids win, missing authority/custom aliases fail narrowly, and known non-matching conventional variants can fall back to the default. Property-substituted identifiers are refused because MyBatis `XNode` applies configuration `PropertyParser` substitution to XML attributes while the #120 discovery model preserves raw source values. Dotted declaration ids are also refused rather than guessing MyBatis `applyCurrentNamespace` behavior, which accepts an already-current-namespace-qualified name and rejects other dotted element ids. Unsupported evidence remains conservatively document-scoped because #120 does not bind it to a declaration owner; unrelated unreachable documents do not contaminate a root graph. A 2,000-fragment acyclic fixture proves dependency traversal does not depend on JVM recursion depth. No mapper file discovery, IntelliJ state access, MyBatis evaluation, parameter derivation, or production action cutover is exercised by this boundary.

## Java direct-annotation capture evidence boundary

`JavaAnnotationSourceCaptureAdapterProjectFixtureTest` and `JavaAnnotationSourceCaptureAdapterAdversarialProjectFixtureTest` exercise the new non-wired Leap Java source adapter in the real Java code-insight sandbox. Capture starts from the immutable active-editor `SourceSnapshot`, commits only the active `Document` into PSI for semantic interpretation, then re-captures and requires the same source revision/content/caret evidence. The unsaved-root fixture proves the resulting SQL comes from the draft `Document` while the physical file remains unchanged and unsaved.

The adapter constructs `JavaStatementId` from source identity, fully qualified mapper type, and ordered canonical parameter type identities, so overloaded methods remain distinct. It preserves direct annotation array order as `sqlSegments` and records declaration-ordered parameter metadata with source name, canonical type identity, and statically proven `@Param` alias. That metadata is evidence for #63 only; this boundary does not infer MyBatis `paramN`/`argN`, collection aliases, placeholder requirements, codecs, UI state, or remembered values.

Compile-time String constants are captured as source dependencies instead of being reduced to unproven text. Same-file and cross-file references become source-backed `SourceDependencyEdge`s, dependent files are captured through the existing live-`Document`-first/VFS-fallback adapter, and an unsaved dependent constant fixture proves the committed PSI value, immutable dependent snapshot, and returned SQL all come from the same draft while disk remains unchanged. Qualified references retain only the outer field reference as dependency evidence so class qualifiers are not misclassified as constants. Unsupported or unprovable constant expressions, dependent-source races/unavailability, and constant dependency cycles fail typed.

The adversarial Java fixtures also prove exact `StatementKind` mapping for all four Leap-v1 direct annotations and typed failures for missing method/direct annotation, unresolved `@Param` alias, unresolved parameter type, anonymous/unqualified mapper identity, provider annotations, custom `@Lang`, `@Select(affectData = ...)`, and unresolved annotation values. #252 promotes standard `databaseId` variants without coupling source capture to target state: repeated direct annotations and explicit `@Select.List` containers are captured as variants first; statement kind is preserved per variant (including cross-kind database-id variants), then `selectJavaAnnotationStatementVariant` applies PostgreSQL/Oracle target authority and rebuilds the selected root statement kind. Missing authority returns `AuthorityUnavailable`, and unknown aliases return `MappingUnproven` rather than silently falling back. `JavaAnnotationSourceCaptureKotlinBoundaryTest` still proves Kotlin direct annotations return explicit `UNSUPPORTED_KOTLIN_SOURCE`. The captured core model retains no IntelliJ objects.

This boundary still does not wire `MyBatisExecuteProxyAction` to Leap, prepare/evaluate MyBatis, derive the #63 parameter contract, execute against Database Tools, or prove rename/move/delete/pre-execution stale-source handling. Full production cutover, rename/move identity policy, and pre-execution source revalidation remain later #62/#66 obligations.

## Dynamic SQL evidence boundary

`MyBatisEvaluatorContractTest` plus `MyBatisEvaluatorDynamicTagBaselineTest` exercise at least one representative path for every standard dynamic tag named by the current evaluator (`if`, `choose`/`when`/`otherwise`, `foreach`, `where`, `set`, `trim`, `bind`). This is executable baseline evidence that those handlers are reached and that the tested representative semantics currently work. It is **not** enough by itself to claim every tag is unconditionally supported across all branches, malformed inputs, nested combinations, OGNL failures, or application-runtime semantics. zMyBatis still owns parameter discovery, direct legacy OGNL source-sanitization compatibility, and literal conversion; shipping-only evaluation no longer applies the OGNL-placeholder rewrite and now refuses unresolved bound-property paths while preserving explicit null, and Map/list property navigation itself no longer uses the removed custom global accessor.

The unresolved-`<include>` characterization is deliberately narrower. With the unit-test/default `ignoreUnknownTags=false` path it proves the current evaluator does not silently turn that dependency into ordinary SQL. The current outcome is still SQL-looking plugin-error text, not the Leap target's typed fail-closed result. The shipping evaluator still has no fragment/namespace dependency resolver; the new Leap source-graph resolver is deliberately non-wired and #64 remains responsible for consuming that graph through the isolated MyBatis boundary rather than preserving the legacy diagnostic string.

## Negative evaluator evidence boundary

`MyBatisEvaluatorNegativeBoundaryTest` is **characterization of current unsafe/degraded behavior**, not a target contract to preserve. It intentionally proves counterexamples that downstream architecture must eliminate:

- with `Ignore Unknown Tags` off, an unknown element becomes SQL-looking `-- [MyBatis Plugin Error]` diagnostic text rather than typed failure data;
- with `Ignore Unknown Tags` on, unknown wrappers are removed while their inner SQL survives, so unsupported syntax can silently change the source accepted by the evaluator;
- more critically, an unresolved `<include>` can be stripped entirely and the current evaluator can return truncated SQL such as `SELECT FROM users` instead of blocking on the missing dependency;
- Strict OGNL is not a universal fail-fast boundary: the current `kind == 'B'` coercion failure surfaces as `NumberFormatException("For input string: \"B\"")` and is converted to SQL-looking plugin-error text with Strict mode both OFF and ON because `isOgnlError` does not classify it;
- a failure that MyBatis wraps as `BuilderException("Error evaluating expression ...")` is converted to plugin-error text with Strict mode OFF and rethrown with Strict mode ON, showing that Strict behavior depends on the current exception classifier rather than on every expression failure;
- direct legacy evaluator calls still characterize unsupported direct collection/object literalization as marker-bearing `NULL` or guessed `toString()` SQL, but shipping-only `evaluateForExecution(...)` now rejects those values before they can continue toward preview/execution;
- direct legacy evaluation still turns an unresolved bound property into `NULL`, while shipping-only evaluation distinguishes absent flat/nested/indexed paths from explicit null and refuses the missing input before preview/execution;
- malformed XML likewise becomes SQL-looking plugin-error text in the default path.

These tests falsify any architecture assumption that today's evaluator already has the Leap typed preparation/materialization boundary or that compatibility switches define shipping safety. Direct `evaluate(...)` calls deliberately retain unsafe/degraded compatibility characterization, including unknown-tag stripping, OGNL-placeholder sanitization, missing-bound-parameter-to-`NULL`, and marker/`toString()` fallbacks, but shipping `evaluateForExecution(...)` disables unknown-tag stripping and OGNL-placeholder sanitization and rejects ordinary evaluator exceptions, unresolved bound-property paths, plus direct unsupported List/Map/other-object literalization. The worker stops before formatting, preview, or Database Tools execution. This does **not** make the retained direct-call compatibility or supported scalar literalization part of the Leap target architecture. #64 owns the typed evaluation/execution representation redesign; #66 owns the user confirmation/execution boundary.

## Mutation and raw interpolation safety evidence boundary

`MyBatisExecutionSafetyBaselineTest` characterizes the current evaluator-side safety gap without changing production behavior:

- SELECT, INSERT, UPDATE, DELETE, representative DDL, and an unclassified statement-shaped command all emerge as ordinary SQL `String` results; the evaluator is not a SQL safety classifier;
- mapper wrapper kind is not semantic proof: a `<select>` wrapper can evaluate to `DELETE`, and a `<delete>` wrapper can evaluate to `SELECT`;
- `${}` preserves raw statement-shaped text, including semicolon/comment-bearing text that can materially change the resulting SQL string.

This evidence must not be misread as an authorization or exploitability claim for every database/driver configuration. The Database Tools execution layer, target database, driver, and server settings ultimately determine what a particular SQL string can execute. Shipping evaluation carries a conservative source-level raw-interpolation confirmation flag, while the action separately classifies XML insert/update/delete and Java @Insert/@Update/@Delete declarations as requiring confirmation. Either condition forces the production SQL Preview even if the ordinary `sqlPreview` setting is false. The same formatted `pureSql` is shown in the dialog and passed to Database Tools only after Execute; cancelling a mandatory preview does not continue to native query execution. The packaged IU 2026.2 Starter/Driver fixtures prove both raw-interpolation and mutation-declaration paths against a real project datasource.

This closes the frozen #61 confirmation gap for explicit legacy mutation declarations without turning mapper kind into SQL authorization: the maintained E2E deliberately uses an `<update>` declaration whose rendered SQL is `SELECT`, and the existing baseline still proves wrapper kind does not constrain SQL semantics. The raw source-level flag is not the final #63 provenance model, declaration policy is not the final #64 typed statement-kind model, and unknown rendered SQL meaning is not guessed. For the Leap target, #64 still owns typed evaluation/representation boundaries and #66 owns the final confirmation/execution workflow redesign.

## Preview and execution boundary

`MyBatisEvaluatorContractTest` is authoritative for the existing SQL-string contract, with `MyBatisEvaluatorDynamicTagBaselineTest` adding representative tag/dependency characterization and `MyBatisEvaluatorNegativeBoundaryTest` showing why that untyped string contract is insufficient as a fail-closed execution boundary. The same evaluated SQL is expected to be the payload presented for preview and handed to the Database Tools execution path. The maintained Starter/Driver E2E now supplies the representative full click-through for one H2 process fixture: datasource choice, parameter entry, preview confirmation, native Database Tools execution, and result-grid observation. It remains separate from `./gradlew check` and does not replace broader DBMS/dialect, DataGrip, target-identity, or console-lifecycle evidence.

## Session and datasource platform gaps

The automated session tests deliberately avoid pretending to emulate JetBrains Database Tools identity semantics. They prove project-scoped persistence, stable serialized datasource identity, stale-index cleanup, and lifecycle ordering. The following remain platform-dependent and must be validated when changing the corresponding integration code:

- JetBrains datasource UUID lookup against real IDE datasource objects, including duplicate display names and datasource rename;
- missing/ambiguous datasource or schema resolution against a populated Database Tools model;
- actual deferred console acquisition and exact schema switching on the first explicit execution after an IDE restart;
- console disposal callbacks from the real Database Tools console implementation, including confirmation that ephemeral REUSE disposal cannot delete v3 target identity;
- end-to-end confirmation that startup target migration/pruning creates no console and never triggers statement execution;
- end-to-end production source-adapter cutover and orchestration of the now-proven source-graph revalidation adapter at every required #66 asynchronous/pre-execution boundary.

Those gaps are explicit so a green `Test` context is not misrepresented as evidence for behavior it does not execute.

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
without it; the engine remains standalone and unpackaged, and #258's `BOUND_EXECUTION_REQUIRED`
materialization boundary remains in force.

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
wrappers, if/set composition, other dynamic tags, nested or duplicate loops/wrappers, arbitrary
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
identity, per-invocation loader ownership and TCCL restoration. The engine remains standalone and
unpackaged; non-zero bindings still fail materialization with `BOUND_EXECUTION_REQUIRED` under #258.
No production action, target/console, execution or materialization authority is introduced.

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
invocations under poisoned parent OGNL exercise isolation and TCCL restoration. This adds no
production execution authority; non-zero bindings still require `BOUND_EXECUTION_REQUIRED`.

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
Boolean-if child; the separate foreach island above permits a sole direct foreach in WHERE trim. Native and
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
override tokens. Native where/set remain attribute-free, general trim is unsupported, and
non-zero bindings remain non-executable through `BOUND_EXECUTION_REQUIRED`.

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
