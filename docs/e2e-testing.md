# Starter / Driver process-level testing

Completed issue #130 established the repository-wide process-level integration/E2E boundary for zMyBatis. This document is the maintained operating contract for that harness.

The target proof path is:

`real IDE process -> exact packaged plugin -> controlled real project -> production service/action wiring -> observable result or refusal`

This layer complements, rather than replaces, core/unit tests and IntelliJ project fixtures. Detailed semantic edge cases remain at the lowest test layer that can prove them reliably.

## Maintained process scenarios

`ZMyBatisStarterDriverE2ETest` maintains three process-level scenarios against the maintained IntelliJ IDEA Ultimate 2026.2.3 target on JDK 25:

1. **Packaged-plugin / production-service happy path**
   - installs the exact `buildPlugin` archive into a separate IDE process;
   - copies the versioned sample project into a fresh temporary directory;
   - opens the project and waits for supported background-indicator readiness;
   - reaches the shipping `ZMyBatisSettings` application service over Driver JMX/RMI and asserts its typed production result.
2. **Registered-action / real Database Tools golden path**
   - starts the exact packaged plugin in a real IDE process with an integration-test-only H2 JDBC fixture registered as a local Database Tools driver;
   - prepares a real project-level Database Tools datasource through the IDE-supported `.idea/dataSources.xml` persistence boundary rather than a test adapter or internal Driver stub;
   - opens the canonical mapper and invokes the registered `zMyBatis.Execute` action;
   - selects the datasource/default-schema through the shipping chooser;
   - enters a scalar through the real `ParameterInputDialog`;
   - observes the real `SqlPreviewDialog` and requires the user-visible SQL to contain the resolved value;
   - clicks `Execute` and waits for a real Database Tools `TableResultView` cell containing the expected H2 result.
3. **Registered-action fail-closed path**
   - opens the same mapper fixture in a fresh IDE process without any datasource;
   - invokes the real production action from the supported statement context;
   - requires the production `zMyBatis: No Data Source` refusal instead of parameter evaluation or execution.

No production method exists solely for this harness. H2 is an `integrationTestImplementation` fixture only; before IDE startup, the test copies its JAR into that Starter run's isolated IDE config and records it as a local Driver Files library in `options/databaseDrivers.xml`. It is neither placed on `idea.additional.classpath` nor added to the shipped plugin dependency graph. The datasource itself is loaded from project `.idea/dataSources.xml` plus local datasource state, while Driver interacts only with the shipping zMyBatis service/action and visible product UI. The golden path therefore exercises Database Tools' own JDBC driver classloader instead of an internal datasource-construction stub or production E2E hook.

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
- the database golden path generates project-local `.idea/dataSources.xml` / `dataSources.local.xml` only inside that test's temporary project, and uses an in-memory H2 datasource as deterministic process-test infrastructure; its JDBC JAR is copied only into the isolated Starter IDE config and registered as a local Database Tools driver library, while the exact normal plugin ZIP remains unchanged;
- datasource choice, parameter input, preview confirmation, and result-table observation use maintained Driver component APIs rather than screen coordinates or correctness sleeps;
- readiness uses Driver `waitForIndicators` / bounded component waits, not correctness sleeps;
- Starter's `CIServer` integration is overridden so IDE-side exceptions/freezes reported by Starter fail the JUnit process instead of producing a false green;
- IDE startup failure, Driver communication loss, assertion failure, or process failure therefore fails the task.

## CI evidence

`.github/workflows/e2e.yml` exposes process evidence separately as `E2E / Starter / Driver E2E`.

For every run it records:

- the actual checked-out commit (`git rev-parse HEAD`);
- the workflow SHA supplied by GitHub;
- the exact plugin distribution path;
- the SHA-256 digest of that plugin ZIP.

On failure it archives the Gradle/JUnit result and Starter IDE logs when present. E2E remains separate from the required `Merge Gate`; the gate aggregates the ordinary Build, Test, Qodana, Plugin Verifier, workflow-policy, and Dependency Review component jobs.

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

The maintained H2 golden path proves one representative installed-plugin user journey through the current shipping action and native Database Tools query/result UI. It does **not** prove every DBMS/dialect, every datasource/schema identity case, DataGrip parity, console disposal/reuse lifecycle, or the final Leap execution architecture. Those remain explicit #67 compatibility/lifecycle obligations. Architecture Leap continues to add representative production-wired scenarios as #62-#66 boundaries become authoritative. The harness remains repository-wide after Leap and is extended according to the applicability rule above.
