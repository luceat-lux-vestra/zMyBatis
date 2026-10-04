package com.algorist.zMyBatis

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyEvaluationExecutionGuardContractTest {

    @Test
    fun `shipping action refuses explicit evaluation failure before formatting preview or execution`() {
        val action = source(
            "src/main/kotlin/com/algorist/zMyBatis/MyBatisExecuteProxyAction.kt",
        )
        val worker = action
            .substringAfter("ApplicationManager.getApplication().executeOnPooledThread {")
            .substringBefore("@Suppress(\"TooGenericExceptionCaught\", \"LongMethod\")")

        val evaluationCall = worker.indexOf("MyBatisEvaluator.evaluateForExecution(")
        val failureBranch = worker.indexOf("is LegacyExecutionEvaluationResult.Failed")
        val failureReturn = worker.indexOf("return@executeOnPooledThread", failureBranch)
        val settingsRead = worker.indexOf("val settings = ZMyBatisSettings.getInstance()", failureReturn)
        val formatter = worker.indexOf("SqlFormatter.format(", settingsRead)
        val preview = worker.indexOf("SqlPreviewDialog(", settingsRead)
        val execution = worker.indexOf("executeOnConsole(", settingsRead)

        assertTrue("shipping action must use the explicit execution-evaluation API", evaluationCall >= 0)
        assertTrue("failure branch must follow evaluation", failureBranch > evaluationCall)
        assertTrue("failure branch must terminate the worker", failureReturn > failureBranch)
        assertTrue("settings/format path must be unreachable after failure return", settingsRead > failureReturn)
        assertTrue("SQL formatting must happen only after successful evaluation", formatter > settingsRead)
        assertTrue("preview must happen only after successful evaluation", preview > settingsRead)
        assertTrue("console execution must happen only after successful evaluation", execution > settingsRead)

        val refused = worker.substring(failureBranch, failureReturn)
        assertTrue(
            "ordinary evaluation refusal must remain non-fatal",
            refused.contains("LOG.warn(\"zMyBatis evaluation refused ("),
        )
        assertFalse(
            "source-derived failure message must not be written to logs",
            refused.contains("LOG.warn(\"zMyBatis evaluation refused (${failure.message}"),
        )
    }

    @Test
    fun `execution evaluator rethrows cancellation and never converts fatal throwable to failure result`() {
        val evaluator = source(
            "src/main/kotlin/com/algorist/zMyBatis/MyBatisEvaluator.kt",
        )
        val executionBody = evaluator
            .substringAfter("internal fun evaluateForExecution(")
            .substringBefore("@Suppress(\"TooGenericExceptionCaught\")\n    fun evaluate(")

        val cancellationCatch = executionBody.indexOf("catch (e: ProcessCanceledException)")
        val ordinaryCatch = executionBody.indexOf("catch (e: Exception)")

        assertTrue("cancellation must be classified before ordinary Exception", cancellationCatch >= 0)
        assertTrue("ordinary Exception must become an explicit failure result", ordinaryCatch > cancellationCatch)
        assertTrue(
            "cancellation must be rethrown",
            executionBody.substring(cancellationCatch, ordinaryCatch).contains("throw e"),
        )
        assertTrue(
            "ordinary evaluation exceptions must be non-executable results",
            executionBody.substring(ordinaryCatch).contains("LegacyExecutionEvaluationResult.Failed(e)"),
        )
        assertFalse(
            "execution API must not emit the legacy compatibility SQL marker",
            executionBody.contains("-- [MyBatis Plugin Error]"),
        )
        assertFalse(
            "fatal non-Exception Throwable must not be downgraded",
            executionBody.contains("catch (e: Throwable)"),
        )
    }

    private fun source(relativePath: String): String =
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
