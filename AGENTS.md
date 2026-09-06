# zMyBatis Engineering Contract

This is the authoritative engineering and review contract for zMyBatis. It describes the **current enforced baseline** separately from **known product gaps** and from the Leap target architecture tracked by Epic #60. Do not turn a target rule into a claim about current behavior without evidence.

CI green is necessary evidence, never sufficient approval. Every PASS belongs to one exact final PR HEAD SHA. See [CONTRIBUTING.md](CONTRIBUTING.md) for contribution mechanics, [docs/test-contracts.md](docs/test-contracts.md) for executable product evidence, and [SECURITY.md](SECURITY.md) for vulnerability reporting.

## 1. Current product boundary

zMyBatis is an IntelliJ/DataGrip database plugin. `plugin.xml` depends on `com.intellij.database`.

The current execution path is broadly:

1. extract a mapper statement from supported XML or annotation source;
2. infer user-supplied parameters;
3. evaluate dynamic SQL with MyBatis `XMLScriptBuilder` plus zMyBatis-owned compatibility behavior;
4. render a zMyBatis literal SQL representation;
5. resolve datasource + explicit schema and reuse/create a JDBC console;
6. execute only after the explicit zMyBatis action.

Using MyBatis internally does **not** by itself prove stock MyBatis/JDBC parity. zMyBatis currently owns parameter discovery, OGNL/property-access behavior, unknown-tag compatibility handling, and literal rendering. Product/fidelity decisions and unsupported/degraded cases are owned by Leap #60, starting with #61.

Key current ownership areas:

- source/context extraction: `AnnotationSqlExtractor`, `MyBatisContextAnalyzer`;
- parameters: `ParameterExtractor`, `JsonParameterParser`, parameter UI/history;
- dynamic SQL/rendering: `MyBatisEvaluator`, `SqlFormatter`, preview;
- execution/DataGrip integration: `MyBatisExecuteProxyAction`;
- session identity/lifecycle: `ConsoleCacheService`, startup restoration;
- settings: `ZMyBatisSettings`, configurable UI.

## 2. Mapper, parameter, and SQL fidelity

- `#{...}` and `${...}` are distinct semantics. Never silently convert a bound placeholder into raw interpolation or vice versa.
- The current literal renderer is a zMyBatis product representation; do not describe it as JDBC/TypeHandler-equivalent without dedicated evidence.
- Strings, numbers, booleans, `null`, collections, nested objects, arrays, escaping, and indexed paths must preserve the contracts covered by `docs/test-contracts.md`.
- Internal MyBatis variables such as `<bind>` names and `foreach` item/index must not be invented as caller inputs.
- Unsupported or ambiguous source/input/evaluation behavior must not silently become plausible executable SQL. The stronger typed-failure architecture is owned by Leap #60/#64/#67.
- Preview and execution must not silently diverge. Full Database Tools click-through fidelity remains a platform/integration evidence gap documented in `docs/test-contracts.md`.

### Known current privacy/diagnostic defect

Current `MyBatisExecuteProxyAction` still emits raw parameter values and rendered SQL at INFO level. That is a known defect tracked by Leap #60/#67, **not** an accepted logging policy and not evidence that the privacy boundary is already satisfied. New code must not add or widen sensitive-value logging.

## 3. DataGrip action and IDE boundary

zMyBatis must not replace, wrap, unregister, reorder, or intercept DataGrip built-in Execute/Explain/console actions.

- Platform action IDs remain untouched.
- `MyBatisActionInterceptorActivity` is session-restoration infrastructure despite its historical name; it is not a global action interceptor.
- `MyBatisExecuteProxyAction.getActionUpdateThread()` is BGT.
- Current `update()` unconditionally keeps the zMyBatis action enabled/visible. Whether context-sensitive enablement is the intended product behavior is tracked by Leap #61/#66; do not document the target as if it were current behavior.
- UI/console work belongs on the EDT; PSI reads obey IntelliJ read-action requirements; blocking work must not be moved onto the EDT.

## 4. IntelliJ / Database API compatibility

The plugin uses `com.intellij.database.*`, including APIs that can move between IDE releases.

- `pluginSinceBuild`, platform type/version, bundled database plugin declarations, and Plugin Verifier configuration form one compatibility contract.
- The required `Verify plugin` context is authoritative for its configured target. The deterministic automated verifier baseline is IntelliJ IDEA Ultimate 2025.3.3.
- DataGrip remains a product target, but a green verifier against IDEA Ultimate alone is not evidence of every DataGrip runtime path. Database Tools behavior that cannot be credibly covered by unit tests requires explicit platform/manual evidence.
- Raising the minimum IDE build or widening compatibility claims is a product/API decision, not a routine dependency bump.

## 5. Datasource / schema / session identity — hardened baseline

Executing correct SQL against the wrong datasource/schema is a higher-severity failure than refusing to execute.

The current v2 persistence baseline established by hardening #57 is:

- persistence is project-scoped; active state no longer uses `project.basePath.hashCode()` as a project namespace;
- restart restoration uses a stable IDE datasource UUID, never datasource display-name fallback;
- restart persistence requires an explicit named schema; `Use Default Schema` and datasources without a stable UUID are in-process only;
- missing or ambiguous datasource/schema identity and failed named-schema switching fail closed;
- legacy application-global hash/name records are deliberately not migrated or read because their original ownership cannot be proven safely;
- registration, disposal, stale cleanup, and shutdown ordering are serialized by the project lifecycle contract;
- interrupted persistence replacement must not resurrect an older datasource/schema identity;
- startup restoration may reconstruct state/consoles but must never execute SQL.

See [docs/session-persistence.md](docs/session-persistence.md) for the persistence contract and [docs/test-contracts.md](docs/test-contracts.md) for automated versus platform-dependent evidence.

## 6. Lifecycle and execution safety

Only the user's explicit zMyBatis execution action may execute SQL.

Extraction, parameter discovery, evaluation, preview, formatting, settings, diagnostics, and startup/session restoration must not execute a statement as a side effect.

- No auto-confirm, auto-retry, or auto-reexecute of failed statements.
- Project shutdown must win deterministically over queued restore/selection/execution work.
- Consoles, listeners, callbacks, dialogs, editors, PSI objects, and scheduled work must not retain disposed project state.
- Re-check project/lifecycle availability after asynchronous boundaries.
- Expected unsupported/degraded/user failures should not be promoted to IntelliJ fatal errors. The complete typed diagnostic model is owned by Leap #67.

## 7. Tests and evidence

The required `Test` context is product evidence, not a coverage percentage. The authoritative current contract map is [docs/test-contracts.md](docs/test-contracts.md).

Hardening #58/#79 removed the old IntelliJ template rename test and debug/reproduction-only evaluator evidence. Do not cite removed template fixtures, empty tests, `println`, or Kotlin/JVM `assert(...)` as product proof.

Baseline evidence for ordinary code changes is selected by the changed contract and normally includes:

- `./gradlew check`;
- `./gradlew buildPlugin`;
- `./gradlew verifyPlugin` when platform/API compatibility is plausibly affected;
- required CI/static-analysis gates.

Do not delete, ignore, soften, or bypass assertions/checks to obtain green CI. UNKNOWN/UNVERIFIED evidence is not a PASS.

## 8. CI / GitHub Actions — hardened baseline

The canonical merge policy is [`.github/merge-gate-policy.yml`](.github/merge-gate-policy.yml).

Current required contexts are exactly:

- `Build`
- `Test`
- `Inspect code`
- `Verify plugin`
- `Lint workflows`

The live `main protection` ruleset is expected to enforce those contexts strictly, squash-only linear history, required review-thread resolution, and no bypass actors. `.github/workflows/repository-settings-drift.yml` performs recurring fail-closed live readback against the checked-in policy.

Workflow trust-boundary rules:

- every third-party `uses:` reference is pinned to a full commit SHA;
- every scanned job has explicit effective permissions;
- PR-authored code cannot execute with write-scoped authority unless an exact audited event condition excludes PR execution;
- read-only checkout jobs use `persist-credentials: false`;
- `Inspect code` is the authoritative Qodana gate;
- `workflow-lint.yml` runs repository pin/trust/required-context/live-settings/release-provenance checks plus actionlint and zizmor;
- the checked-in negative controls must continue to fail for deliberately bad fixtures;
- `release.yml` is included in pinning, permission, actionlint, zizmor, and release-provenance review. It is **not** pending or exempt from hardening.

A manual UI-test workflow is not part of the current evidence architecture unless real UI tests exist and provide falsifiable product evidence. Do not retain template automation merely because it came from the upstream plugin template.

## 9. Version and release contract — hardened baseline

The active publication contract established by #56 is:

- ordinary PR/main validation is deterministic and non-publishing, using `0.0.0-dev` unless an explicit release version is supplied;
- publication identity is a protected Git tag matching `vMAJOR.MINOR.PATCH[-PRERELEASE]` with migration floor `v27.0.0`;
- the effective JetBrains plugin version is exactly the validated tag with one leading `v` removed;
- `release.yml` runs only from GitHub Release events and checks out the release tag;
- release provenance proves the tag commit is reachable from reviewed `main`;
- build/Plugin Verifier and artifact-version validation happen before signing and Marketplace publication;
- signing and publishing use that same effective version;
- ordinary `build.yml` does not create release identity or draft releases;
- live `publication tags` governance protects `refs/tags/v*` from update/deletion with no routine bypass while allowing new tag creation;
- `.github/workflow-policy/check_release_provenance.py` and its negative controls fail closed on release-contract drift.

JetBrains Marketplace `Source Code` and `License` are Marketplace-admin metadata rather than repository-controlled state. The maintainer has confirmed the canonical source URL and Apache-2.0 metadata; repository automation must not pretend it can mutate those fields.

## 10. Current product gaps versus Leap target

Hardening is a maintained baseline, not a declaration that the current product architecture is final. Epic #60 owns the next product/runtime architecture and may replace current classes/heuristics after preserving proven safety contracts.

Current known product gaps include, among others:

- raw parameter/rendered-SQL INFO logging (#67);
- current always-enabled action presentation and broader IDE orchestration policy (#61/#66);
- compatibility-altered OGNL/unknown-tag/literal-rendering behavior that must not be described as stock MyBatis/JDBC parity (#61/#64);
- error/degradation paths that still need typed failure outcomes (#64/#67);
- DataGrip/runtime integration evidence that is not exercised by the deterministic IDEA Ultimate Plugin Verifier target (#61/#67).

These are explicit, separately owned gaps. Do not silently "fix" product architecture inside a repository-governance PR, and do not claim they are already solved merely because repository hardening is green.

## 11. Review discipline

Review the exact final PR HEAD for:

- functional correctness and regressions;
- mapper/dynamic-SQL/parameter fidelity;
- datasource/schema/session identity and lifecycle;
- IntelliJ/DataGrip API, threading, and disposal boundaries;
- diagnostics/privacy/security behavior;
- compatibility and resource/performance risk;
- abstractions, duplication, complexity, dead code, template residue, and hacks;
- adversarial/negative evidence and remaining platform gaps;
- workflow/release/merge-gate integrity;
- diff scope and documentation consistency.

A PASS applies only to the reviewed HEAD SHA. Any HEAD movement invalidates it. Merge by squash only after fresh HEAD/main readback and exact-head approval. Post-merge validation must complete before the owning issue is closed.
