# Starter / Driver process-level testing

Issue #130 owns the repository-wide process-level integration/E2E boundary for zMyBatis.

The target proof path is:

`real IDE process -> exact packaged plugin -> controlled real project -> production service/action wiring -> observable result or refusal`

This layer complements, rather than replaces, core/unit tests and IntelliJ project fixtures. Detailed semantic edge cases remain at the lowest test layer that can prove them reliably.

## Current bootstrap scenarios

`ZMyBatisStarterDriverE2ETest` currently establishes two small process-level scenarios against the maintained IntelliJ IDEA Ultimate target:

1. **Packaged-plugin / production-service happy path**
   - installs the exact `buildPlugin` archive into a separate IDE process;
   - copies the versioned sample project into a fresh temporary directory;
   - opens the project and waits for supported background-indicator readiness;
   - reaches the shipping `ZMyBatisSettings` application service over Driver JMX/RMI and asserts its typed production result.
2. **Registered-action fail-closed path**
   - opens the versioned `Query.xml` sample;
   - invokes the real `zMyBatis.Execute` action by its registered action ID;
   - with no datasource configured, requires the production action to reach its real `zMyBatis: No Data Source` refusal instead of evaluating or executing SQL.

No production method exists solely for this harness. The Driver service stub names the shipping production class by FQN and the action scenario invokes the registered production action.

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
- readiness uses Driver `waitForIndicators`, not correctness sleeps;
- Starter's `CIServer` integration is overridden so IDE-side exceptions/freezes reported by Starter fail the JUnit process instead of producing a false green;
- IDE startup failure, Driver communication loss, assertion failure, or process failure therefore fails the task.

## CI evidence

`.github/workflows/e2e.yml` exposes process evidence separately as `E2E / Starter / Driver E2E`.

For every run it records:

- the actual checked-out commit (`git rev-parse HEAD`);
- the workflow SHA supplied by GitHub;
- the exact plugin distribution path;
- the SHA-256 digest of that plugin ZIP.

On failure it archives the Gradle/JUnit result and Starter IDE logs when present. Existing `Build`, `Test`, `Inspect code`, `Verify plugin`, and `Lint workflows` checks are unchanged.

The E2E workflow is initially **characterization evidence, not a required merge context**. Issue #130 requires repeated CI execution to demonstrate acceptable determinism before any deliberate required-context promotion. A green single run is not sufficient evidence of stability.

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

Architecture Leap will add representative production-wired scenarios as #62-#66 become available. The harness remains repository-wide after Leap and is extended according to the applicability rule above.
