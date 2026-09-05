# zMyBatis test contracts

The required `Test` CI context is product evidence, not a line-coverage target. `./gradlew check` must fail when one of the automated contracts below regresses. Template fixtures, empty files, debug-only reproductions, and `println` output are not considered evidence.

## Automated contract map

| Correctness boundary | Authoritative evidence | What it proves |
| --- | --- | --- |
| MyBatis parameter discovery | `ParameterExtractorTest`, `ParameterExtractorBoundaryTest` | scalar/object roots, bind/foreach exclusions, nested collections, indexed `#{}`/`${}` paths, placeholder options |
| JSON parameter input | `JsonParameterTest`, `OgnlEvalTest` | the `parseValue()` path used by the parameter dialog, nested object/array values, malformed input, integer precision boundaries |
| Dynamic SQL and literal rendering | `MyBatisEvaluatorContractTest`, `OgnlEvalTest` | dynamic `if`/`where`/`foreach`, nested OGNL, `#{}` vs `${}`, quote escaping, NULL/boolean literals, visible failure for unsupported direct list/map values |
| Annotation SQL extraction | `AnnotationSqlExtractorTest` | literal annotation value, ordered string arrays, constant-field references through IntelliJ PSI |
| Session persistence format and stale index recovery | `PersistedConsoleSessionTest`, `ConsoleCacheServicePersistenceTest` | versioned encoding, malformed/legacy/default-schema invalidation, interrupted-write pruning, shutdown lifecycle gating |

The old IntelliJ template rename test and evaluator debug/reproduction files were removed when the product-specific tests above replaced them.

## Preview and execution boundary

`MyBatisEvaluatorContractTest` is authoritative for the SQL string produced from a mapper statement and parameters. The same evaluated SQL is expected to be the payload presented for preview and handed to the Database Tools execution path. A full click-through test of preview-dialog confirmation followed by real Database Tools execution is not part of `./gradlew check`; it depends on an IDE database connection and remains a platform/integration validation item.

## Session and datasource platform gaps

The automated session tests deliberately avoid pretending to emulate JetBrains Database Tools identity semantics. They prove project-scoped persistence, stable serialized datasource identity, stale-index cleanup, and lifecycle ordering. The following remain platform-dependent and must be validated when changing the corresponding integration code:

- JetBrains datasource UUID lookup against real IDE datasource objects, including duplicate display names and datasource rename;
- missing/ambiguous datasource or schema resolution against a populated Database Tools model;
- actual console recreation and schema switching across an IDE restart;
- console disposal callbacks from the real Database Tools console implementation;
- end-to-end confirmation that startup restoration never triggers statement execution.

Those gaps are explicit so a green `Test` context is not misrepresented as evidence for behavior it does not execute.

## Other gates

`Inspect code`/Qodana and `Verify plugin` remain separate required evidence for static analysis and JetBrains compatibility. They do not substitute for the product assertions in `Test`.
