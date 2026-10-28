package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.source.DependentMapperSourceCaptureResult
import com.algorist.zMyBatis.source.DependentMapperSourceSnapshotAdapter
import com.algorist.zMyBatis.source.XmlMapperDiscoveryResult
import com.algorist.zMyBatis.source.XmlMapperDocumentDiscovery
import com.algorist.zMyBatis.source.XmlMapperSourceDiscovery
import com.algorist.zMyBatis.source.XmlProjectMapperSourceCapture
import com.algorist.zMyBatis.source.XmlProjectMapperSourceCaptureAdapter
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class LeapXmlMapperScaleProjectFixtureTest : BasePlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    fun testLargeProjectExpansionMeasuresShippingCaptureAndLeavesEdtResponsive() =
        withProductionStyleBackgroundTasks {
            val fixture = createProjectFixture()
            val gateReached = CountDownLatch(1)
            val releaseGate = CountDownLatch(1)
            val gateTimedOut = AtomicBoolean(false)
            val gateBlockedNanos = AtomicLong()
            val expansionWasOnEdt = AtomicBoolean(true)
            val probeCount = AtomicInteger()
            val result = CompletableFuture<LeapProjectXmlMapperExpansionTaskResult>()

            val expander: LeapProjectXmlMapperExpander = { rootSnapshot, rootDiscovery, checkCanceled ->
                expansionWasOnEdt.set(ApplicationManager.getApplication().isDispatchThread)
                XmlProjectMapperSourceCaptureAdapter.capture(
                    project = project,
                    rootSnapshot = rootSnapshot,
                    rootDiscovery = rootDiscovery,
                    checkCanceled = {
                        checkCanceled()
                        val probe = probeCount.incrementAndGet()
                        if (probe == RESPONSIVENESS_GATE_PROBE) {
                            gateReached.countDown()
                            val gateStartedNanos = System.nanoTime()
                            if (!releaseGate.await(FIXTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                                gateTimedOut.set(true)
                            }
                            gateBlockedNanos.set(System.nanoTime() - gateStartedNanos)
                        }
                    },
                )
            }

            val startedNanos = System.nanoTime()
            try {
                requestExpansion(
                    adapter = LeapProjectXmlMapperExpansionAdapter(project, expander),
                    fixture = fixture,
                    onResult = result::complete,
                    onCancelled = {
                        result.completeExceptionally(
                            AssertionError("uncancelled scale expansion must not cancel"),
                        )
                    },
                )

                assertTrue("large XML project expansion did not reach the capture loop", await(gateReached))
                assertFalse("large XML project expansion must not run on EDT", expansionWasOnEdt.get())
                assertFalse("large XML project expansion must remain blocked at the fixture gate", result.isDone)

                val edtSentinel = CompletableFuture<Unit>()
                ApplicationManager.getApplication().invokeLater(
                    { edtSentinel.complete(Unit) },
                    ModalityState.any(),
                )
                PlatformTestUtil.waitForFuture(edtSentinel, FIXTURE_TIMEOUT_MILLIS)
                assertFalse(
                    "EDT sentinel must complete while real project XML capture remains outstanding",
                    result.isDone,
                )
            } finally {
                releaseGate.countDown()
            }

            val completed = PlatformTestUtil.waitForFuture(result, FIXTURE_TIMEOUT_MILLIS)
            val capture = captured(completed)
            val elapsedNanos =
                (System.nanoTime() - startedNanos - gateBlockedNanos.get()).coerceAtLeast(0L)
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos)
            val aggregateBytes = capture.snapshots.sumOf { snapshot ->
                snapshot.content.toByteArray(Charsets.UTF_8).size.toLong()
            }

            assertEquals(DEPENDENT_MAPPER_COUNT + 1, capture.snapshots.size)
            assertEquals(DEPENDENT_MAPPER_COUNT + 1, capture.discoveries.size)
            assertEquals(fixture.expectedAggregateBytes, aggregateBytes)
            assertFalse(
                "large XML expansion fixture timed out at its deterministic gate",
                gateTimedOut.get(),
            )
            assertTrue(
                "real capture must traverse enough cooperative probes to cover the full candidate set",
                probeCount.get() > RESPONSIVENESS_GATE_PROBE,
            )

            println(
                "ZMYBATIS_XML_SCALE phase=project-expansion " +
                    "dependent_candidates=$DEPENDENT_MAPPER_COUNT " +
                    "total_mapper_documents=${capture.snapshots.size} " +
                    "aggregate_source_bytes=$aggregateBytes " +
                    "probe_count=${probeCount.get()} " +
                    "elapsed_excluding_fixture_gate_ms=$elapsedMillis " +
                    "edt_sentinel_completed=true",
            )
        }

    fun testCancellationStopsLargeProjectExpansionAtRealCaptureProbe() =
        withProductionStyleBackgroundTasks {
            val fixture = createProjectFixture()
            val cancellationGate = CountDownLatch(1)
            val releaseGate = CountDownLatch(1)
            val gateTimedOut = AtomicBoolean(false)
            val indicatorRef = AtomicReference<ProgressIndicator?>()
            val probeCount = AtomicInteger()
            val normalContinuationReached = AtomicBoolean(false)
            val cancelled = CompletableFuture<Unit>()

            val expander: LeapProjectXmlMapperExpander = { rootSnapshot, rootDiscovery, checkCanceled ->
                indicatorRef.set(ProgressManager.getInstance().progressIndicator)
                XmlProjectMapperSourceCaptureAdapter.capture(
                    project = project,
                    rootSnapshot = rootSnapshot,
                    rootDiscovery = rootDiscovery,
                    checkCanceled = {
                        val probe = probeCount.incrementAndGet()
                        if (probe == CANCELLATION_GATE_PROBE) {
                            cancellationGate.countDown()
                            if (!releaseGate.await(FIXTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                                gateTimedOut.set(true)
                            }
                        }
                        checkCanceled()
                    },
                )
            }

            requestExpansion(
                adapter = LeapProjectXmlMapperExpansionAdapter(project, expander),
                fixture = fixture,
                onResult = {
                    normalContinuationReached.set(true)
                },
                onCancelled = {
                    cancelled.complete(Unit)
                },
            )

            assertTrue("large XML capture did not reach the cancellation probe", await(cancellationGate))
            val indicator = indicatorRef.get()
            assertNotNull("large XML expansion must run under a real ProgressIndicator", indicator)

            val cancellationStartedNanos = System.nanoTime()
            indicator!!.cancel()
            releaseGate.countDown()
            PlatformTestUtil.waitForFuture(cancelled, FIXTURE_TIMEOUT_MILLIS)
            val cancellationObservedMillis =
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancellationStartedNanos)

            assertFalse(
                "cancelled large XML expansion must not emit a normal completion",
                normalContinuationReached.get(),
            )
            assertEquals(
                "capture must stop at the gated cooperative cancellation probe",
                CANCELLATION_GATE_PROBE,
                probeCount.get(),
            )
            assertFalse(
                "large XML cancellation fixture timed out at its deterministic gate",
                gateTimedOut.get(),
            )

            println(
                "ZMYBATIS_XML_SCALE phase=project-expansion-cancel " +
                    "dependent_candidates=$DEPENDENT_MAPPER_COUNT " +
                    "aggregate_fixture_bytes=${fixture.expectedAggregateBytes} " +
                    "cancel_probe=$CANCELLATION_GATE_PROBE " +
                    "observed_probe_count=${probeCount.get()} " +
                    "cancel_observed_ms=$cancellationObservedMillis " +
                    "normal_continuation=false",
            )
        }

    private fun createProjectFixture(): ProjectFixture {
        var expectedAggregateBytes = 0L

        val rootContent = mapperContent("scale.RootMapper", "root")
        expectedAggregateBytes += rootContent.toByteArray(Charsets.UTF_8).size
        val rootFile = myFixture.addFileToProject("scale/RootMapper.xml", rootContent).virtualFile

        repeat(DEPENDENT_MAPPER_COUNT) { index ->
            val namespace = "scale.Mapper${index.toString().padStart(3, '0')}"
            val content = mapperContent(namespace, "find")
            expectedAggregateBytes += content.toByteArray(Charsets.UTF_8).size
            myFixture.addFileToProject(
                "scale/Mapper${index.toString().padStart(3, '0')}.xml",
                content,
            )
        }

        val rootSnapshot = when (
            val capture = DependentMapperSourceSnapshotAdapter.capture(
                virtualFile = rootFile,
                maxContentLength = MAX_FIXTURE_SOURCE_LENGTH,
            )
        ) {
            is DependentMapperSourceCaptureResult.Captured -> capture.snapshot
            else -> throw AssertionError("root scale mapper snapshot failed: $capture")
        }
        val rootDiscovery = when (val discovery = XmlMapperSourceDiscovery.discover(rootSnapshot)) {
            is XmlMapperDiscoveryResult.Discovered -> discovery.mapper
            is XmlMapperDiscoveryResult.Failed ->
                throw AssertionError("root scale mapper discovery failed: ${discovery.reason}")
        }

        return ProjectFixture(
            rootSnapshot = rootSnapshot,
            rootDiscovery = rootDiscovery,
            expectedAggregateBytes = expectedAggregateBytes,
        )
    }

    private fun mapperContent(namespace: String, statementId: String): String =
        buildString {
            append("<mapper namespace=\"")
            append(namespace)
            append("\"><sql id=\"fragment\">id</sql><select id=\"")
            append(statementId)
            append("\">select 1</select><!--")
            repeat(PADDING_CHARS) {
                append('x')
            }
            append("--></mapper>")
        }

    private fun requestExpansion(
        adapter: LeapProjectXmlMapperExpansionAdapter,
        fixture: ProjectFixture,
        onResult: (LeapProjectXmlMapperExpansionTaskResult) -> Unit,
        onCancelled: () -> Unit,
    ) {
        runInEdtAndWait {
            adapter.requestExpansion(
                rootSnapshot = fixture.rootSnapshot,
                rootDiscovery = fixture.rootDiscovery,
                isInvocationActive = { true },
                onResult = onResult,
                onCancelled = onCancelled,
            )
        }
    }

    private fun captured(
        result: LeapProjectXmlMapperExpansionTaskResult,
    ): XmlProjectMapperSourceCapture {
        val completed = result as? LeapProjectXmlMapperExpansionTaskResult.Completed
            ?: throw AssertionError("expected completed scale expansion but got $result")
        return when (val expansion = completed.expansion) {
            is XmlProjectMapperSourceCaptureResult.Captured -> expansion.capture
            XmlProjectMapperSourceCaptureResult.IndexUnavailable ->
                throw AssertionError("project XML index unexpectedly unavailable in scale fixture")
        }
    }

    private fun withProductionStyleBackgroundTasks(block: () -> Unit) {
        PlatformTestUtil.withSystemProperty<Nothing>(
            "intellij.progress.task.ignoreHeadless",
            "true",
        ) {
            block()
        }
    }

    private fun await(latch: CountDownLatch): Boolean =
        latch.await(FIXTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private data class ProjectFixture(
        val rootSnapshot: SourceSnapshot,
        val rootDiscovery: XmlMapperDocumentDiscovery,
        val expectedAggregateBytes: Long,
    )

    companion object {
        private const val DEPENDENT_MAPPER_COUNT = 128
        private const val PADDING_CHARS = 8 * 1024
        private const val MAX_FIXTURE_SOURCE_LENGTH = 2 * 1024 * 1024
        private const val RESPONSIVENESS_GATE_PROBE = 64
        private const val CANCELLATION_GATE_PROBE = 64
        private const val FIXTURE_TIMEOUT_SECONDS = 30L
        private const val FIXTURE_TIMEOUT_MILLIS = FIXTURE_TIMEOUT_SECONDS * 1_000
    }
}
