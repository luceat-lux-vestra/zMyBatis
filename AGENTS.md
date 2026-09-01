# zMyBatis Engineering Contract

This is the authoritative engineering and review contract for zMyBatis. It is deliberately about this plugin's real failure modes: MyBatis parsing/evaluation, SQL fidelity, DataGrip integration, datasource/schema/session identity, lifecycle, and release integrity.

CI green is necessary evidence, never sufficient approval. Every PASS belongs to one exact final PR HEAD SHA.

## Product boundary

zMyBatis is an IntelliJ/DataGrip database plugin. `plugin.xml` depends on `com.intellij.database`.

The execution path is:

1. extract a mapper statement from XML or MyBatis annotations;
2. identify user-supplied parameters;
3. evaluate dynamic SQL through MyBatis itself;
4. render literal SQL for preview/execution;
5. resolve datasource + schema and reuse/create the correct JDBC console;
6. execute only after the explicit zMyBatis action.

Key ownership areas:

- statement extraction: `AnnotationSqlExtractor`, `MyBatisContextAnalyzer`;
- parameters: `ParameterExtractor`, `JsonParameterParser`, parameter UI/history;
- dynamic SQL/rendering: `MyBatisEvaluator`, `SqlFormatter`, preview;
- execution/DataGrip integration: `MyBatisExecuteProxyAction`;
- session identity/lifecycle: `ConsoleCacheService`, startup restoration;
- settings: `ZMyBatisSettings`, configurable UI.

## 1. Mapper / XML extraction

- Recover the whole intended statement and nothing else, including nested MyBatis dynamic tags and multi-line annotation forms.
- Do not implement a second dynamic-SQL engine. Evaluation goes through MyBatis `XMLScriptBuilder` so zMyBatis and the application use the same semantics.
- `Ignore Unknown Tags` may ignore an unknown wrapper but must preserve its text content.
- `Strict OGNL Mode` must not silently turn evaluation errors into plausible-but-wrong SQL.
- PSI access follows IntelliJ read-action rules. Partially edited mapper files fail with a useful diagnostic rather than a truncated statement or generic exception.

## 2. Parameter binding / rendered SQL fidelity

Literal SQL is a correctness and safety boundary.

- `#{...}` and `${...}` are not interchangeable. Never silently turn a bound value into raw textual interpolation.
- Strings, numbers, booleans, `null`, collections, nested objects, and arrays must be rendered with correct SQL quoting/escaping semantics.
- Internal MyBatis variables such as `<bind>` names and `foreach` item/index stay out of user prompts.
- Empty-input policy changes (`NULL` vs empty string) are behavioral changes and require tests.
- The previewed SQL and the SQL handed to the JDBC console must be byte-for-byte the same authoritative rendered statement. A divergence is a merge blocker.
- Do not log parameter values or rendered production SQL at `info` or above.

## 3. DataGrip action isolation

zMyBatis adds its own Execute action. It must not replace, wrap, unregister, reorder, or intercept DataGrip's built-in Execute/Explain/console actions.

- Platform action IDs remain untouched.
- `MyBatisActionInterceptorActivity` is session-restoration infrastructure despite its historical name; do not turn it into a global action interceptor.
- `update()` stays cheap and disables zMyBatis outside a valid MyBatis context.
- Declare the appropriate `ActionUpdateThread`.

A regression that changes DataGrip's own behavior is more severe than zMyBatis failing explicitly.

## 4. IntelliJ / Database API compatibility

The plugin uses `com.intellij.database.*`, including APIs that can move between IDE releases.

- `pluginSinceBuild`, platform version/type, and bundled database plugin declarations form one compatibility contract.
- The `Verify plugin` CI context is authoritative compatibility evidence. Do not lower verifier failure severity or narrow the intended supported range merely to get green CI.
- Raising the minimum IDE build is an API/product decision, not a routine dependency bump.
- Isolate new Database API usage so a future platform break has a bounded repair surface.
- Compile success alone does not prove runtime compatibility.

## 5. Datasource / schema / session identity

The authoritative execution identity is `(project, mapper file, datasource, schema)`.

Executing correct SQL against the wrong datasource/schema is the highest-severity product failure because it can affect production data.

Current known risks:

- session persistence historically namespaces application-level `PropertiesComponent` data with `project.basePath.hashCode()`, which can collide;
- datasource restoration historically resolves by display name, which can be ambiguous after duplicate names or renames.

Rules:

- Any key/namespace/datasource-lookup change is an identity migration and needs explicit backward-compatibility handling for persisted state.
- Never silently fall back to another datasource when the saved datasource is missing or ambiguous.
- `ConsoleCacheService.put()` keeps in-memory and persisted session state atomic; do not split it back into independently failing save/index operations.
- Prefer project-scoped persistent state and stable platform identities over adding more application-global string keys.

## 6. Restart, cleanup, and lifecycle

- `pruneStaleIndex()` must reconcile persisted index/session data before restoration.
- Missing datasource/session data is removed, never redirected to a different datasource.
- Shutdown ordering is intentional: the project-closing flag must be established before delayed restoration can race with disposal.
- Console disposal clears its persisted session; shutdown persistence preserves still-live sessions for the next restart. Change both halves together.
- Every new persisted key has a deterministic cleanup path.
- `ConsoleCacheService` is project-scoped. Consoles, listeners, sentinels, callbacks, dialogs, and scheduled work must not retain disposed `Project`, `Editor`, `PsiFile`, or `JdbcConsole` instances.
- Re-check `project.isDisposed` after asynchronous/scheduled boundaries.
- UI/console work belongs on the EDT; PSI reads use read actions; blocking I/O does not run on the EDT.

## 7. SQL execution safety

Only the user's explicit `Execute (zMyBatis)` action may execute SQL.

Extraction, parameter detection, OGNL evaluation, preview, formatting, settings, and startup restoration must not execute a statement as a side effect.

- Startup restoration may recreate consoles but never run SQL.
- Do not auto-confirm, auto-retry, or auto-reexecute failed statements.
- Errors identify the stage that failed: extraction, parameter parsing, OGNL/evaluation, datasource/schema resolution, console setup, or database execution.
- Include mapper/statement identity where safe, but redact sensitive parameter/rendered SQL content.

## 8. Tests and evidence

Run the narrowest evidence that can falsify the changed contract.

Baseline for ordinary code changes:

- `./gradlew check`;
- `./gradlew buildPlugin`;
- `./gradlew verifyPlugin` where platform compatibility is relevant;
- required workflow/static-analysis gates.

Parsing, parameter extraction, OGNL, and SQL rendering are unit-testable without a live IDE and should be tested there. Session/DataGrip lifecycle changes require platform/manual evidence appropriate to the behavior.

`MyPluginTest` and `src/test/testData/rename/` are template leftovers and do not count as zMyBatis correctness coverage. Replace them with meaningful tests rather than citing their existence as evidence.

Do not delete, ignore, or weaken assertions to obtain green CI.

## 9. CI / GitHub Actions

- Third-party actions use immutable full commit SHAs with readable version comments.
- Workflows default to `contents: read`; write permission is job-scoped and justified.
- Checkout uses `persist-credentials: false` unless a narrowly trusted later step genuinely needs an ambient credential.
- A privileged workflow must never execute PR-head code with elevated permissions.
- `Inspect code` is the authoritative Qodana gate; annotation/comment side effects are not merge evidence.
- Qodana/scanner internal failure must fail the authoritative job. Thresholds are policy, not numbers to raise until green.
- Workflow static analysis itself is fail-closed and has a negative control.
- Required contexts must be emitted on every ordinary PR and must not disappear behind top-level path filters.

## 10. Version and release contract

Ordinary development builds may retain the historical timestamp fallback. Release builds are different: the release workflow passes `-PbuildVersion=<release-tag>`, and that effective version must control plugin metadata, the built distribution, Marketplace publication, and release metadata.

The release tag is therefore an immutable provenance identifier, not a convenient label.

- `build.yml` validates code and never mutates releases or publishes.
- `.github/workflows/release.yml` is the only JetBrains Marketplace publication path in this repository.
- GitHub release downloads are exposed through `zMyBatis-public`; the relationship between that public release channel and this private source/Marketplace publication must remain explicit.
- Before publication, the release workflow verifies required signing/publishing secrets, exact tag checkout, reachability from reviewed `main`, and built artifact/version identity.
- Artifact/version verification happens **before** `publishPlugin` because Marketplace publication is irreversible.
- Release tags must be protected against update/deletion before this contract is considered complete.
- Never rewrite a published tag or version as a recovery mechanism.
- No SBOM/attestation/release machinery is added solely for parity; provenance controls must protect a real distributed artifact.

## 11. Review discipline

Review the exact final PR HEAD for:

- functional correctness and regressions;
- mapper/dynamic-SQL and parameter-binding fidelity;
- datasource/schema/session identity;
- restart/rollback/disposal semantics;
- IntelliJ/DataGrip compatibility;
- architecture and ownership boundaries;
- error handling and diagnostics;
- SQL/privacy/security boundaries;
- performance/resource retention;
- abstractions, duplication, complexity, dead code, and hacks;
- edge cases and meaningful test coverage;
- workflow/release integrity;
- diff scope and documentation consistency.

A PASS applies only to the reviewed HEAD SHA. Any commit after review invalidates the PASS. Squash merge only after exact-HEAD approval. Publication remains a separate explicit gate.
