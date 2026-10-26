package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.execution.ExecutionTargetDescriptor
import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.ExplicitSchemaIdentity
import com.algorist.zMyBatis.core.execution.ResolvedExecutionTarget
import com.algorist.zMyBatis.core.execution.StableDataSourceId
import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.InputEnvironmentResult
import com.algorist.zMyBatis.core.input.JavaAnnotationParameterContractFactory
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.preparation.PreparationSource
import com.algorist.zMyBatis.core.source.CapturedStatement
import com.algorist.zMyBatis.core.source.JavaAnnotationStatementCapture
import com.algorist.zMyBatis.core.source.JavaStatementId
import com.algorist.zMyBatis.core.source.MethodSignature
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.StatementSourceGraph
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class LeapExecutionCandidateAdapterProjectFixtureTest : BasePlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    fun testBlockedCandidateRunsOffEdtAndLeavesEdtResponsive() = withProductionStyleBackgroundTasks {
        val inputs = candidateInputs()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val builderTimedOut = AtomicBoolean(false)
        val candidateWasOnEdt = AtomicBoolean(true)
        val result = CompletableFuture<LeapExecutionCandidateResult>()

        val builder: LeapExecutionCandidateBuilder =
            { _, _, _, target, _, checkCanceled ->
                checkCanceled()
                candidateWasOnEdt.set(ApplicationManager.getApplication().isDispatchThread)
                started.countDown()
                if (!release.await(FIXTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    builderTimedOut.set(true)
                }
                checkCanceled()
                LeapExecutionCandidateResult.TargetIdentityMismatch(target.targetId, null)
            }

        try {
            requestCandidate(
                adapter = LeapExecutionCandidateAdapter(project, builder),
                inputs = inputs,
                onResult = { candidate -> result.complete(candidate) },
                onCancelled = {
                    result.completeExceptionally(
                        AssertionError("uncancelled responsiveness fixture must not cancel"),
                    )
                },
            )

            assertTrue("candidate background task did not start", await(started))
            assertFalse("candidate builder must not run on EDT", candidateWasOnEdt.get())
            assertFalse("candidate must remain blocked until the fixture releases it", result.isDone)

            val edtSentinel = CompletableFuture<Unit>()
            ApplicationManager.getApplication().invokeLater(
                { edtSentinel.complete(Unit) },
                ModalityState.any(),
            )

            PlatformTestUtil.waitForFuture(edtSentinel, FIXTURE_TIMEOUT_MILLIS)
            assertFalse(
                "EDT sentinel must complete before blocked candidate finishes",
                result.isDone,
            )
        } finally {
            release.countDown()
        }

        val completed = PlatformTestUtil.waitForFuture(result, FIXTURE_TIMEOUT_MILLIS)
        assertTrue(completed is LeapExecutionCandidateResult.TargetIdentityMismatch)
        assertFalse("candidate fixture timed out waiting for deterministic release", builderTimedOut.get())
    }

    fun testProgressIndicatorCancellationPreventsNormalContinuation() = withProductionStyleBackgroundTasks {
        val inputs = candidateInputs()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val indicatorRef = AtomicReference<ProgressIndicator?>()
        val builderTimedOut = AtomicBoolean(false)
        val passedCancellationGate = AtomicBoolean(false)
        val normalContinuationReached = AtomicBoolean(false)
        val cancelled = CompletableFuture<Unit>()

        val builder: LeapExecutionCandidateBuilder =
            { _, _, _, target, _, checkCanceled ->
                checkCanceled()
                indicatorRef.set(ProgressManager.getInstance().progressIndicator)
                started.countDown()
                if (!release.await(FIXTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    builderTimedOut.set(true)
                }
                checkCanceled()
                passedCancellationGate.set(true)
                LeapExecutionCandidateResult.TargetIdentityMismatch(target.targetId, null)
            }

        try {
            requestCandidate(
                adapter = LeapExecutionCandidateAdapter(project, builder),
                inputs = inputs,
                onResult = {
                    normalContinuationReached.set(true)
                },
                onCancelled = {
                    cancelled.complete(Unit)
                },
            )

            assertTrue("candidate background task did not start", await(started))
            val indicator = indicatorRef.get()
            assertNotNull("background candidate must run under a ProgressIndicator", indicator)

            indicator!!.cancel()
        } finally {
            release.countDown()
        }

        PlatformTestUtil.waitForFuture(cancelled, FIXTURE_TIMEOUT_MILLIS)
        assertFalse("cancelled task must not emit a normal candidate result", normalContinuationReached.get())
        assertFalse("cancelled task must stop at the next bounded cancellation probe", passedCancellationGate.get())
        assertFalse("candidate fixture timed out waiting for deterministic release", builderTimedOut.get())
    }

    fun testInvocationInvalidationRejectsDelayedCandidateCompletion() = withProductionStyleBackgroundTasks {
        val inputs = candidateInputs()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val invocationActive = AtomicBoolean(true)
        val builderTimedOut = AtomicBoolean(false)
        val nonInvalidatedContinuationReached = AtomicBoolean(false)
        val observed = CompletableFuture<LeapExecutionCandidateResult>()

        val builder: LeapExecutionCandidateBuilder =
            { _, _, _, target, _, checkCanceled ->
                checkCanceled()
                started.countDown()
                if (!release.await(FIXTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    builderTimedOut.set(true)
                }
                checkCanceled()
                LeapExecutionCandidateResult.TargetIdentityMismatch(target.targetId, null)
            }

        try {
            requestCandidate(
                adapter = LeapExecutionCandidateAdapter(project, builder),
                inputs = inputs,
                isInvocationActive = invocationActive::get,
                onResult = { candidate ->
                    if (candidate !== LeapExecutionCandidateResult.InvocationInvalidated) {
                        nonInvalidatedContinuationReached.set(true)
                    }
                    observed.complete(candidate)
                },
                onCancelled = {
                    observed.completeExceptionally(
                        AssertionError("invalidation is not ProgressIndicator cancellation"),
                    )
                },
            )

            assertTrue("candidate background task did not start", await(started))
            invocationActive.set(false)
        } finally {
            release.countDown()
        }

        val completed = PlatformTestUtil.waitForFuture(observed, FIXTURE_TIMEOUT_MILLIS)
        assertSame(LeapExecutionCandidateResult.InvocationInvalidated, completed)
        assertFalse(
            "stale completion must not remain eligible for approval/native delivery",
            nonInvalidatedContinuationReached.get(),
        )
        assertFalse("candidate fixture timed out waiting for deterministic release", builderTimedOut.get())
    }

    private fun withProductionStyleBackgroundTasks(block: () -> Unit) {
        PlatformTestUtil.withSystemProperty<Nothing>(
            "intellij.progress.task.ignoreHeadless",
            "true",
        ) {
            block()
        }
    }

    private fun requestCandidate(
        adapter: LeapExecutionCandidateAdapter,
        inputs: CandidateInputs,
        isInvocationActive: () -> Boolean = { true },
        onResult: (LeapExecutionCandidateResult) -> Unit,
        onCancelled: () -> Unit,
    ) {
        runInEdtAndWait {
            adapter.requestCandidate(
                source = inputs.source,
                parameterContract = inputs.contract,
                inputEnvironment = inputs.environment,
                target = inputs.target,
                isInvocationActive = isInvocationActive,
                onResult = onResult,
                onCancelled = onCancelled,
            )
        }
    }

    private fun candidateInputs(): CandidateInputs {
        val file = SourceFileId("vfs:/fixture/Mapper.java")
        val sourceSnapshot = SourceSnapshot(
            fileId = file,
            revision = SourceRevision("document:464"),
            content = "package fixture; interface Mapper {}",
        )
        val statementId = JavaStatementId(
            sourceFileId = file,
            qualifiedMapperType = "fixture.Mapper",
            methodSignature = MethodSignature("find", emptyList()),
        )
        val capture = JavaAnnotationStatementCapture(
            sourceGraph = StatementSourceGraph(
                rootStatement = CapturedStatement(
                    id = statementId,
                    kind = StatementKind.SELECT,
                    sourceRange = SourceRange(0, sourceSnapshot.content.length),
                ),
                sourceSnapshots = listOf(sourceSnapshot),
                dependencies = emptyList(),
            ),
            sqlSegments = listOf("select 1"),
            parameters = emptyList(),
        )
        val source = PreparationSource.JavaAnnotation(capture)
        val contract = JavaAnnotationParameterContractFactory.build(capture)
        val environment = when (val result = InputEnvironment.validate(contract, emptyList())) {
            is InputEnvironmentResult.Success -> result.environment
            is InputEnvironmentResult.Failure ->
                error("fixture unexpectedly produced invalid empty input: ${result.failures}")
        }
        val target = ResolvedExecutionTarget(
            descriptor = ExecutionTargetDescriptor(
                targetId = ExecutionTargetId(
                    dataSourceId = StableDataSourceId("fixture-ds"),
                    schema = ExplicitSchemaIdentity("public"),
                ),
                dataSourceDisplayName = "Fixture",
            ),
        )

        return CandidateInputs(source, contract, environment, target)
    }

    private fun await(latch: CountDownLatch): Boolean =
        latch.await(FIXTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private data class CandidateInputs(
        val source: PreparationSource,
        val contract: ParameterContract,
        val environment: InputEnvironment,
        val target: ResolvedExecutionTarget,
    )

    companion object {
        private const val FIXTURE_TIMEOUT_SECONDS = 15L
        private const val FIXTURE_TIMEOUT_MILLIS = FIXTURE_TIMEOUT_SECONDS * 1_000
    }
}
