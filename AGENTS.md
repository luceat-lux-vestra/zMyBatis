# zMyBatis Engineering Contract

This is the authoritative engineering and review contract for zMyBatis. It is deliberately about this plugin's real failure modes: MyBatis parsing/evaluation, SQL fidelity, DataGrip integration, datasource/schema/session identity, lifecycle, and repository/CI integrity.

CI green is necessary evidence, never sufficient approval. Every PASS belongs to one exact final PR HEAD SHA - see [CONTRIBUTING.md](CONTRIBUTING.md) for the PR workflow this implies, and [SECURITY.md](SECURITY.md) for how to report a vulnerability instead of opening a public PR.

## Product boundary

zMyBatis is an IntelliJ/DataGrip database plugin. `plugin.xml` depends on `com.intellij.database`.

The current execution path is:

1. extract a mapper statement from XML or supported Java MyBatis annotations;
2. discover candidate parameter inputs from source;
3. evaluate supported dynamic SQL through MyBatis `XMLScriptBuilder` plus zMyBatis-owned compatibility behavior;
4. render literal SQL with zMyBatis-owned rules for preview/execution;
5. resolve datasource + schema and reuse/create the correct JDBC console;
6. execute only after the explicit zMyBatis action.

Using MyBatis for script parsing does not by itself prove stock application MyBatis/JDBC/TypeHandler parity; parameter discovery, compatibility transformations, OGNL handling, and literal rendering remain zMyBatis-owned boundaries.

Key ownership areas:

- statement extraction: `AnnotationSqlExtractor`, `MyBatisContextAnalyzer`;
- parameters: `ParameterExtractor`, `JsonParameterParser`, parameter UI/history;
- dynamic SQL/rendering: `MyBatisEvaluator`, `SqlFormatter`, preview;
- execution/DataGrip integration: `MyBatisExecuteProxyAction`;
- session identity/lifecycle: `ConsoleCacheService`, startup restoration;
- settings: `ZMyBatisSettings`, configurable UI.

## 1. Mapper / XML extraction

- Recover the whole intended statement and nothing else, including nested MyBatis dynamic tags and multi-line annotation forms.
- Use MyBatis `XMLScriptBuilder` for supported dynamic tags, but do not treat that parser choice as proof that zMyBatis and the application have identical end-to-end runtime semantics.
- `Ignore Unknown Tags` may ignore an unknown wrapper but must preserve its text content; this is compatibility-altered behavior, not stock MyBatis semantics.
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
- `update()` stays cheap. The current implementation remains always enabled/visible; context-sensitive visibility/enabling is not current behavior and is tracked by Leap #66.
- Declare the appropriate `ActionUpdateThread`.

A regression that changes DataGrip's own behavior is more severe than zMyBatis failing explicitly.

## 4. IntelliJ / Database API compatibility

The plugin uses `com.intellij.database.*`, including APIs that can move between IDE releases.

- `pluginSinceBuild`, platform version/type, bundled database plugin declarations, and configured Plugin Verifier targets form one compatibility contract.
- The `Verify plugin` CI context is authoritative for the targets actually configured in `build.gradle.kts`. Do not infer broader host coverage from compile success.
- The maintained automated verifier target is currently IntelliJ IDEA Ultimate 2025.3.3; DataGrip remains a product integration target but does not yet have a separate maintained verifier/runtime evidence line.
- Raising the minimum IDE build is an API/product decision, not a routine dependency bump.
- Isolate new Database API usage so a future platform break has a bounded repair surface.
- Compile success alone does not prove runtime compatibility.

## 5. Datasource / schema / session identity

The authoritative execution identity is `(project, mapper file, datasource, schema)`.

Executing correct SQL against the wrong datasource/schema is the highest-severity product failure because it can affect production data.

Current hardened baseline from #57:

- session persistence is project-scoped rather than application-global project-hash namespacing;
- datasource restart identity uses the IDE-assigned stable UUID, never display-name fallback;
- missing/ambiguous datasource identity and missing/ambiguous/switch-failed schema restoration fail closed;
- legacy application-global `basePath.hashCode()` + datasource-name records are deliberately invalidated because their original identity cannot be proven safely;
- only explicit-schema sessions persist across restart; `Use Default Schema` is reusable in-process only;
- persistence lifecycle operations are serialized so stale cleanup cannot erase a newer replacement session.

Rules:

- Any key/namespace/datasource-lookup change is an identity migration and needs explicit backward-compatibility or deliberate-invalidation handling for persisted state.
- Never silently fall back to another datasource when the saved datasource is missing or ambiguous.
- Preserve project-scoped persistent state and stable platform datasource identities.
- Keep [docs/session-persistence.md](docs/session-persistence.md) aligned with the actual persisted format and invalidation policy.

## 6. Restart, cleanup, and lifecycle

- `pruneStaleIndex()` must reconcile persisted index/session data before restoration.
- Missing datasource/session data is removed, never redirected to a different datasource.
- Shutdown ordering is intentional: the project-closing flag must be established before delayed restoration can race with disposal.
- Console disposal clears its persisted session; shutdown persistence preserves still-live explicit-schema sessions for the next restart. Change both halves together.
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

Product-specific automated contracts and explicit platform-dependent gaps are mapped in [docs/test-contracts.md](docs/test-contracts.md). Parsing, parameter extraction, JSON input, dynamic SQL, annotation-extractor interfaces, and persistence-format behavior should use those falsifiable contracts; Database Tools/runtime gaps that cannot be credibly emulated remain explicit platform/manual evidence obligations.

The template rename/debug tests removed by #58 are historical only and must not be cited as current zMyBatis correctness coverage. Do not delete, ignore, or weaken meaningful assertions to obtain green CI.

The same rule applies to `.github/workflow-policy/`: a failing check gets fixed at the source, never suppressed, and its negative controls must keep failing on the fixtures under `fixtures/bad/` - if one of them stops failing, the check it targets has regressed, not the fixture.

## 9. CI / GitHub Actions

The canonical policy lives in [`.github/merge-gate-policy.yml`](.github/merge-gate-policy.yml); this section is a summary, not a second copy - if the two ever disagree, the policy file wins and this section is out of date.

- Third-party actions use immutable full commit SHAs with readable version comments (`.github/workflow-policy/check_pins.py`, run from `workflow-lint.yml`).
- Validation workflows use explicit read-only defaults. Any write grant is job-scoped, minimal, and justified (see `merge-gate-policy.yml`'s `privilegedJobs`).
- Every scanned job must have an explicit effective `permissions` declaration (R0). Missing workflow/job declarations never inherit mutable repository or organization defaults as an assumed read-only baseline.
- A job in a `pull_request` workflow must never both check out source and hold any effective `*: write` permission unless an exact audited event-name condition proves that job cannot execute for pull requests (R1). The default synthetic merge ref still contains PR-controlled changes, so this rule applies equally to same-repository and fork contributions.
- A job with no effective write permission must set `persist-credentials: false` on every `actions/checkout` step (R2). Relying on the checkout default leaves a usable token in `.git/config` for no reason.
- `Inspect code` is the authoritative Qodana gate; it holds no write permissions, so it cannot publish its own PR comment or check run - its own job conclusion is the signal, not an annotation.
- `workflow-lint.yml` applies pinned-action, trust-boundary, actionlint, and zizmor checks to the validation workflows and `release.yml`. In the same required `Lint workflows` job, repository-level required-context drift, live-settings static policy, and release-provenance checks also run fail-closed.
- Required status-check contexts must be the jobs listed in `merge-gate-policy.yml`'s `requiredStatusChecks`, must run unconditionally on every ordinary PR (the producing workflow must declare a `pull_request` trigger with no `paths`/`paths-ignore` filter, and the job must have no `if:` guard), and must not silently rename out from under that list - `check_required_contexts.py` enforces these invariants automatically.
- `release.yml` is not exempt from workflow security review: `check_release_provenance.py` / `test_release_provenance.py` own its tag/version/artifact publication contract, while the normal pin/trust/actionlint/zizmor checks cover its workflow structure.
- `repository-settings-drift.yml` owns recurring live readback of repository/ruleset/tag-governance settings; a static policy file is not a substitute for live evidence.

## 10. Version and release contract

The current hardened publication contract from #56 is:

- ordinary PR/main validation is deterministic and non-publishing; `build.gradle.kts` uses `0.0.0-dev` unless an explicit release version is supplied, and `build.yml` never creates release identity, release drafts, tags, or Marketplace publication;
- publication tags use strict `vMAJOR.MINOR.PATCH` SemVer with optional prerelease suffix; publication major `< 27` is rejected and the first stable migration line is `v27.0.0` / plugin version `27.0.0`;
- the effective plugin version is the validated publication tag with exactly one leading `v` removed; wall-clock/commit timestamps are not release identity;
- `release.yml` consumes an explicit GitHub Release tag, proves tag provenance and ancestry from reviewed `main`, builds/verifies the exact effective version, validates the distribution before irreversible publication, and uses that exact version for signing and Marketplace publication;
- the live `publication tags` ruleset protects `refs/tags/v*` from update/deletion with no bypass actors while allowing creation of new valid tags; recurring settings drift verifies that governance;
- already-published tags/versions are never rewritten for recovery;
- Marketplace Source Code and License are manual Marketplace-admin metadata and are set to the canonical `luceat-lux-vestra/zMyBatis` repository and Apache-2.0; historical non-SemVer releases mean Marketplace `SemVer Only` is intentionally not enabled as part of this migration.

Any release-path change must preserve one auditable chain: reviewed `main` commit -> immutable publication tag -> one effective version -> validated artifact -> signing input -> Marketplace publication/release metadata.

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
