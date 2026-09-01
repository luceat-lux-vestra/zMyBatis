# AGENTS.md — engineering contract for zMyBatis

This file is the working contract for anyone (human or agent) changing this repository. It is
deliberately about *this* plugin, not about IDE plugins in general. If a rule here does not
mention a MyBatis, DataGrip, or IntelliJ Platform concept, it probably does not belong here.

## What zMyBatis is

zMyBatis is a JetBrains **Database/DataGrip** plugin. `plugin.xml` hard-`depends` on
`com.intellij.database`; the plugin does not function without it.

The end-to-end path is:

```
XML mapper statement  ─┐
                       ├─► extract statement text ─► detect parameters ─► prompt user
@Select/@Insert/… ─────┘        (ParameterExtractor,      (ParameterInputDialog,
                                 MyBatisContextAnalyzer,   JsonParameterParser)
                                 AnnotationSqlExtractor)
                                          │
                                          ▼
                        evaluate dynamic SQL via MyBatis XMLScriptBuilder
                                    (MyBatisEvaluator)
                                          │
                                          ▼
                        bind values into literal SQL ─► optional format/preview
                                    (SqlFormatter, SqlPreviewDialog)
                                          │
                                          ▼
                        resolve datasource + schema, reuse or create JdbcConsole,
                        execute (MyBatisExecuteProxyAction, ConsoleCacheService)
```

Source layout:

| Area | Files |
|---|---|
| Statement extraction | `AnnotationSqlExtractor.kt`, `MyBatisContextAnalyzer.kt` |
| Parameters | `ParameterExtractor.kt`, `JsonParameterParser.kt`, `ParameterInputDialog.kt`, `settings/ParameterHistoryService.kt` |
| SQL evaluation / rendering | `MyBatisEvaluator.kt`, `SqlFormatter.kt`, `SqlPreviewDialog.kt` |
| Execution & DataGrip integration | `MyBatisExecuteProxyAction.kt` |
| Session identity & lifecycle | `services/ConsoleCacheService.kt`, `startup/MyBatisActionInterceptorActivity.kt` |
| Settings | `settings/ZMyBatisSettings.kt`, `settings/ZMyBatisConfigurable.kt` |

## Correctness boundaries

These are the places where a bug is expensive rather than annoying. A change that crosses one
of these lines needs an explicit justification in the pull request description, and a test or
a described manual reproduction — not just a green CI run.

### 1. MyBatis mapper / XML parsing correctness

Statement extraction must recover the *whole* statement and nothing else: the full body of a
`<select>/<insert>/<update>/<delete>` including nested `<if>`, `<choose>/<when>/<otherwise>`,
`<foreach>`, `<where>`, `<set>`, `<trim>`, `<bind>`, and the full string for annotation
mappers — including multi-line `@Select({...})` arrays and constant references such as
`@Select(SqlConstants.FIND_USER)`.

- Do not hand-roll a second dynamic-SQL engine. Evaluation goes through MyBatis'
  `XMLScriptBuilder` (`org.mybatis:mybatis:3.5.x`) so that zMyBatis and the application agree.
- `Ignore Unknown Tags` strips unrecognised tags but **preserves their text content**. Any
  change to that behaviour changes the rendered SQL and must be treated as a correctness change.
- `Strict OGNL Mode` decides whether an OGNL failure propagates or the block is skipped.
  Silently skipping a block that the application would have included produces SQL that looks
  fine and is wrong. Never widen a `catch` in the evaluation path to "make it work".
- Extraction runs against PSI. Read PSI under a read action, and do not assume a mapper file
  is well-formed — a partially typed statement must produce a clear message, not an exception
  dialog or a truncated statement.

### 2. Parameter binding / rendered SQL fidelity

zMyBatis substitutes **literal values** into the SQL it hands to the console. That means every
binding decision is a correctness *and* a safety decision.

- `#{...}` and `${...}` are not interchangeable. `#{}` is a bound value; `${}` is textual
  interpolation. Rendering must preserve that distinction and must never silently upgrade a
  `#{}` into a raw text splice.
- Literal rendering must be type- and quote-correct: strings, numbers, booleans, `null`,
  lists (`[1,2,3]`), nested objects and object arrays from the JSON editor. Quoting/escaping
  bugs here are SQL injection in the user's own hands — treat them as security bugs.
- `Empty Input Handling` (`NULL` vs `EMPTY_STRING`) changes result sets. Changing the default,
  or the set of inputs it applies to, is a behavioural change.
- Internal variables (`<bind>` names, `foreach` item/index) must stay excluded from the prompt.
  Prompting for them, or failing to exclude a newly supported construct, corrupts evaluation.
- What the preview dialog shows must be **exactly** what is executed. If preview and execution
  can diverge, that is a bug regardless of which one is "right".

### 3. Separation from DataGrip's built-in actions

zMyBatis adds `zMyBatis.Execute`. It does **not** replace, wrap, or reorder DataGrip's own
Execute, Explain Plan, or console actions, and the README promises exactly that.

- Do not override or unregister platform action IDs. Do not install global action interceptors
  that can change what DataGrip's own actions do.
- `MyBatisActionInterceptorActivity` is a startup activity for **session restoration**. Despite
  the name it must not become a mechanism for intercepting platform actions; if it grows that
  behaviour, rename it and justify it.
- `update()` must be cheap and must disable the action outside a MyBatis context, so the entry
  does not appear in unrelated editors. Declare the correct `ActionUpdateThread`.
- A regression here is user-visible as "DataGrip's Execute stopped behaving normally", which is
  far worse than zMyBatis failing outright.

### 4. Database API compatibility across IDE versions

The plugin uses `com.intellij.database.*` — `JdbcConsole`, `DbPsiFacade`, `DasUtil`,
`ObjectPath`, `SearchPath`. This API is not a stability-guaranteed public API and it moves
between releases.

- `gradle.properties` declares the target: `pluginSinceBuild = 253`, `platformType =
  IntellijIdeaUltimate`, `platformVersion = 2025.3.3`, with `com.intellij.database` as a
  bundled plugin dependency.
- The **Plugin Verifier** (`Verify plugin` job, `./gradlew verifyPlugin`, IDEs from
  `pluginVerification { ides { recommended() } }`) is the compatibility gate. Never lower its
  `failureLevel`, and never narrow the verified IDE set, to get past a failure. A verifier
  failure means the plugin would break on a supported IDE.
- Raising `pluginSinceBuild` or changing `platformVersion` is a compatibility decision, not a
  dependency bump. Say so in the PR.
- Prefer the narrowest Database API that does the job, and isolate new Database API calls so a
  future breaking change has one place to fix rather than fifteen.

### 5. Datasource / schema / session identity

`ConsoleCacheService` keys a cached `JdbcConsole` by a **file key** derived from the mapper
file, and persists `dsName ||| schemaName` in `PropertiesComponent` under
`zMyBatis.session.<fileKey>`, with a per-project index namespaced by `project.basePath.hashCode()`.

- Identity must be `(project, mapper file, datasource, schema)`. Executing a statement against
  the wrong datasource or the wrong schema is the single worst failure this plugin can have —
  it can mean writing to production. Any change to key derivation, to the `|||` separator, to
  the `PropertiesComponent` key format, or to the index namespace is an identity change and
  needs a migration story for values already on disk.
- `basePath.hashCode()` is a collision-prone namespace and `PropertiesComponent` is
  application-level. Two projects can share a namespace. Do not make this worse; prefer moving
  toward a project-scoped `PersistentStateComponent` over adding more keys to the flat store.
- `put()` deliberately creates the in-memory entry **and** persists in one call. Keep that
  atomicity — do not reintroduce separate `saveSession` / `addToIndex` call sites that can
  leave the index and the session data disagreeing.
- `findDataSourceByName` matches on the display name. Duplicate or renamed datasources
  therefore resolve ambiguously; treat any fix as an identity change per the rule above.

### 6. Session persistence across restart, and stale-session cleanup

- On startup, `pruneStaleIndex()` reconciles the index against saved session data and drops
  entries with no session (console closed while the IDE was down, or a crash). Restoration then
  re-creates consoles for the surviving keys.
- If the datasource named in a saved session no longer exists, the session and index entry are
  removed rather than restored against something else. Preserve that: **never fall back to a
  different datasource.**
- `projectClosing` sets the shutdown flag *before* restoration is scheduled, on purpose — the
  comment in `MyBatisActionInterceptorActivity` explains the window it closes. Do not move that
  registration inside the `invokeLater` lambda.
- Console dispose sentinels always clear the session; `markShuttingDown()`/`dispose()`
  re-persist still-live sessions so a restart restores them. Changing either half without the
  other produces sessions that either vanish on restart or accumulate forever.
- New persisted state must be prunable. Anything written under `zMyBatis.` must have a path
  that removes it.

### 7. Project disposal / plugin lifecycle

- `ConsoleCacheService` is a project-level `Disposable`. Everything it creates —
  `JdbcConsole`s, `CheckedDisposable` sentinels, listeners — must be parented to a disposable
  that dies with the project. A retained `Project`, `Editor`, `PsiFile`, or `JdbcConsole` is a
  memory leak that the platform will report as a leaked project in tests.
- `ProjectManagerListener` is registered with the project as parent disposable. Keep it that
  way; an application-level listener without a parent outlives the project.
- Check `project.isDisposed` before touching project services from anything scheduled
  (`invokeLater`, coroutines, callbacks). Restoration in particular runs after a suspension
  point and can land on a closing project.
- Respect threading: EDT for UI and console creation, read actions for PSI, and no blocking
  I/O on the EDT.
- The dialogs (`ParameterInputDialog`, `SqlPreviewDialog`) must not leak the editor or project
  after `dispose()`.

### 8. SQL execution safety

- The user's explicit `Execute (zMyBatis)` invocation is the only thing that may cause SQL to
  run. Nothing in extraction, parameter detection, evaluation, preview, formatting, settings,
  or startup restoration may execute SQL as a side effect. **Startup session restoration
  re-creates consoles; it must never run a statement.**
- zMyBatis does not restrict statement types — `insert`, `update`, and `delete` mappers are
  executed as written. That makes the datasource/schema identity rules in §5 and the preview
  fidelity rule in §2 the actual safety mechanism, not a convenience.
- Never auto-confirm, auto-retry, or re-execute on failure.
- Errors must be attributable: say whether the failure came from statement extraction, OGNL
  evaluation, parameter parsing, datasource resolution, or the database itself, and include the
  statement id where available. A generic "failed to execute" makes every one of the boundaries
  above unverifiable in the field. Log through `Logger.getInstance(...)`; never log parameter
  values or rendered SQL that may contain production data at `info` or above.

## Working rules

- **Scope.** Fix the reported defect. No speculative features, no broad refactors, no new
  abstraction layers in a bug fix.
- **Tests.** `./gradlew check` must pass. Do not delete, `@Ignore`, or weaken an assertion to
  get a green run. Parsing, parameter extraction, and SQL rendering changes are unit-testable
  without an IDE — test them (`ParameterExtractorTest`, `JsonParameterTest`, `OgnlEvalTest`).
  Note that `MyPluginTest` and `src/test/testData/rename/` are still template leftovers and
  test nothing about zMyBatis; replacing them is welcome, deleting them without replacement
  is not.
- **Static analysis.** Qodana runs on every PR and is configured to fail on Critical findings
  (`qodana.yml`). Suppressing a finding requires a comment saying why it is a false positive.
  Raising a threshold to get a green build is not an acceptable fix.
- **Versioning.** `build.gradle.kts` derives the version from `LocalDateTime.now()`
  (`yy.MM.dd.HHmmss`), so builds are not reproducible and the version does not identify a
  commit. Do not build release automation that assumes the version is stable or meaningful.
- **Release boundary.** `build.yml` validates and must never mutate release state. `release.yml`
  is the only workflow that publishes, and it runs only from a human-published GitHub Release.
  Distribution for this plugin is `zMyBatis-public` (`pluginRepositoryUrl` in
  `gradle.properties`), not this repository.
- **Changelog.** User-visible changes go in `CHANGELOG.md` under `Unreleased`; the plugin's
  `change-notes` are generated from it.
- **README is load-bearing.** `patchPluginXml` fails the build if the
  `<!-- Plugin description -->` markers are missing, and the text between them becomes the
  Marketplace description.

## CI and workflow security rules

- All third-party actions are pinned to **full-length commit SHAs** with the tag in a trailing
  comment. Dependabot updates both. Never reintroduce a mutable tag.
- Workflows default to `permissions: contents: read`. A write scope is added only at job level
  and only with a comment saying which step needs it.
- **`inspectCode` executes pull-request-authored build logic** (Qodana runs the project's own
  Gradle build). It must never hold a write scope. That is why the Qodana action no longer
  publishes its own check run or PR comment — the job conclusion is the gate.
- Checkouts set `persist-credentials: false` unless a trusted step genuinely needs to push;
  `release.yml` is the only justified exception and says so inline.
- Never interpolate `${{ ... }}` into a `run:` block. Pass through `env:` and quote it.
- `Workflow Lint` (`actionlint` + `zizmor`, every severity blocking) enforces the above and is
  fail-closed. Do not add `continue-on-error` to it or raise its severity floor.

## Reviewing a change

Review the **exact final HEAD**, not an intermediate commit. Check, in order:

1. Which of the eight boundaries above the diff touches, and whether the PR says so.
2. Rendered-SQL fidelity: does preview still equal execution?
3. Datasource/schema identity: can this route a statement to the wrong target?
4. Lifecycle: is everything new parented to a disposable, and is `isDisposed` checked?
5. Database API surface: anything new from `com.intellij.database.*`, and did the Plugin
   Verifier actually run against it?
6. Error paths: is a failure attributable to a stage, and does it avoid logging user data?
7. Workflow diffs: permissions, pins, and credential exposure.
8. Diff scope: anything in here that is not the stated fix?

CI passing is a precondition for review, not a substitute for it.
