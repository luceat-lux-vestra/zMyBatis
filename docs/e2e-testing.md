# Starter / Driver process-level testing

Completed issue #130 established the repository-wide process-level integration/E2E boundary for zMyBatis. This document is the maintained operating contract for that harness.

The target proof path is:

`real IDE process -> exact packaged plugin -> controlled real project -> production service/action wiring -> observable result or refusal`

This layer complements, rather than replaces, core/unit tests and IntelliJ project fixtures. Detailed semantic edge cases remain at the lowest test layer that can prove them reliably.

## Maintained process scenarios

`ZMyBatisStarterDriverE2ETest` maintains focused process-level scenarios against the maintained IntelliJ IDEA Ultimate 2026.2.3 target on JDK 25:

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
3. **Registered-action / PostgreSQL BIGINT nonzero golden path**
   - reuses the same maintained PostgreSQL datasource creation boundary;
   - invokes the same registered `zMyBatis.Execute` action for a mapper with one `#{value,jdbcType=BIGINT}` binding and stock-MyBatis dynamic `<if>`;
   - selects the exact datasource + `public` schema, enters the scalar through `ContractParameterInputDialog`, and requires preview text to equal the independently materialized artifact;
   - executes positive and negative cases, observes native result-grid values, and verifies both exact SQL texts persist in native Database Tools history.
4. **Registered XML raw-interpolation confirmation path**
   - declares `${}` in the XML mapper while using a real Java mapper method for caller-input authority;
   - reuses the maintained PostgreSQL datasource/explicit-schema path;
   - verifies raw interpolation forces final-SQL preview even when the ordinary preview setting is disabled;
   - cancels at approval and proves no native result grid is created.
5. **Registered direct-Java-annotation raw-interpolation path**
   - opens a real Java mapper method with direct `@Select` plus explicit `@Param` provenance and invokes the registered `zMyBatis.Execute` action from that source;
   - uses the same maintained PostgreSQL datasource/explicit-schema lifecycle and keeps `sqlPreview=false` so raw interpolation itself must force approval;
   - cancels one exact raw artifact and proves it creates neither a native result grid nor native query-history entry;
   - invokes the same method again, proves the prior raw value was not retained, approves a different exact SQL artifact, observes its native result value, and verifies only the executed artifact reaches Database Tools history;
   - the build-tool-free Starter sample supplies minimal source stubs for the MyBatis annotation FQNs so Java PSI can resolve the direct annotation; real MyBatis 3.5.19 annotation resolution remains independently covered by the Java source-adapter project fixture.
6. **Registered-action fail-closed path**
   - opens the mapper fixture in a fresh IDE process without any datasource;
   - invokes the real production action from the supported statement context;
   - requires the typed `zMyBatis: Execution Target Unavailable` refusal instead of preparation or execution.

No production method exists solely for this harness. The process database stack uses only pinned pgJDBC on the `integrationTestImplementation` graph; before IDE startup, the test copies that JAR into the isolated Starter config and records it as a local Driver Files library. Driver then creates the datasource through maintained Database Tools UI, performs a real Test Connection, refreshes Database Tools, and selects explicit `public` through shipping UI. No H2-specific cold-datasource/introspection harness participates in #448 evidence.

## Local execution

Run the process suite separately from the normal fixture suite:

```bash
./gradlew integrationTest
```

The IntelliJ Platform Gradle Plugin's `testIdeUi` task supplies `path.to.build.plugin` from the `buildPlugin` archive. The test installs that exact archive with Starter before launching the IDE.

The first local run can download IDE/Starter artifacts. Semantic correctness of the scenarios does not depend on network access after required artifacts are provisioned.

## Isolation and readiness

- each test receives a fresh JUnit temporary directory;
- the checked-in `src/integrationTest/testProject` sample is copied into that directory before IDE startup, so IDE metadata cannot mutate the repository fixture or a later scenario;
- each scenario starts and closes its own IDE process through `runIdeWithDriver().useDriverAndCloseIde`;
- before opening the project, a separate no-project launch completes the supplied-license or native `StartTrial` activation in that scenario's isolated config, waits for Ultimate/plugin initialization, and proves activation state was persisted; the project launch then uses that config without overlapping project-tree reads with activation's dynamic scheme reload;
- the database scenarios create PostgreSQL datasource state only inside each temporary Starter project through maintained Database Tools UI; pinned pgJDBC is copied only into the isolated Starter IDE config and registered as a local Database Tools driver library, while the exact normal plugin ZIP remains unchanged;
- datasource choice, parameter input, preview confirmation, and result-table observation use maintained Driver component APIs rather than screen coordinates or correctness sleeps;
- readiness uses Driver `waitForIndicators` / bounded component waits, not correctness sleeps;
- Starter's `CIServer` integration is overridden so IDE-side exceptions/freezes reported by Starter fail the JUnit process instead of producing a false green;
- IDE startup failure, Driver communication loss, assertion failure, or process failure therefore fails the task.

## CI evidence

For pull requests, the canonical `.github/workflows/build.yml` DAG invokes `.github/workflows/e2e.yml` as the reusable `Starter / Driver E2E` job and aggregates its applicable result into `Merge Gate`. The E2E workflow remains independently triggerable only for trusted-main cache seeding and explicit manual recovery.

For every run it records:

- the actual checked-out commit (`git rev-parse HEAD`);
- the workflow SHA supplied by GitHub;
- the exact plugin distribution path;
- the SHA-256 digest of that plugin ZIP.

On failure it archives the Gradle/JUnit result plus bounded per-test IDE diagnostics (`log/idea.log` and screenshots when present); it deliberately does not archive the entire Starter test tree. E2E remains separate from the required `Merge Gate`; the gate aggregates the ordinary Build, Test, Qodana, Plugin Verifier, workflow-policy, and Dependency Review component jobs.

Issue #130 completed the harness and its initial repeatability rollout. The current `.github/merge-gate-policy.yml` does **not** make E2E an unconditional required status check for every PR; the workflow remains separately visible process-level evidence. Applicability is decided per change: when installed-plugin or user-path behavior is part of the changed invariant, the relevant Starter/Driver scenario must pass on the exact final PR HEAD before merge. A lower-level-only change may record E2E as not applicable with a layer-based reason.

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

The maintained registered-action suite now uses one PostgreSQL Database Tools stack for complementary production paths through the same Leap authority: the DB-neutral zero-binding artifact executes against an explicit PostgreSQL target, the named BIGINT nonzero slice proves contract input/materialization/native results/history, and the raw-interpolation scenario proves mandatory preview cancellation without native execution. It does **not** prove every DBMS/dialect, every datasource/schema identity case, DataGrip parity, or console disposal/reuse lifecycle; those remain explicit #67 compatibility/lifecycle obligations. The harness remains repository-wide and is extended according to the applicability rule above.
