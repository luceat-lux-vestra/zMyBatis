package com.algorist.zMyBatis

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyActionPresentationDiagnosticSeverityContractTest {

    @Test
    fun `run action keeps handled exceptions non fatal and rethrows fatal throwables`() {
        val action = source(
            "src/main/kotlin/com/algorist/zMyBatis/MyBatisExecuteProxyAction.kt"
        )
        val runBody = action
            .substringAfter("private fun runMyBatisQuery(")
            .substringBefore("private fun proceedWithParamsAndExecute(")

        val cancellationCatch = runBody.indexOf("catch (ex: ProcessCanceledException)")
        val ordinaryCatch = runBody.indexOf("catch (ex: Exception)")
        val fatalCatch = runBody.indexOf("catch (fatal: Throwable)")

        assertTrue("run action must classify cancellation first", cancellationCatch >= 0)
        assertTrue("ordinary Exception must follow cancellation", ordinaryCatch > cancellationCatch)
        assertTrue("fatal Throwable must follow ordinary Exception", fatalCatch > ordinaryCatch)

        val ordinaryBranch = runBody.substring(ordinaryCatch, fatalCatch)
        assertTrue(
            "ordinary preparation failures must remain non-fatal",
            ordinaryBranch.contains("LOG.warn(\"zMyBatis runMyBatisQuery failed ("),
        )
        assertTrue(
            "ordinary preparation failures must retain the existing user-visible dialog",
            ordinaryBranch.contains("Error preparing MyBatis query:"),
        )
        assertFalse(
            "ordinary preparation failures must not use IntelliJ fatal reporting",
            ordinaryBranch.contains("LOG.error("),
        )

        val fatalBranch = runBody.substring(fatalCatch)
        assertTrue(
            "fatal action failures must remain error-level",
            fatalBranch.contains("LOG.error(\"zMyBatis fatal runMyBatisQuery failure\", fatal)"),
        )
        assertTrue("fatal action failures must be rethrown", fatalBranch.contains("throw fatal"))
    }

    @Test
    fun `datasource chooser cleanup preserves cancellation and fatal throwables`() {
        val action = source(
            "src/main/kotlin/com/algorist/zMyBatis/MyBatisExecuteProxyAction.kt"
        )
        val chooserCallback = action
            .substringAfter("popup.showInBestPositionFor(originalEvent.dataContext)")
            .substringBefore("}, ModalityState.any())")

        val cancellationCatch = chooserCallback.indexOf("catch (ex: ProcessCanceledException)")
        val ordinaryCatch = chooserCallback.indexOf("catch (ex: Exception)")
        val fatalCatch = chooserCallback.indexOf("catch (fatal: Throwable)")

        assertTrue("chooser must classify cancellation first", cancellationCatch >= 0)
        assertTrue("ordinary chooser Exception must follow cancellation", ordinaryCatch > cancellationCatch)
        assertTrue("fatal chooser Throwable must follow ordinary Exception", fatalCatch > ordinaryCatch)

        val cancellationBranch = chooserCallback.substring(cancellationCatch, ordinaryCatch)
        assertTrue(cancellationBranch.contains("cache.endSelection(fileKey)"))
        assertTrue(cancellationBranch.contains("throw ex"))

        val ordinaryBranch = chooserCallback.substring(ordinaryCatch, fatalCatch)
        assertTrue(ordinaryBranch.contains("cache.endSelection(fileKey)"))
        assertTrue(
            "ordinary chooser failures must remain non-fatal",
            ordinaryBranch.contains("LOG.warn(\"zMyBatis: failed to show datasource chooser"),
        )
        assertFalse(ordinaryBranch.contains("LOG.error("))

        val fatalBranch = chooserCallback.substring(fatalCatch)
        assertTrue(fatalBranch.contains("cache.endSelection(fileKey)"))
        assertTrue(
            fatalBranch.contains("LOG.error(\"zMyBatis: fatal datasource chooser failure for \$fileKey\", fatal)")
        )
        assertTrue(fatalBranch.contains("throw fatal"))
    }

    @Test
    fun `formatter fallback handles only ordinary exceptions and does not log exception messages`() {
        val formatter = source(
            "src/main/kotlin/com/algorist/zMyBatis/SqlFormatter.kt"
        )
        val formatBody = formatter
            .substringAfter("fun format(")
            .substringBefore("private fun formatInternal(")

        val cancellationCatch = formatBody.indexOf("catch (e: ProcessCanceledException)")
        val ordinaryCatch = formatBody.indexOf("catch (e: Exception)")

        assertTrue("formatter must classify cancellation first", cancellationCatch >= 0)
        assertTrue("ordinary formatter Exception must follow cancellation", ordinaryCatch > cancellationCatch)
        assertTrue(
            "formatter cancellation must be rethrown",
            formatBody.substring(cancellationCatch, ordinaryCatch).contains("throw e"),
        )
        assertFalse(
            "formatter must not convert fatal non-Exception Throwable into a successful fallback",
            formatBody.contains("catch (e: Throwable)"),
        )

        val ordinaryBranch = formatBody.substring(ordinaryCatch)
        assertTrue(
            "ordinary formatting failure must still fall back to original SQL",
            ordinaryBranch.trimEnd().endsWith("sql\n        }\n    }"),
        )
        assertTrue(
            "formatter warning should retain only exception type metadata",
            ordinaryBranch.contains("\${e::class.java.name}"),
        )
        assertFalse(
            "formatter diagnostics must not log exception messages that may contain SQL/source-derived text",
            ordinaryBranch.contains("e.message"),
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
