# Starter / Driver process-level testing

Completed issue #130 established the repository-wide process-level integration/E2E boundary for zMyBatis. This document is the maintained operating contract for that harness.

The target proof path is:

`real IDE process -> exact packaged plugin -> controlled real project -> production service/action wiring -> observable result or refusal`

This layer complements, rather than replaces, core/unit tests and IntelliJ project fixtures. Detailed semantic edge cases remain at the lowest test layer that can prove them reliably.

## Maintained process scenarios

`ZMyBatisStarterDriverE2ETest` targets the **actual IntelliJ IDEA Ultimate 2026.3** process on JDK 25. The exact packaged plugin must complete these maintained PostgreSQL scenarios before 2026.3 runtime support can be claimed:

1. **Packaged-plugin / production-service happy path**
   - installs the exact `buildPlugin` archive into a separate IDE process;
   - copies the versioned sample project into a fresh temporary directory;
   - opens the project and waits for supported background-indicator readiness;
   - reaches the shipping `ZMyBatisSettings` application service over Driver JMX/RMI and asserts its typed production result.
2. **Registered-action / PostgreSQL zero-binding golden path**
   - creates a real PostgreSQL datasource through Database Tools using the pinned pgJDBC fixture;
   - opens the canonical zero-binding mapper and invokes the registered `zMyBatis.Execute` action;
   - selects the exact datasource plus explicit `public` schema through the shipping chooser;
   - observes the real `SqlPreviewDialog` and requires its SQL to equal the exact DB-neutral zero-binding artifact text;
   - clicks `Execute` and waits for a real Database Tools `TableResultView` cell containing the expected value.
3. **Registered-action / multi-binding prepared golden path**
   - reuses the same maintained PostgreSQL datasource creation boundary;
   - invokes the registered `zMyBatis.Execute` action for a mapper whose one caller input expands to two ordered BIGINT JDBC bindings;
   - selects the exact datasource + `public` schema and requires approval preview to retain two `?` placeholders plus value-hidden binding metadata;
   - explicitly rejects literalized bound SQL;
   - executes through `DatabaseSessionPreparedBackend` and requires the bounded `zMyBatis: Query Result` presentation to contain both result values;
   - proves the bound path does not surface through the native Database Tools `TableResultView`.
4. **Promoted ordinary-mapper metadata / built-in parameterType path**
   - carries the historical #510 R05 claim into the exact packaged registered action;
   - declares `parameterType="long"` on the XML statement rather than stripping or rejecting it during whole-document preflight;
   - enters one scalar through the normal contract dialog and requires placeholder SQL plus Long/BIGINT/LongTypeHandler binding metadata;
   - executes through `DatabaseSessionPreparedBackend`, observes the zMyBatis prepared-result table, and rejects native-grid/literalized-SQL fallback.
5. **Target-derived databaseId selection path**
   - declares both PostgreSQL-specific and default variants for one XML statement id;
   - selects the real PostgreSQL datasource + explicit `public` schema through production UI;
   - requires preview/result `2620` from the PostgreSQL variant and rejects the default `2600` result.
6. **Mutation-declaration approval path**
   - declares an XML `<update>` while ordinary SQL Preview is disabled;
   - requires the final-plan preview anyway because statement kind is mutation evidence;
   - cancels approval and proves no Database Tools result grid is created.
7. **Unsupported Provider annotation path**
   - opens a real direct Java `@SelectProvider` method in the packaged IDE;
   - invokes the registered action and requires the explicit `zMyBatis: Provider Not Supported` notice;
   - no datasource or alternate execution route is involved.
8. **Registered XML raw-interpolation confirmation path**
   - declares `${}` in the XML mapper while using a real Java mapper method for caller-input authority;
   - reuses the maintained PostgreSQL datasource/explicit-schema path;
   - verifies raw interpolation forces final-SQL preview even when the ordinary preview setting is disabled;
   - cancels at approval and proves no native result grid is created.
9. **Registered direct-Java-annotation raw-interpolation path**
   - opens a real Java mapper method with direct `@Select` plus explicit `@Param` provenance and invokes the registered `zMyBatis.Execute` action from that source;
   - uses the same maintained PostgreSQL datasource/explicit-schema lifecycle and keeps `sqlPreview=false` so raw interpolation itself must force approval;
   - cancels one exact raw artifact and proves it creates neither a native result grid nor native query-history entry;
   - invokes the same method again, proves the prior raw value was not retained, approves a different exact SQL artifact, observes its native result value, and verifies only the executed artifact reaches Database Tools history;
   - the build-tool-free Starter sample supplies minimal source stubs for the MyBatis annotation FQNs so Java PSI can resolve the direct annotation; real MyBatis 3.6.0 annotation resolution remains independently covered by the Java source-adapter project fixture.
10. **Registered-action fail-closed no-datasource path**
   - opens the mapper fixture in a fresh IDE process without any datasource;
   - invokes the real production action from the supported statement context;
   - requires the typed `zMyBatis: Execution Target Unavailable` refusal instead of preparation or execution.
11. **Large multi-mapper / persisted-target registered-action regression**
   - generates 256 inert mapper XML documents across eight module-shaped resource trees plus one root mapper and one shared-fragment mapper;
   - uses qualified cross-document `<include>` references so the shipping action must traverse the real lazy project-expansion/source-selection boundary instead of the root-only fast path;
   - runs the zero-binding statement with ordinary SQL Preview disabled, selects PostgreSQL + explicit `public`, and requires native result value `7007`;
   - invokes a bound statement from the **same mapper file** so the exact persisted target must be reused with no datasource/schema chooser;
   - requires the parameter dialog to become reachable under that real-project mapper load, submits `41`, and requires native Database Tools result value `42`;
   - emits `ZMYBATIS_E2E_REAL_PROJECT_SCALE` observations for generated mapper count and visible-action phases. The bounded waits are liveness guards, not latency SLAs.

No production method exists solely for this harness. The process database stack uses only pinned pgJDBC on the `integrationTestImplementation` graph; before IDE startup, the test copies that JAR into the isolated Starter config and records it as a local Driver Files library. Driver then creates and persists the datasource through maintained Database Tools UI, refreshes Database Tools, and selects explicit `public` through shipping UI. Connectivity is proven by the downstream packaged zMyBatis execution reaching native Database Tools result/history evidence rather than by the separate asynchronous Test Connection UI lifecycle. No H2-specific cold-datasource/introspection harness participates in #448 evidence.

### Separate DataGrip host scenarios

`ZMyBatisDataGripStarterDriverE2ETest` is a separate host proof against DataGrip 2026.3. It never treats the IDEA Ultimate suite as DataGrip evidence.

It installs the exact same packaged plugin ZIP and proves three bounded claims:

1. **Packaged plugin/service smoke** — DataGrip starts under a strict Starter `CIServer` failure boundary with no IDEA-only exception exemption, the zMyBatis plugin loads, and the shipping `ZMyBatisSettings` service is callable.
2. **Registered XML action fail-closed path** — a real XML mapper is opened in DataGrip, the registered `zMyBatis.Execute` action is invoked, and with no datasource configured the shipping path reaches the typed `zMyBatis: Execution Target Unavailable` refusal without an IDE fatal error or stale dialog.
3. **Bounded XML prepared PostgreSQL path** — the DataGrip job starts the same pinned PostgreSQL image, registers a pinned local pgJDBC driver through Database Tools, selects the explicit `public` schema, and executes an XML-only `<bind>`-generated BIGINT binding through `DatabaseSessionPreparedBackend`. The preview must keep `?`, the zMyBatis prepared-result dialog must contain `41`, and the bound path must not surface as native `TableResultView` delivery.

Each scenario closes its DataGrip process through `useDriverAndCloseIde` and emits a `ZMYBATIS_HOST_EVIDENCE host=DATAGRIP` record with release, scenario, result, shutdown state and observed duration. These durations are observations, not latency SLAs.

This host proof deliberately does **not** claim Java-annotation parity, native Database Tools result/history parity, every DBMS/dialect, or IDEA's broader PostgreSQL scenario matrix. The prepared case is XML-only and uses zMyBatis result presentation. Java PSI availability is not inferred from IDEA Ultimate, and the maintained IU PostgreSQL golden path remains the full host authority. DataGrip 2026.3 Plugin Verifier evidence is required alongside this process proof; the minimum IDE build is 263.

## Local execution

Run each host suite separately from the normal fixture suite:

```bash
./gradlew integrationTest
./gradlew dataGripIntegrationTest
```

`integrationTest` is the IntelliJ IDEA Ultimate suite. `dataGripIntegrationTest` targets DataGrip 2026.3 and filters to the DataGrip-specific process class.

For local runs, the IntelliJ Platform Gradle Plugin's `testIdeUi` task still supplies `path.to.build.plugin` from `buildPlugin`. In PR CI, however, the canonical `Build` job checks out the exact PR head, builds one uniquely versioned `0.0.0-validation.sha<HEAD>` distribution, uploads that ZIP without unpacking it, and records its SHA-256 in `validation-candidate-manifest.txt`. IDEA and DataGrip download that same artifact, verify the manifest/hash, and set `ZMYBATIS_E2E_PLUGIN_ARCHIVE`; Starter installs that exact ZIP. The artifact from that Build run is therefore also the only valid human-smoke candidate. A standalone `workflow_dispatch` may build its own local validation candidate, but that standalone build is not a P5 human-handoff artifact.

The first local run can download IDE/Starter artifacts. Semantic correctness of the scenarios does not depend on network access after required artifacts are provisioned.

## Isolation and readiness

- each test receives a fresh JUnit temporary directory;
- the checked-in `src/integrationTest/testProject` sample is copied into that directory before IDE startup, so IDE metadata cannot mutate the repository fixture or a later scenario;
- each scenario starts and closes its own IDE process through `runIdeWithDriver().useDriverAndCloseIde`;
- IDEA Ultimate uses the isolated no-project activation seed and persists `idea.key` before project-tree reads begin; DataGrip uses Starter-managed licensing/bootstrap directly and explicitly verifies that the IDEA-specific `StartTrial` action is not a valid bootstrap assumption;
- the database scenarios create PostgreSQL datasource state only inside each temporary Starter project through maintained Database Tools UI; pinned pgJDBC is copied only into the isolated Starter IDE config and registered as a local Database Tools driver library, while the exact normal plugin ZIP remains unchanged;
- datasource choice, parameter input, preview confirmation, and result-table observation use maintained Driver component APIs rather than screen coordinates or correctness sleeps;
- readiness uses Driver `waitForIndicators` / bounded component waits, not correctness sleeps;
- Starter's `CIServer` integration is overridden so IDE-side exceptions/freezes reported by Starter fail the JUnit process instead of producing a false green;
- IDE startup failure, Driver communication loss, assertion failure, or process failure therefore fails the task.

## CI evidence

For pull requests, the canonical `.github/workflows/build.yml` DAG invokes `.github/workflows/e2e.yml` as the reusable `Starter / Driver E2E` job and aggregates its applicable result into `Merge Gate`. The E2E workflow remains independently triggerable only for trusted-main cache seeding and explicit manual recovery.

Each host manifest records:

- the actual checked-out commit (`git rev-parse HEAD`);
- the workflow SHA supplied by GitHub;
- the exact plugin distribution path;
- the SHA-256 digest of that plugin ZIP;
- the exact IDE release; the DataGrip manifest additionally records `host=DATAGRIP`.

The DataGrip job is permanently cache-read-only and never becomes a second protected-main cache writer.

On failure it archives the Gradle/JUnit result plus bounded per-test IDE diagnostics (`log/idea.log` and screenshots when present); it deliberately does not archive the entire Starter test tree. For pull requests, the canonical Build DAG feeds the applicable reusable Starter/Driver E2E result into `Merge Gate` alongside Build, Test, Qodana, Plugin Verifier, workflow-policy, dependency-submission validation, and Dependency Review.

Issue #130 completed the harness and its initial repeatability rollout. The ruleset still requires only the aggregate `Merge Gate` context rather than an unconditional standalone E2E status. E2E applicability is decided inside the Build DAG: ready non-doc PRs require the reusable Starter/Driver job, while non-applicable scope is represented explicitly rather than inferred from a missing check.

## E2E applicability for future work

Every feature, bug fix, refactor, dependency/platform migration, compatibility change, and production-wiring change must record one of these outcomes during review:

- **Not applicable** — the changed invariant is fully owned by a lower test layer; state the layer-based reason.
- **Existing process scenario applies** — identify and re-run the scenario on the exact final PR HEAD.
- **New or updated process scenario required** — add process evidence because existing scenarios do not prove the changed installed-plugin/user-path invariant.
- **Unknown / unverified** — fail closed; do not claim sufficient evidence.

Process-level consideration is normally required for action registration/wiring, service/plugin registration, installed-IDE lifecycle or threading behavior, compatibility that can differ from fixtures, Database Tools integration, packaging/resource changes, and user-visible execution/refusal workflows.

Pure value objects, deterministic source-graph algorithms, isolated parser semantics, and detailed parameter matrices normally remain lower-level unless evidence shows that installed-plugin wiring changes the relevant invariant.

## Scope discipline

Do not move stable semantic matrices into this suite merely to label them E2E. Prefer Driver API/service interaction to Swing traversal. UI interaction is used only when registration or visible product behavior is itself the claim.

The maintained IU registered-action suite uses one PostgreSQL Database Tools stack for complementary
production paths through the same execution-plan authority: the DB-neutral zero-binding plan executes
through the explicit native text backend, the multi-binding case proves prepared execution plus
zMyBatis result presentation, and raw-interpolation scenarios prove mandatory approval/non-retention
without weakening exact-text safety. P5 adds representative promoted parity acceptance on top of
those routing proofs.

That IU proof does not automatically generalize to DataGrip. The separate DataGrip suite currently
owns packaged-plugin/service loading and its explicitly maintained XML action boundary. P5 decides
whether one prepared XML case can be kept reliable on the maintained DataGrip host; Java-source
parity, every DBMS/dialect, and IDEA-only UX are not inferred from that host.
