# zMyBatis test contracts

The required `Test` CI context is product evidence, not a line-coverage target. `./gradlew check` must fail when one of the automated contracts below regresses. Template fixtures, empty files, debug-only reproductions, and `println` output are not considered evidence.

## Automated contract map

| Correctness boundary | Authoritative evidence | What it proves |
| --- | --- | --- |
| MyBatis parameter discovery | `ParameterExtractorTest`, `ParameterExtractorBoundaryTest`, `ParameterProvenanceBaselineTest` | scalar/object roots, bind/foreach exclusions, nested collections, indexed `#{}`/`${}` paths, placeholder options, generated-alias/collection-alias source heuristics, lexical false-positive boundaries, deterministic large-fixture deduplication |
| JSON parameter input | `JsonParameterTest`, `OgnlEvalTest` | the `parseValue()` path used by the parameter dialog, nested object/array values, malformed input, integer precision boundaries |
| Dynamic SQL and literal rendering | `MyBatisEvaluatorContractTest`, `MyBatisEvaluatorDynamicTagBaselineTest`, `OgnlEvalTest` | representative direct evaluator behavior for `if`, `choose`/`when`/`otherwise`, `foreach`, `where`, `set`, `trim`, and `bind`; nested OGNL; `#{}` vs `${}`; quote escaping; NULL/boolean literals; visible current failure markers for unsupported direct list/map values |
| Negative evaluator boundaries | `MyBatisEvaluatorNegativeBoundaryTest` | current classified-vs-unclassified OGNL failure behavior under Strict mode, unknown-tag stripping, unresolved-include truncation under compatibility mode, malformed XML diagnostic strings, and executable-looking unsupported-value markers |
| Mutation/raw interpolation safety baseline | `MyBatisExecutionSafetyBaselineTest` | common read/mutation/DDL/unclassified SQL families remain ordinary evaluator strings, statement wrapper kind does not constrain SQL semantics, and `${}` preserves statement-shaped raw text without a typed safety boundary |
| Mapper dependency baseline | `MyBatisEvaluatorDynamicTagBaselineTest`, `MyBatisEvaluatorNegativeBoundaryTest` | unresolved `<include>` behavior differs materially by compatibility setting: default mode returns current plugin-error text while `Ignore Unknown Tags` can strip the dependency and yield truncated SQL text |
| Annotation SQL extraction | `AnnotationSqlExtractorTest`, `AnnotationSqlExtractorProjectFixtureTest` | literal/array/constant shapes at the PSI-interface contract plus real Java PSI/project-index resolution of cross-file constant references, including ordered constant arrays |
| Java action source authority / overload identity | `JavaActionContextProjectFixtureTest` | a real saved Java editor/caret selects the exact overloaded `PsiMethod` and annotation SQL; the still-shipping production analyzer/extractor continues to read last-committed PSI until explicit `commitDocument`; distinct overloads still collapse to the same legacy `file::Class#method` key |
| Leap active-editor snapshot boundary | `ActiveEditorSourceSnapshotAdapterProjectFixtureTest` | the new non-wired source adapter captures saved and unsaved active `Document` text/revision/caret without committing PSI or saving disk, fails closed for missing file identity/source mutation/invalid caret evidence, and returns no retained IntelliJ platform object; it does not prove production action cutover or dependent-source resolution |
| Leap dependent mapper snapshot boundary | `DependentMapperSourceSnapshotAdapterProjectFixtureTest` | for one already-resolved dependent `VirtualFile`, a loaded unsaved `Document` wins over stale VFS/disk content; otherwise bounded VFS text is captured with the same source identity scheme; oversize/invalid/unreadable/racing source fails closed and no IntelliJ platform object is retained; it does not discover namespaces/includes or construct dependency graphs |
| Leap XML mapper discovery boundary | `XmlMapperSourceDiscoveryTest`, `XmlMapperSourceDiscoveryExternalResolutionTest` | immutable `SourceSnapshot` input is parsed without external DTD/entity resolution; mapper namespace, direct statement/fragment declarations, repeated nested `<include refid>` occurrences, exact source-backed start-tag ranges, and `databaseId`/`lang` evidence are preserved; malformed/unsafe/duplicate/structurally invalid source fails closed; cross-document resolution and graph construction are not claimed |
| Leap XML source-graph resolution boundary | `XmlStatementSourceGraphResolverTest`, `XmlStatementSourceGraphResolverAdversarialTest` | canonical XML root identity plus immutable captured snapshots/discoveries resolve same-namespace and qualified includes into a reachable-only `StatementSourceGraph`; repeated/same-file edges are preserved; missing/ambiguous/cyclic/property-substituted-reference-or-identifier/dotted-declaration-id/input-mismatch/reachable-unsupported cases fail typed; deep chains use iterative traversal; no project/PSI/VFS/MyBatis/cutover behavior is claimed |
| Leap Java direct-annotation capture boundary | `JavaAnnotationSourceCaptureAdapterProjectFixtureTest`, `JavaAnnotationSourceCaptureAdapterAdversarialProjectFixtureTest`, `JavaAnnotationSourceCaptureKotlinBoundaryTest` | authoritative active `Document` source is synchronized to Java PSI without saving disk; direct `@Select`/`@Insert`/`@Update`/`@Delete` map to canonical overload-safe `JavaStatementId` values; ordered SQL segments, declaration-ordered method parameter/`@Param` metadata, and same-/cross-file constant source dependencies are retained; provider/Kotlin/`@Lang`/`databaseId`/`affectData`/repeatable-or-ambiguous/unresolved source fails typed; no parameter-contract, MyBatis preparation, or production cutover is claimed |
| Session persistence format and stale index recovery | `PersistedConsoleSessionTest`, `ConsoleCacheServicePersistenceTest` | versioned encoding, malformed/legacy/default-schema invalidation, interrupted-write pruning, shutdown lifecycle gating |

The old IntelliJ template rename test and evaluator debug/reproduction files were removed when the product-specific tests above replaced them.

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

The fixture covers both a direct constant annotation value and an ordered annotation array containing cross-file constants. This closes the specific parser/project-index evidence gap for constant resolution. `JavaActionContextProjectFixtureTest` separately exercises action-time source authority and legacy-key characterization. Canonical mapper-method identity is owned by #62 and now exists as a pure core contract; replacing the shipping legacy key construction remains #62 migration work, #63 consumes that identity for remembered-input isolation, and #67 verifies the final evidence rather than redesigning the identity.

## Java action source authority and overload identity evidence boundary

`JavaActionContextProjectFixtureTest` boots the same real Java code-insight environment but drives the production action boundaries. Its saved-state case creates two overloaded annotation mapper methods, moves the real editor caret to each overload, supplies project/editor/PSI data through an `AnActionEvent`, and asserts that production `MyBatisContextAnalyzer.analyze` classifies both positions as annotation context. It then invokes the production action extraction boundary and proves that each caret returns the exact SQL attached to that overloaded `PsiMethod`.

The same saved-state case deliberately exercises the current remembered-parameter statement-key boundary. The two methods have distinct parameter signatures and distinct SQL, but both keys are `/fixture/UserMapper.java::UserMapper#find`. This is executable characterization of a shipping canonical-identity defect, not a target contract: remembered inputs can currently alias across overloaded mapper methods because the action key omits the method signature. #62 owns replacing that legacy source/statement key construction with the canonical identity; #63 owns remembered-input identity/persistence on top of it; #67 only validates the final contract.

The unsaved-state case edits only the active editor `Document` from `SELECT saved` to the equal-length `SELECT draft` while retaining the existing `PsiJavaFile`. It proves all of the following before any explicit synchronization:

- `PsiDocumentManager.isCommitted(document)` becomes false;
- the editor `Document` contains the draft SQL while `getLastCommittedText(document)` still contains the saved SQL;
- production `MyBatisContextAnalyzer.analyze` still classifies the caret from the existing PSI;
- production `extractSqlContent` returns `SELECT saved`, not the draft text;
- invoking those production boundaries does not implicitly commit the mapper Document.

After the fixture explicitly calls `PsiDocumentManager.commitDocument(document)`, the same production analyzer/extractor sees `SELECT draft`. This remains characterization of the **shipping legacy path**: the production action does not itself establish an authoritative current-document PSI snapshot before reading Java PSI. It is not evidence that stale PSI is acceptable target behavior.

`ActiveEditorSourceSnapshotAdapterProjectFixtureTest` now proves a separate Leap adapter boundary that does establish the active `Document` as authoritative without committing PSI or saving the physical file. Its disk-backed case proves saved bytes remain unchanged while the captured immutable snapshot contains the unsaved draft and a changed revision. Its fail-closed cases cover a `Document` with no `VirtualFile`, mutation between the before/after revision reads, and caret evidence outside the captured content. The captured value retains only core/JDK values. This adapter is not yet wired into `MyBatisExecuteProxyAction`, so the legacy production characterization above and the new target-adapter evidence are intentionally both true at the same time.

`DependentMapperSourceSnapshotAdapterProjectFixtureTest` proves the next #62 host boundary for a **known dependent file**. It first proves that once a dependent mapper has a live loaded `Document`, an unsaved draft is captured instead of stale physical bytes while PSI remains uncommitted and the physical file remains unchanged. It separately proves the unloaded path uses a bounded `VfsUtilCore.loadText` probe, that VFS-backed and later Document-backed captures retain the same `SourceFileId`, that both authorities enforce the explicit caller-provided content bound, and that invalid, unreadable, or revision-changing source fails closed. The captured result retains only `SourceSnapshot`; dependency discovery itself remains outside this adapter.

`XmlMapperSourceDiscoveryTest` and `XmlMapperSourceDiscoveryExternalResolutionTest` prove the following immutable-source discovery slice. They feed only captured `SourceSnapshot` values into a JDK StAX parser configured with DTD support and external entities disabled, while a separate lexical start-tag scanner supplies exact source ranges and is cross-checked against the parser's start-element event sequence. The fixtures prove standard MyBatis DOCTYPE input does not require external resolution, an active loopback HTTP listener receives zero DTD connections, a nonexistent file DTD does not affect discovery, comments/CDATA cannot create ghost include references, repeated include occurrences remain distinct and source-ordered, multiline attributes and quoted `>` characters preserve exact ranges, declaration/include structural misuse and duplicate IDs fail closed, internal DTD/entity declarations are rejected, and `databaseId`/`lang` evidence survives discovery. The returned model retains only core/JDK values.

`XmlStatementSourceGraphResolverTest` and `XmlStatementSourceGraphResolverAdversarialTest` prove the next immutable dependency-resolution slice. They supply only a canonical `XmlStatementId`, matching `SourceSnapshot` values, and their `XmlMapperDocumentDiscovery` results. The resolver requires exact source-file/revision alignment, resolves bare references in the owning namespace and qualified references by full canonical fragment name, follows nested cross-document fragments, preserves repeated and same-file include occurrences as distinct source-backed dependency edges, and emits a reachable-only `StatementSourceGraph` independent of caller collection ordering. Missing and ambiguous fragments, direct/indirect cycles, missing or mismatched captured evidence, property-substituted `${...}` refids, property-substituted mapper namespace/statement/fragment identifiers, dotted statement/fragment declaration ids, and reachable documents carrying unsupported `databaseId`/`lang` evidence fail typed. Property-substituted identifiers are refused because MyBatis `XNode` applies configuration `PropertyParser` substitution to XML attributes while the #120 discovery model preserves raw source values. Dotted declaration ids are also refused rather than guessing MyBatis `applyCurrentNamespace` behavior, which accepts an already-current-namespace-qualified name and rejects other dotted element ids. Unsupported evidence remains conservatively document-scoped because #120 does not bind it to a declaration owner; unrelated unreachable documents do not contaminate a root graph. A 2,000-fragment acyclic fixture proves dependency traversal does not depend on JVM recursion depth. No mapper file discovery, IntelliJ state access, MyBatis evaluation, parameter derivation, or production action cutover is exercised by this boundary.

## Java direct-annotation capture evidence boundary

`JavaAnnotationSourceCaptureAdapterProjectFixtureTest` and `JavaAnnotationSourceCaptureAdapterAdversarialProjectFixtureTest` exercise the new non-wired Leap Java source adapter in the real Java code-insight sandbox. Capture starts from the immutable active-editor `SourceSnapshot`, commits only the active `Document` into PSI for semantic interpretation, then re-captures and requires the same source revision/content/caret evidence. The unsaved-root fixture proves the resulting SQL comes from the draft `Document` while the physical file remains unchanged and unsaved.

The adapter constructs `JavaStatementId` from source identity, fully qualified mapper type, and ordered canonical parameter type identities, so overloaded methods remain distinct. It preserves direct annotation array order as `sqlSegments` and records declaration-ordered parameter metadata with source name, canonical type identity, and statically proven `@Param` alias. That metadata is evidence for #63 only; this boundary does not infer MyBatis `paramN`/`argN`, collection aliases, placeholder requirements, codecs, UI state, or remembered values.

Compile-time String constants are captured as source dependencies instead of being reduced to unproven text. Same-file and cross-file references become source-backed `SourceDependencyEdge`s, dependent files are captured through the existing live-`Document`-first/VFS-fallback adapter, and an unsaved dependent constant fixture proves the committed PSI value, immutable dependent snapshot, and returned SQL all come from the same draft while disk remains unchanged. Qualified references retain only the outer field reference as dependency evidence so class qualifiers are not misclassified as constants. Unsupported or unprovable constant expressions, dependent-source races/unavailability, and constant dependency cycles fail typed.

The adversarial Java fixtures also prove exact `StatementKind` mapping for all four Leap-v1 direct annotations and typed failures for missing method/direct annotation, unresolved `@Param` alias, unresolved parameter type, anonymous/unqualified mapper identity, provider annotations, multiple direct statement annotations, custom `@Lang`, and unresolved annotation values. MyBatis direct annotations also carry semantic variants that this Leap-v1 source contract does not yet model: an explicitly declared `databaseId` can change statement selection by target database, `@Select(affectData = ...)` can mark a SELECT annotation as data-affecting, and repeatable/container annotations can represent multiple statement declarations on one method. These forms therefore fail closed as `UNSUPPORTED_DATABASE_ID`, `UNSUPPORTED_AFFECT_DATA`, or `AMBIGUOUS_STATEMENT_ANNOTATION` rather than being collapsed into an ordinary direct statement. `JavaAnnotationSourceCaptureKotlinBoundaryTest` runs with the bundled Kotlin plugin and proves Kotlin direct annotations return explicit `UNSUPPORTED_KOTLIN_SOURCE`. The captured core model retains no IntelliJ objects.

This boundary still does not wire `MyBatisExecuteProxyAction` to Leap, prepare/evaluate MyBatis, derive the #63 parameter contract, execute against Database Tools, or prove rename/move/delete/pre-execution stale-source handling. Full production cutover, rename/move identity policy, and pre-execution source revalidation remain later #62/#66 obligations.

## Dynamic SQL evidence boundary

`MyBatisEvaluatorContractTest` plus `MyBatisEvaluatorDynamicTagBaselineTest` exercise at least one representative path for every standard dynamic tag named by the current evaluator (`if`, `choose`/`when`/`otherwise`, `foreach`, `where`, `set`, `trim`, `bind`). This is executable baseline evidence that those handlers are reached and that the tested representative semantics currently work. It is **not** enough by itself to claim every tag is unconditionally supported across all branches, malformed inputs, nested combinations, OGNL failures, or application-runtime semantics. zMyBatis still owns parameter discovery, OGNL compatibility behavior, and literal conversion.

The unresolved-`<include>` characterization is deliberately narrower. With the unit-test/default `ignoreUnknownTags=false` path it proves the current evaluator does not silently turn that dependency into ordinary SQL. The current outcome is still SQL-looking plugin-error text, not the Leap target's typed fail-closed result. The shipping evaluator still has no fragment/namespace dependency resolver; the new Leap source-graph resolver is deliberately non-wired and #64 remains responsible for consuming that graph through the isolated MyBatis boundary rather than preserving the legacy diagnostic string.

## Negative evaluator evidence boundary

`MyBatisEvaluatorNegativeBoundaryTest` is **characterization of current unsafe/degraded behavior**, not a target contract to preserve. It intentionally proves counterexamples that downstream architecture must eliminate:

- with `Ignore Unknown Tags` off, an unknown element becomes SQL-looking `-- [MyBatis Plugin Error]` diagnostic text rather than typed failure data;
- with `Ignore Unknown Tags` on, unknown wrappers are removed while their inner SQL survives, so unsupported syntax can silently change the source accepted by the evaluator;
- more critically, an unresolved `<include>` can be stripped entirely and the current evaluator can return truncated SQL such as `SELECT FROM users` instead of blocking on the missing dependency;
- Strict OGNL is not a universal fail-fast boundary: the current `kind == 'B'` coercion failure surfaces as `NumberFormatException("For input string: \"B\"")` and is converted to SQL-looking plugin-error text with Strict mode both OFF and ON because `isOgnlError` does not classify it;
- a failure that MyBatis wraps as `BuilderException("Error evaluating expression ...")` is converted to plugin-error text with Strict mode OFF and rethrown with Strict mode ON, showing that Strict behavior depends on the current exception classifier rather than on every expression failure;
- unsupported direct collection literalization remains an ordinary `String` containing an inline error marker plus `NULL`, so its type does not prevent it from continuing toward preview/execution;
- malformed XML likewise becomes SQL-looking plugin-error text in the default path.

These tests falsify any architecture assumption that today's evaluator already has a typed fail-closed boundary or that enabling Strict OGNL closes every expression-failure path. They do **not** make unknown-tag stripping, error-comment SQL, marker-bearing `NULL`, or exception propagation supported Leap behavior. #64 owns the typed evaluation/execution representation redesign; #66 owns the user confirmation/execution boundary.

## Mutation and raw interpolation safety evidence boundary

`MyBatisExecutionSafetyBaselineTest` characterizes the current evaluator-side safety gap without changing production behavior:

- SELECT, INSERT, UPDATE, DELETE, representative DDL, and an unclassified statement-shaped command all emerge as ordinary SQL `String` results; the evaluator is not a SQL safety classifier;
- mapper wrapper kind is not semantic proof: a `<select>` wrapper can evaluate to `DELETE`, and a `<delete>` wrapper can evaluate to `SELECT`;
- `${}` preserves raw statement-shaped text, including semicolon/comment-bearing text that can materially change the resulting SQL string.

This evidence must not be misread as an authorization or exploitability claim for every database/driver configuration. The Database Tools execution layer, target database, driver, and server settings ultimately determine what a particular SQL string can execute. The current action code separately shows that the evaluated/formatted `pureSql` is handed to the optional preview and execution path without a mutation- or raw-interpolation-specific confirmation branch; `sqlPreview` defaults to false. Full UI/Database Tools click-through remains a platform integration obligation rather than fake unit coverage.

For the Leap target, these counterexamples support the existing #61 policy: mutation/raw interpolation require explicit confirmation of the final authoritative representation, while unknown execution meaning must fail closed. #64 owns typed evaluation/representation boundaries and #66 owns confirmation/execution workflow redesign.

## Preview and execution boundary

`MyBatisEvaluatorContractTest` is authoritative for the existing SQL-string contract, with `MyBatisEvaluatorDynamicTagBaselineTest` adding representative tag/dependency characterization and `MyBatisEvaluatorNegativeBoundaryTest` showing why that untyped string contract is insufficient as a fail-closed execution boundary. The same evaluated SQL is expected to be the payload presented for preview and handed to the Database Tools execution path. A full click-through test of preview-dialog confirmation followed by real Database Tools execution is not part of `./gradlew check`; it depends on an IDE database connection and remains a platform/integration validation item.

## Session and datasource platform gaps

The automated session tests deliberately avoid pretending to emulate JetBrains Database Tools identity semantics. They prove project-scoped persistence, stable serialized datasource identity, stale-index cleanup, and lifecycle ordering. The following remain platform-dependent and must be validated when changing the corresponding integration code:

- JetBrains datasource UUID lookup against real IDE datasource objects, including duplicate display names and datasource rename;
- missing/ambiguous datasource or schema resolution against a populated Database Tools model;
- actual console recreation and schema switching across an IDE restart;
- console disposal callbacks from the real Database Tools console implementation;
- end-to-end confirmation that startup restoration never triggers statement execution;
- end-to-end production source-adapter cutover, rename/move identity behavior, and pre-execution source revalidation across the full persistence/execution workflow.

Those gaps are explicit so a green `Test` context is not misrepresented as evidence for behavior it does not execute.

## Other gates

`Inspect code`/Qodana and `Verify plugin` remain separate required evidence for static analysis and JetBrains compatibility. They do not substitute for the product assertions in `Test`.
