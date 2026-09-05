# Test evidence

The required `Test` check is product evidence only when a named zMyBatis correctness contract is exercised. Template samples, empty test files, debug-only reproductions, and generic IntelliJ fixture tests are not counted as evidence.

## Automated contracts

| Contract | Authoritative evidence |
| --- | --- |
| Caller parameter discovery (`#{}` / `${}`), object roots, OGNL roots, `<bind>`, `<foreach>`, malformed placeholders | `ParameterExtractorTest`, `ParameterExtractorSafetyTest` |
| JSON values used by parameter input: nested objects/arrays, null/boolean/string, flattening, malformed input, numeric precision | `JsonParameterParserTest` |
| Dynamic MyBatis evaluation: `<if>`, `<foreach>`, wrapperless mixed SQL, `#{}` vs `${}`, SQL string escaping, NULL/boolean literals, non-scalar rejection, malformed/missing input failure | `MyBatisEvaluatorTest`, `OgnlEvalTest` |
| Java MyBatis annotation SQL extraction: literal, string array, constant reference | `AnnotationSqlExtractorTest` (IntelliJ platform fixture) |
| Restart persistence encoding, project-scoped storage, malformed/stale record pruning, explicit-schema requirement, shutdown cleanup ordering | `PersistedConsoleSessionTest`, `ConsoleCacheServicePersistenceTest` |

The required GitHub `Test` job runs `./gradlew check`, which executes these suites. A regression in a named contract must therefore fail the merge gate rather than rely on a debug print or manual observation.

## Deliberately removed non-evidence

- `MyPluginTest` and `src/test/testData/rename/*`: IntelliJ template XML/rename examples unrelated to zMyBatis.
- empty `JsonParameterTest`.
- `MyBatisEvaluatorExceptionDebug`: reproduction code without an assertion.
- `MyBatisEvaluatorDebug`: useful scenarios were promoted into `MyBatisEvaluatorTest` with explicit assertions and no debug output.

## Remaining platform/manual gaps

These are **not** claimed as automated proof by the current `Test` context:

- Full UI flow from editor action through SQL preview confirmation into a real DataGrip console/result grid.
- Preview-to-execution fidelity after IDE SQL formatting and `JdbcConsoleProvider` integration. Pure evaluator output is asserted, but the real console boundary remains platform-dependent.
- Real IDE restart with configured datasource UUID rename/removal and schema catalog changes. Persistence encoding/pruning/lifecycle decisions are automated; live Database model discovery is not.
- Real project close/disposal callback ordering under the IntelliJ lifecycle. The service state machine is tested, not a multi-process IDE restart.
- Kotlin annotation-source support. The current annotation extractor is Java PSI-based; this document does not treat Kotlin annotation extraction as proven.
- Settings-dependent `strictOgnlMode` and `ignoreUnknownTags` behavior in a fully initialized IDE service environment.

These gaps belong to platform/integration evidence or later architecture work; they must not be inferred from the pure/unit suites above.
