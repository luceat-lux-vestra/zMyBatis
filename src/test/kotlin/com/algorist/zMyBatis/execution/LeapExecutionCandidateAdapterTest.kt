package com.algorist.zMyBatis.execution

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeapExecutionCandidateAdapterTest {
    @Test
    fun candidateWorkIsQueuedOnCancellableBackgroundTaskBeforeContinuation() {
        val adapter = sourceText(
            "src/main/kotlin/com/algorist/zMyBatis/execution/LeapExecutionCandidateAdapter.kt",
        )

        val task = adapter.indexOf("Task.Backgroundable(")
        val run = adapter.indexOf("override fun run(indicator: ProgressIndicator)")
        val build = adapter.indexOf("candidateBuilder(", run)
        val success = adapter.indexOf("override fun onSuccess()")
        val queue = adapter.indexOf("}.queue()")

        assertTrue(task >= 0)
        assertTrue(task < run)
        assertTrue(run < build)
        assertTrue(build < success)
        assertTrue(success < queue)
        assertTrue(adapter.contains("indicator::checkCanceled"))
        assertTrue(adapter.contains("if (!liveInvocation())"))
        assertTrue(adapter.contains("LeapExecutionCandidateResult.InvocationInvalidated"))
        assertTrue(adapter.contains("override fun onCancel()"))
        assertTrue(adapter.contains("override fun onThrowable(error: Throwable)"))
        assertFalse(adapter.contains("AnActionEvent"))
        assertFalse(adapter.contains("Editor"))
        assertFalse(adapter.contains("JdbcConsole"))
        assertFalse(adapter.contains("Dialog"))
    }

    @Test
    fun immutableSourceCaptureDropsLiveEditorBeforeAsyncInvocationChain() {
        val admission = sourceText(
            "src/main/kotlin/com/algorist/zMyBatis/execution/LeapActionContextAdmissionCoordinator.kt",
        )

        val captureGuard = admission.indexOf("val captureContextActive = {")
        val capture = admission.indexOf("LeapActionSourceEvidenceAdapter.capture(")
        val invocationGuard = admission.indexOf("val liveInvocation = {", capture)
        val continuation = admission.indexOf("continueWithCapturedEvidence(", invocationGuard)
        val guardBody = admission.substring(
            invocationGuard,
            admission.indexOf("continueWithCapturedEvidence(", invocationGuard),
        )

        assertTrue(captureGuard >= 0)
        assertTrue(captureGuard < capture)
        assertTrue(capture < invocationGuard)
        assertTrue(invocationGuard < continuation)
        assertTrue(admission.substring(captureGuard, capture).contains("!editor.isDisposed"))
        assertFalse(guardBody.contains("editor"))
        assertTrue(guardBody.contains("!project.isDisposed"))
        assertTrue(guardBody.contains("!cache.isShuttingDown()"))
    }

    private fun sourceText(relativePath: String): String =
        Files.readString(repositoryRoot().resolve(relativePath))

    private fun repositoryRoot(): Path {
        var current = Path.of("").toAbsolutePath().normalize()
        while (true) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
                return current
            }
            current = current.parent ?: error("Repository root not found")
        }
    }
}
