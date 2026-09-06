# zMyBatis test contracts

The required `Test` CI context is product evidence, not a line-coverage target. `./gradlew check` must fail when one of the automated contracts below regresses. Template fixtures, empty files, debug-only reproductions, and `println` output are not considered evidence.

## Automated contract map

| Correctness boundary | Authoritative evidence | What it proves |
| --- | --- | --- |
| MyBatis parameter discovery | `ParameterExtractorTest`, `ParameterExtractorBoundaryTest`, `ParameterProvenanceBaselineTest` | scalar/object roots, bind/foreach exclusions, nested collections, indexed `#{}`/`${}` paths, placeholder options, generated-alias/collection-alias source heuristics, lexical false-positive boundaries, deterministic large-fixture deduplication |
| JSON parameter input | `JsonParameterTest`, `OgnlEvalTest` | the `parseValue()` path used by the parameter dialog, nested object/array values, malformed input, integer precision boundaries |
| Dynamic SQL and literal rendering | `MyBatisEvaluatorContractTest`, `MyBatisEvaluatorDynamicTagBaselineTest`, `OgnlEvalTest` | representative direct evaluator behavior for `if`, `choose`/`when`/`otherwise`, `foreach`, `where`, `set`, `trim`, and `bind`; nested OGNL; `#{}` vs `${}`; quote escaping; NULL/boolean literals; visible current failure markers for unsupported direct list/map values |
| Mapper dependency baseline | `MyBatisEvaluatorDynamicTagBaselineTest` | with the default/unit-test evaluator settings, an unresolved `<include>` is not rendered as ordinary self-contained SQL; it produces the current plugin-error text baseline pending the typed failure/source-dependency redesign owned by Leap |
| Annotation SQL extraction | `AnnotationSqlExtractorTest` | literal values, ordered arrays, constant-field references at the PSI-interface contract without bootstrapping an IDE fixture |
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

## Dynamic SQL evidence boundary

`MyBatisEvaluatorContractTest` plus `MyBatisEvaluatorDynamicTagBaselineTest` exercise at least one representative path for every standard dynamic tag named by the current evaluator (`if`, `choose`/`when`/`otherwise`, `foreach`, `where`, `set`, `trim`, `bind`). This is executable baseline evidence that those handlers are reached and that the tested representative semantics currently work. It is **not** enough by itself to claim every tag is unconditionally supported across all branches, malformed inputs, nested combinations, OGNL failures, or application-runtime semantics. zMyBatis still owns parameter discovery, OGNL compatibility behavior, and literal conversion.

The unresolved-`<include>` characterization is deliberately narrower. With the unit-test/default `ignoreUnknownTags=false` path it proves the current evaluator does not silently turn that dependency into ordinary SQL. The current outcome is still SQL-looking plugin-error text, not the Leap target's typed fail-closed result, and `Ignore Unknown Tags` is a separate compatibility-altered path that may strip unrecognized tags. There is still no fragment/namespace dependency resolver. #62/#64 remain responsible for replacing that boundary rather than preserving the diagnostic string.

## Preview and execution boundary

`MyBatisEvaluatorContractTest` is authoritative for the existing SQL-string contract, with `MyBatisEvaluatorDynamicTagBaselineTest` adding representative tag/dependency characterization. The same evaluated SQL is expected to be the payload presented for preview and handed to the Database Tools execution path. A full click-through test of preview-dialog confirmation followed by real Database Tools execution is not part of `./gradlew check`; it depends on an IDE database connection and remains a platform/integration validation item.

## Session and datasource platform gaps

The automated session tests deliberately avoid pretending to emulate JetBrains Database Tools identity semantics. They prove project-scoped persistence, stable serialized datasource identity, stale-index cleanup, and lifecycle ordering. The following remain platform-dependent and must be validated when changing the corresponding integration code:

- JetBrains datasource UUID lookup against real IDE datasource objects, including duplicate display names and datasource rename;
- missing/ambiguous datasource or schema resolution against a populated Database Tools model;
- actual console recreation and schema switching across an IDE restart;
- console disposal callbacks from the real Database Tools console implementation;
- end-to-end confirmation that startup restoration never triggers statement execution;
- real Java parser/index wiring for annotation extraction, including project-backed constant resolution.

Those gaps are explicit so a green `Test` context is not misrepresented as evidence for behavior it does not execute.

## Other gates

`Inspect code`/Qodana and `Verify plugin` remain separate required evidence for static analysis and JetBrains compatibility. They do not substitute for the product assertions in `Test`.
