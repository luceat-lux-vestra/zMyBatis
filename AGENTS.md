# zMyBatis Engineering Contract

This is the authoritative engineering and review contract for zMyBatis. It is deliberately about this plugin's real failure modes: MyBatis parsing/evaluation, SQL fidelity, DataGrip integration, datasource/schema/session identity, lifecycle, and repository/CI integrity.

CI green is necessary evidence, never sufficient approval. Every PASS belongs to one exact final PR HEAD SHA - see [CONTRIBUTING.md](CONTRIBUTING.md) for the PR workflow this implies, and [SECURITY.md](SECURITY.md) for how to report a vulnerability instead of opening a public PR.

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
- required workflow/static-analysis gates (`Lint workflows`, see section 9).

Parsing, parameter extraction, OGNL, and SQL rendering are unit-testable without a live IDE and should be tested there. Session/DataGrip lifecycle changes require platform/manual evidence appropriate to the behavior.

`MyPluginTest` and `src/test/testData/rename/` are template leftovers and do not count as zMyBatis correctness coverage. Replace them with meaningful tests rather than citing their existence as evidence.

Do not delete, ignore, or weaken assertions to obtain green CI. The same rule applies to `.github/workflow-policy/`: a failing check gets fixed at the source, never suppressed, and its `test_policy.py` negative controls must keep failing on the fixtures under `fixtures/bad/` - if one of them stops failing, the check it targets has regressed, not the fixture.

## 9. CI / GitHub Actions

The canonical policy lives in [`.github/merge-gate-policy.yml`](.github/merge-gate-policy.yml); this section is a summary, not a second copy - if the two ever disagree, the policy file wins and this section is out of date.

- Third-party actions use immutable full commit SHAs with readable version comments (`.github/workflow-policy/check_pins.py`, run from `workflow-lint.yml`).
- Validation workflows use explicit read-only defaults. Any write grant is job-scoped, minimal, and justified (see `merge-gate-policy.yml`'s `privilegedJobs`).
- Every scanned job must have an explicit effective `permissions` declaration (R0). Missing workflow/job declarations never inherit mutable repository or organization defaults as an assumed read-only baseline.
- A job in a `pull_request` workflow must never both check out source and hold any effective `*: write` permission unless an exact audited event-name condition proves that job cannot execute for pull requests (R1). The default synthetic merge ref still contains PR-controlled changes, so this rule applies equally to same-repository and fork contributions.
- A job with no effective write permission must set `persist-credentials: false` on every `actions/checkout` step (R2). Relying on the checkout default leaves a usable token in `.git/config` for no reason.
- `Inspect code` is the authoritative Qodana gate; it holds no write permissions, so it cannot publish its own PR comment or check run - its own job conclusion is the signal, not an annotation.
- Workflow static analysis (`workflow-lint.yml`: pin check, trust-boundary check, required-context drift check, actionlint, zizmor) is itself fail-closed, proven by the negative/positive controls in `test_policy.py`. Do not add `continue-on-error` to any of it, and do not narrow a check's file scope to make a finding disappear.
- Required status-check contexts must be the jobs listed in `merge-gate-policy.yml`'s `requiredStatusChecks`, must run unconditionally on every ordinary PR (the producing workflow must declare a `pull_request` trigger with no `paths`/`paths-ignore` filter, and the job must have no `if:` guard), and must not silently rename out from under that list - `check_required_contexts.py` enforces these invariants automatically.
- `release.yml` (JetBrains Marketplace publication) is intentionally out of scope for the checks above pending the separate release-provenance track (issue #56); see the scope note at the top of `workflow-lint.yml`. That exclusion is a recorded, deliberate decision, not an oversight - do not silently start "fixing" release.yml from inside an unrelated PR.

## 10. Version and release contract (current state, not yet hardened)

This section describes what is actually true on `main` today, not a target state - do not treat it as evidence that the release-provenance chain is closed.

- `build.gradle.kts` derives the plugin version from `LocalDateTime.now()` (`yy.MM.dd.HHmmss`) on every build. The same source/tag can therefore currently rebuild to a different effective Marketplace version. This is a known, tracked gap, not an oversight of this contract.
- `release.yml` checks out `github.event.release.tag_name` and publishes to JetBrains Marketplace, but does not yet establish an immutable tag -> reviewed-`main`-commit -> single effective version -> prevalidated artifact -> publication chain, and has known unresolved static-analysis findings (template-injection, cache-poisoning - see the `workflow-lint.yml` scope note).
- Closing this gap - deterministic release version identity, tag/main ancestry verification, artifact/version preflight before `publishPlugin`, and narrowing `release.yml`'s write scopes - is the explicit subject of the release-provenance track (issue #56). Do not fold that work into an unrelated PR, and do not treat this repository/CI trust-boundary contract as implying it is already done.
- `build.yml` validates code and stages (but never publishes) a draft release; `release.yml` is the only JetBrains Marketplace publication path in this repository.

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
- workflow/merge-gate policy integrity (section 9);
- diff scope and documentation consistency, including whether a change actually belongs in this PR or in a different tracked slice (see section 10 and `CONTRIBUTING.md`).

A PASS applies only to the reviewed HEAD SHA. Any commit after review invalidates the PASS. Squash merge only after exact-HEAD approval (see `merge-gate-policy.yml`'s `mergeStrategy`). Publication remains a separate explicit gate.
