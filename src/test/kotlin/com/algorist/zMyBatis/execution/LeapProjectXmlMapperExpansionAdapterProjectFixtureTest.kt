package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.source.XmlMapperDiscoveryResult
import com.algorist.zMyBatis.source.XmlMapperDocumentDiscovery
import com.algorist.zMyBatis.source.XmlMapperSourceDiscovery
import com.algorist.zMyBatis.source.XmlProjectMapperSourceCapture
import com.algorist.zMyBatis.source.XmlProjectMapperSourceCaptureResult
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

class LeapProjectXmlMapperExpansionAdapterProjectFixtureTest : BasePlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    fun testBlockedProjectExpansionRunsOffEdtAndLeavesEdtResponsive() =
        withProductionStyleBackgroundTasks {
            val evidence = rootEvidence()
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val expansionTimedOut = AtomicBoolean(false)
            val expansionWasOnEdt = AtomicBoolean(true)
            val result = CompletableFuture<LeapProjectXmlMapperExpansionTaskResult>()

            val expander: LeapProjectXmlMapperExpander = { snapshot, discovery, checkCanceled ->
                checkCanceled()
                expansionWasOnEdt.set(ApplicationManager.getApplication().isDispatchThread)
                started.countDown()
                if (!release.await(FIXTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    expansionTimedOut.set(true)
                }
                checkCanceled()
                terminalExpansion(snapshot, discovery)
            }

            try {
                requestExpansion(
                    adapter = LeapProjectXmlMapperExpansionAdapter(project, expander),
                    evidence = evidence,
                    onResult = result::complete,
                    onCancelled = {
                        result.completeExceptionally(
                            AssertionError("uncancelled project expansion fixture must not cancel"),
                        )
                    },
                )

                assertTrue("project XML expansion task did not start", await(started))
                assertFalse("project XML expansion must not run on EDT", expansionWasOnEdt.get())
                assertFalse("expansion must remain blocked until fixture release", result.isDone)

                val edtSentinel = CompletableFuture<Unit>()
                ApplicationManager.getApplication().invokeLater(
                    { edtSentinel.complete(Unit) },
                    ModalityState.any(),
                )
                PlatformTestUtil.waitForFuture(edtSentinel, FIXTURE_TIMEOUT_MILLIS)
                assertFalse(
                    "EDT sentinel must complete before blocked project expansion finishes",
                    result.isDone,
                )
            } finally {
                release.countDown()
            }

            val completed = PlatformTestUtil.waitForFuture(result, FIXTURE_TIMEOUT_MILLIS)
            assertTrue(completed is LeapProjectXmlMapperExpansionTaskResult.Completed)
            assertFalse(
                "project expansion fixture timed out waiting for deterministic release",
                expansionTimedOut.get(),
            )
        }

    fun testProgressCancellationPreventsNormalExpansionContinuation() =
        withProductionStyleBackgroundTasks {
            val evidence = rootEvidence()
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val indicatorRef = AtomicReference<ProgressIndicator?>()
            val expansionTimedOut = AtomicBoolean(false)
            val passedCancellationGate = AtomicBoolean(false)
            val normalContinuationReached = AtomicBoolean(false)
            val cancelled = CompletableFuture<Unit>()

            val expander: LeapProjectXmlMapperExpander = { snapshot, discovery, checkCanceled ->
                checkCanceled()
                indicatorRef.set(ProgressManager.getInstance().progressIndicator)
                started.countDown()
                if (!release.await(FIXTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    expansionTimedOut.set(true)
                }
                checkCanceled()
                passedCancellationGate.set(true)
                terminalExpansion(snapshot, discovery)
            }

            try {
                requestExpansion(
                    adapter = LeapProjectXmlMapperExpansionAdapter(project, expander),
                    evidence = evidence,
                    onResult = {
                        normalContinuationReached.set(true)
                    },
                    onCancelled = {
                        cancelled.complete(Unit)
                    },
                )

                assertTrue("project XML expansion task did not start", await(started))
                val indicator = indicatorRef.get()
                assertNotNull("project expansion must run under a ProgressIndicator", indicator)
                indicator!!.cancel()
            } finally {
                release.countDown()
            }

            PlatformTestUtil.waitForFuture(cancelled, FIXTURE_TIMEOUT_MILLIS)
            assertFalse(
                "cancelled project expansion must not emit a normal result",
                normalContinuationReached.get(),
            )
            assertFalse(
                "cancelled project expansion must stop at the next bounded probe",
                passedCancellationGate.get(),
            )
            assertFalse(
                "project expansion fixture timed out waiting for deterministic release",
                expansionTimedOut.get(),
            )
        }

    fun testInvocationInvalidationRejectsDelayedExpansionCompletion() =
        withProductionStyleBackgroundTasks {
            val evidence = rootEvidence()
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val invocationActive = AtomicBoolean(true)
            val expansionTimedOut = AtomicBoolean(false)
            val normalContinuationReached = AtomicBoolean(false)
            val observed = CompletableFuture<LeapProjectXmlMapperExpansionTaskResult>()

            val expander: LeapProjectXmlMapperExpander = { snapshot, discovery, checkCanceled ->
                checkCanceled()
                started.countDown()
                if (!release.await(FIXTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    expansionTimedOut.set(true)
                }
                checkCanceled()
                terminalExpansion(snapshot, discovery)
            }

            try {
                requestExpansion(
                    adapter = LeapProjectXmlMapperExpansionAdapter(project, expander),
                    evidence = evidence,
                    isInvocationActive = invocationActive::get,
                    onResult = { result ->
                        if (result is LeapProjectXmlMapperExpansionTaskResult.Completed) {
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

                assertTrue("project XML expansion task did not start", await(started))
                invocationActive.set(false)
            } finally {
                release.countDown()
            }

            val completed = PlatformTestUtil.waitForFuture(observed, FIXTURE_TIMEOUT_MILLIS)
            assertSame(LeapProjectXmlMapperExpansionTaskResult.InvocationInvalidated, completed)
            assertFalse(
                "stale project expansion must not remain eligible for target selection",
                normalContinuationReached.get(),
            )
            assertFalse(
                "project expansion fixture timed out waiting for deterministic release",
                expansionTimedOut.get(),
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

    private fun requestExpansion(
        adapter: LeapProjectXmlMapperExpansionAdapter,
        evidence: RootEvidence,
        isInvocationActive: () -> Boolean = { true },
        onResult: (LeapProjectXmlMapperExpansionTaskResult) -> Unit,
        onCancelled: () -> Unit,
    ) {
        runInEdtAndWait {
            adapter.requestExpansion(
                rootSnapshot = evidence.snapshot,
                rootDiscovery = evidence.discovery,
                isInvocationActive = isInvocationActive,
                onResult = onResult,
                onCancelled = onCancelled,
            )
        }
    }

    private fun rootEvidence(): RootEvidence {
        val snapshot = SourceSnapshot(
            fileId = SourceFileId("vfs:/fixture/Mapper.xml"),
            revision = SourceRevision("document:468"),
            content = "<mapper namespace=\"fixture.Mapper\"><select id=\"find\">select 1</select></mapper>",
        )
        val discovery = when (val result = XmlMapperSourceDiscovery.discover(snapshot)) {
            is XmlMapperDiscoveryResult.Discovered -> result.mapper
            is XmlMapperDiscoveryResult.Failed ->
                error("fixture mapper discovery failed: ${result.reason}")
        }
        return RootEvidence(snapshot, discovery)
    }

    private fun terminalExpansion(
        snapshot: SourceSnapshot,
        discovery: XmlMapperDocumentDiscovery,
    ): XmlProjectMapperSourceCaptureResult =
        XmlProjectMapperSourceCaptureResult.Captured(
            XmlProjectMapperSourceCapture(
                snapshots = listOf(snapshot),
                discoveries = listOf(discovery),
            ),
        )

    private fun await(latch: CountDownLatch): Boolean =
        latch.await(FIXTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private data class RootEvidence(
        val snapshot: SourceSnapshot,
        val discovery: XmlMapperDocumentDiscovery,
    )

    companion object {
        private const val FIXTURE_TIMEOUT_SECONDS = 15L
        private const val FIXTURE_TIMEOUT_MILLIS = FIXTURE_TIMEOUT_SECONDS * 1_000
    }
}
