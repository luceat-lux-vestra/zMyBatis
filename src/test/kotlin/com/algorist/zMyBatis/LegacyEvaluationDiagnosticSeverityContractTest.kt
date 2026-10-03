package com.algorist.zMyBatis

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyEvaluationDiagnosticSeverityContractTest {

    @Test
    fun `legacy evaluator preserves cancellation and does not catch fatal throwables`() {
        val evaluator = source(
            "src/main/kotlin/com/algorist/zMyBatis/MyBatisEvaluator.kt"
        )
        val evaluateBody = evaluator
            .substringAfter("fun evaluate(")
            .substringBefore("// ── Strict OGNL helper")

        val cancellationCatch = evaluateBody.indexOf("catch (e: ProcessCanceledException)")
        val ordinaryCatch = evaluateBody.indexOf("catch (e: Exception)")

        assertTrue("evaluator must rethrow cancellation before ordinary exceptions", cancellationCatch >= 0)
        assertTrue("evaluator must keep an ordinary Exception compatibility path", ordinaryCatch > cancellationCatch)
        assertTrue(
            "evaluator cancellation must be rethrown",
            evaluateBody.substring(cancellationCatch, ordinaryCatch).contains("throw e"),
        )
        assertFalse(
            "legacy evaluator must not convert non-Exception fatal Throwable into compatibility SQL",
            evaluateBody.contains("catch (e: Throwable)"),
        )
        assertTrue(
            "bounded hardening must preserve the current ordinary-exception compatibility marker",
            evaluateBody.contains("-- [MyBatis Plugin Error]"),
        )
    }

    @Test
    fun `action evaluation keeps handled exceptions non fatal and rethrows fatal throwables`() {
        val action = source(
            "src/main/kotlin/com/algorist/zMyBatis/MyBatisExecuteProxyAction.kt"
        )
        val evaluationWorker = action
            .substringAfter("ApplicationManager.getApplication().executeOnPooledThread {")
            .substringBefore("@Suppress(\"TooGenericExceptionCaught\", \"LongMethod\")")

        val cancellationCatch = evaluationWorker.indexOf("catch (ex: ProcessCanceledException)")
        val ordinaryCatch = evaluationWorker.indexOf("catch (ex: Exception)")
        val fatalCatch = evaluationWorker.indexOf("catch (fatal: Throwable)")

        assertTrue("action evaluation must classify cancellation first", cancellationCatch >= 0)
        assertTrue("ordinary Exception must follow cancellation", ordinaryCatch > cancellationCatch)
        assertTrue("fatal Throwable must be classified after ordinary Exception", fatalCatch > ordinaryCatch)

        assertTrue(
            "ordinary evaluation failures must remain non-fatal",
            evaluationWorker.contains("LOG.warn(\"zMyBatis evaluation failed ("),
        )
        assertFalse(
            "ordinary evaluation failures must not use IntelliJ fatal reporting",
            evaluationWorker.contains("LOG.error(\"zMyBatis evaluation failed\""),
        )
        assertTrue(
            "ordinary evaluation failures must retain the user-visible dialog",
            evaluationWorker.contains("Error evaluating MyBatis SQL:"),
        )

        val fatalBranch = evaluationWorker.substring(fatalCatch)
        assertTrue(
            "fatal evaluation failures must remain error-level",
            fatalBranch.contains("LOG.error(\"zMyBatis fatal evaluation failure\", fatal)"),
        )
        assertTrue(
            "fatal evaluation failures must be rethrown",
            fatalBranch.contains("throw fatal"),
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
