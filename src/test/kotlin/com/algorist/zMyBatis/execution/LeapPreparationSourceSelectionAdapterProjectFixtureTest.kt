package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.XmlStatementId
import com.algorist.zMyBatis.source.XmlStatementSourceGraphFailure
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

class LeapPreparationSourceSelectionAdapterProjectFixtureTest : BasePlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    fun testBlockedXmlSelectionRunsOffEdtAndLeavesEdtResponsive() =
        withProductionStyleBackgroundTasks {
            val evidence = xmlEvidence()
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val selectorTimedOut = AtomicBoolean(false)
            val selectorWasOnEdt = AtomicBoolean(true)
            val result = CompletableFuture<LeapPreparationSourceSelectionTaskResult>()

            val selector: LeapPreparationSourceSelector = { current, _, checkCanceled ->
                checkCanceled()
                selectorWasOnEdt.set(ApplicationManager.getApplication().isDispatchThread)
                started.countDown()
                if (!release.await(FIXTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    selectorTimedOut.set(true)
                }
                checkCanceled()
                terminalSelection(current)
            }

            try {
                requestSelection(
                    adapter = LeapPreparationSourceSelectionAdapter(project, selector),
                    evidence = evidence,
                    onResult = result::complete,
                    onCancelled = {
                        result.completeExceptionally(
                            AssertionError("uncancelled source-selection fixture must not cancel"),
                        )
                    },
                )

                assertTrue("XML source-selection task did not start", await(started))
                assertFalse("XML source selection must not run on EDT", selectorWasOnEdt.get())
                assertFalse("selection must remain blocked until fixture release", result.isDone)

                val edtSentinel = CompletableFuture<Unit>()
                ApplicationManager.getApplication().invokeLater(
                    { edtSentinel.complete(Unit) },
                    ModalityState.any(),
                )
                PlatformTestUtil.waitForFuture(edtSentinel, FIXTURE_TIMEOUT_MILLIS)
                assertFalse(
                    "EDT sentinel must complete before blocked XML selection finishes",
                    result.isDone,
                )
            } finally {
                release.countDown()
            }

            val completed = PlatformTestUtil.waitForFuture(result, FIXTURE_TIMEOUT_MILLIS)
            assertTrue(completed is LeapPreparationSourceSelectionTaskResult.Completed)
            assertFalse(
                "source-selection fixture timed out waiting for deterministic release",
                selectorTimedOut.get(),
            )
        }

    fun testProgressCancellationPreventsNormalSelectionContinuation() =
        withProductionStyleBackgroundTasks {
            val evidence = xmlEvidence()
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val indicatorRef = AtomicReference<ProgressIndicator?>()
            val selectorTimedOut = AtomicBoolean(false)
            val passedCancellationGate = AtomicBoolean(false)
            val normalContinuationReached = AtomicBoolean(false)
            val cancelled = CompletableFuture<Unit>()

            val selector: LeapPreparationSourceSelector = { current, _, checkCanceled ->
                checkCanceled()
                indicatorRef.set(ProgressManager.getInstance().progressIndicator)
                started.countDown()
                if (!release.await(FIXTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    selectorTimedOut.set(true)
                }
                checkCanceled()
                passedCancellationGate.set(true)
                terminalSelection(current)
            }

            try {
                requestSelection(
                    adapter = LeapPreparationSourceSelectionAdapter(project, selector),
                    evidence = evidence,
                    onResult = {
                        normalContinuationReached.set(true)
                    },
                    onCancelled = {
                        cancelled.complete(Unit)
                    },
                )

                assertTrue("XML source-selection task did not start", await(started))
                val indicator = indicatorRef.get()
                assertNotNull("source selection must run under a ProgressIndicator", indicator)
                indicator!!.cancel()
            } finally {
                release.countDown()
            }

            PlatformTestUtil.waitForFuture(cancelled, FIXTURE_TIMEOUT_MILLIS)
            assertFalse(
                "cancelled source selection must not emit a normal result",
                normalContinuationReached.get(),
            )
            assertFalse(
                "cancelled source selection must stop at the next bounded probe",
                passedCancellationGate.get(),
            )
            assertFalse(
                "source-selection fixture timed out waiting for deterministic release",
                selectorTimedOut.get(),
            )
        }

    fun testInvocationInvalidationRejectsDelayedSelectionCompletion() =
        withProductionStyleBackgroundTasks {
            val evidence = xmlEvidence()
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val invocationActive = AtomicBoolean(true)
            val selectorTimedOut = AtomicBoolean(false)
            val normalContinuationReached = AtomicBoolean(false)
            val observed = CompletableFuture<LeapPreparationSourceSelectionTaskResult>()

            val selector: LeapPreparationSourceSelector = { current, _, checkCanceled ->
                checkCanceled()
                started.countDown()
                if (!release.await(FIXTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    selectorTimedOut.set(true)
                }
                checkCanceled()
                terminalSelection(current)
            }

            try {
                requestSelection(
                    adapter = LeapPreparationSourceSelectionAdapter(project, selector),
                    evidence = evidence,
                    isInvocationActive = invocationActive::get,
                    onResult = { result ->
                        if (result is LeapPreparationSourceSelectionTaskResult.Completed) {
                            normalContinuationReached.set(true)
                        }
                        observed.complete(result)
                    },
                    onCancelled = {
                        observed.completeExceptionally(
                            AssertionError("invalidation is not ProgressIndicator cancellation"),
                        )
                    },
                )

                assertTrue("XML source-selection task did not start", await(started))
                invocationActive.set(false)
            } finally {
                release.countDown()
            }

            val completed = PlatformTestUtil.waitForFuture(observed, FIXTURE_TIMEOUT_MILLIS)
            assertSame(LeapPreparationSourceSelectionTaskResult.InvocationInvalidated, completed)
            assertFalse(
                "stale XML selection must not remain eligible for normal continuation",
                normalContinuationReached.get(),
            )
            assertFalse(
                "source-selection fixture timed out waiting for deterministic release",
                selectorTimedOut.get(),
            )
        }

    private fun withProductionStyleBackgroundTasks(block: () -> Unit) {
        PlatformTestUtil.withSystemProperty<Nothing>(
            "intellij.progress.task.ignoreHeadless",
            "true",
        ) {
            block()
        }
    }

    private fun requestSelection(
        adapter: LeapPreparationSourceSelectionAdapter,
        evidence: LeapActionSourceEvidence.Xml,
        isInvocationActive: () -> Boolean = { true },
        onResult: (LeapPreparationSourceSelectionTaskResult) -> Unit,
        onCancelled: () -> Unit,
    ) {
        runInEdtAndWait {
            adapter.requestSelection(
                evidence = evidence,
                effectiveDatabaseId = null,
                isInvocationActive = isInvocationActive,
                onResult = onResult,
                onCancelled = onCancelled,
            )
        }
    }

    private fun xmlEvidence(): LeapActionSourceEvidence.Xml {
        val fileId = SourceFileId("vfs:/fixture/Mapper.xml")
        val statementId = XmlStatementId(fileId, "fixture.Mapper", "find")
        return LeapActionSourceEvidence.Xml(
            statementId = statementId,
            snapshots = listOf(
                SourceSnapshot(
                    fileId = fileId,
                    revision = SourceRevision("document:466"),
                    content = "<mapper namespace=\"fixture.Mapper\"><select id=\"find\">select 1</select></mapper>",
                ),
            ),
            discoveries = emptyList(),
        )
    }

    private fun terminalSelection(
        evidence: LeapActionSourceEvidence.Xml,
    ): LeapPreparationSourceSelectionResult =
        LeapPreparationSourceSelectionResult.Failed(
            LeapPreparationSourceSelectionFailure.XmlSourceGraphFailed(
                XmlStatementSourceGraphFailure.RootSourceMissing(evidence.statementId),
            ),
        )

    private fun await(latch: CountDownLatch): Boolean =
        latch.await(FIXTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    companion object {
        private const val FIXTURE_TIMEOUT_SECONDS = 15L
        private const val FIXTURE_TIMEOUT_MILLIS = FIXTURE_TIMEOUT_SECONDS * 1_000
    }
}
