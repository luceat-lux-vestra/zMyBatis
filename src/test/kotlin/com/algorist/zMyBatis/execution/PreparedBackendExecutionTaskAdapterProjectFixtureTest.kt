package com.algorist.zMyBatis.execution

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PreparedBackendExecutionTaskAdapterProjectFixtureTest : BasePlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    fun testProgressCancellationOwnsAndCancelsPreparedBackendHandle() =
        withProductionStyleBackgroundTasks {
            val started = CountDownLatch(1)
            val indicatorRef = AtomicReference<ProgressIndicator?>()
            val cancelCalls = AtomicInteger(0)
            val completed = CompletableFuture<DatabaseSessionPreparedOutcome>()

            PreparedBackendExecutionTaskAdapter(project).execute(
                executeBackend = {
                    indicatorRef.set(ProgressManager.getInstance().progressIndicator)
                    started.countDown()
                    object : PreparedBackendExecutionHandle {
                        override fun cancel(): PreparedBackendCancellationResult {
                            cancelCalls.incrementAndGet()
                            return PreparedBackendCancellationResult.Requested
                        }
                    }
                },
                onCompleted = completed::complete,
            )

            assertTrue(
                "prepared execution task did not start",
                started.await(FIXTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            val indicator = indicatorRef.get()
            assertNotNull("prepared execution task must own a ProgressIndicator", indicator)
            indicator!!.cancel()

            val outcome = PlatformTestUtil.waitForFuture(completed, FIXTURE_TIMEOUT_MILLIS)
            assertSame(DatabaseSessionPreparedOutcome.Cancelled, outcome)
            assertEquals(
                "task cancellation must dispatch exactly one backend cancel request",
                1,
                cancelCalls.get(),
            )
        }

    fun testSuccessfulPreparedOutcomeIsDeliveredWithoutCancellation() =
        withProductionStyleBackgroundTasks {
            val cancelCalls = AtomicInteger(0)
            val completed = CompletableFuture<DatabaseSessionPreparedOutcome>()
            val expected = DatabaseSessionPreparedOutcome.Update(3)

            PreparedBackendExecutionTaskAdapter(project).execute(
                executeBackend = { onCompleted ->
                    onCompleted(expected)
                    object : PreparedBackendExecutionHandle {
                        override fun cancel(): PreparedBackendCancellationResult {
                            cancelCalls.incrementAndGet()
                            return PreparedBackendCancellationResult.Requested
                        }
                    }
                },
                onCompleted = completed::complete,
            )

            assertEquals(
                expected,
                PlatformTestUtil.waitForFuture(completed, FIXTURE_TIMEOUT_MILLIS),
            )
            assertEquals(0, cancelCalls.get())
        }

    private fun withProductionStyleBackgroundTasks(block: () -> Unit) {
        PlatformTestUtil.withSystemProperty<Nothing>(
            "intellij.progress.task.ignoreHeadless",
            "true",
        ) {
            block()
        }
    }

    companion object {
        private const val FIXTURE_TIMEOUT_SECONDS = 15L
        private const val FIXTURE_TIMEOUT_MILLIS = FIXTURE_TIMEOUT_SECONDS * 1_000
    }
}
