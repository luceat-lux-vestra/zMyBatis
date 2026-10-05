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
        val refusalLog = refused.lineSequence().first { it.contains("LOG.warn(") }
        assertFalse(
            "source-derived failure message must not be written to logs",
            refusalLog.contains("failure.message"),
        )
    }

    @Test
    fun `shipping action derives mutation confirmation from current declaration evidence`() {
        val action = source(
            "src/main/kotlin/com/algorist/zMyBatis/MyBatisExecuteProxyAction.kt",
        )
        val policyCapture = action
            .substringAfter("val requiresMutationConfirmation =")
            .substringBefore("val annotationDependencies =")

        assertTrue(
            "XML statement tag must feed the mutation confirmation policy",
            policyCapture.contains("xmlTagName = statementXmlTag?.name"),
        )
        assertTrue(
            "Java statement annotation must feed the mutation confirmation policy",
            policyCapture.contains("annotationQualifiedName = statementAnnotation?.qualifiedName"),
        )

        val executionSignature = action
            .substringAfter("private fun proceedWithParamsAndExecute(")
            .substringBefore(") {")
        assertTrue(
            "captured mutation policy must be an explicit invocation input",
            executionSignature.contains("requiresMutationConfirmation: Boolean"),
        )
    }

    @Test
    fun `mandatory confirmation requirements cannot be disabled by preview setting`() {
        val action = source(
            "src/main/kotlin/com/algorist/zMyBatis/MyBatisExecuteProxyAction.kt",
        )
        val worker = action
            .substringAfter("ApplicationManager.getApplication().executeOnPooledThread {")
            .substringBefore("@Suppress(\"TooGenericExceptionCaught\", \"LongMethod\")")

        val evaluatedResult = worker.indexOf(
            "is LegacyExecutionEvaluationResult.Evaluated -> evaluation",
        )
        val pureSql = worker.indexOf("val pureSql =", evaluatedResult)
        val previewPolicy = worker.indexOf("val requiresSqlPreview =", pureSql)
        val userSetting = worker.indexOf("settings.sqlPreview", previewPolicy)
        val rawConfirmation = worker.indexOf(
            "evaluated.requiresRawInterpolationConfirmation",
            previewPolicy,
        )
        val mutationConfirmation = worker.indexOf(
            "requiresMutationConfirmation",
            rawConfirmation,
        )
        val guardedPreview = worker.indexOf("if (requiresSqlPreview)", previewPolicy)
        val previewDialog = worker.indexOf("SqlPreviewDialog(project, pureSql)", guardedPreview)
        val cancelBranch = worker.indexOf(
            "user cancelled from SQL preview dialog",
            previewDialog,
        )
        val directExecution = worker.indexOf("} else {", cancelBranch)
        val directExecuteCall = worker.indexOf("executeOnConsole(", directExecution)

        assertTrue("successful evaluation metadata must be retained", evaluatedResult >= 0)
        assertTrue("final SQL must be built before preview policy", pureSql > evaluatedResult)
        assertTrue("preview policy must be explicit", previewPolicy > pureSql)
        assertTrue("user preview setting must participate in policy", userSetting > previewPolicy)
        assertTrue("raw confirmation metadata must participate in policy", rawConfirmation > userSetting)
        assertTrue(
            "mutation declaration policy must participate in preview policy",
            mutationConfirmation > rawConfirmation,
        )
        assertTrue("preview branch must be guarded by combined policy", guardedPreview > mutationConfirmation)
        assertTrue("preview must receive the final pureSql", previewDialog > guardedPreview)
        assertTrue("cancel must remain inside the preview branch", cancelBranch > previewDialog)
        assertTrue("non-preview execution must remain a separate else branch", directExecution > cancelBranch)
        assertTrue("direct execution may occur only after that else branch", directExecuteCall > directExecution)

        val previewPolicySource = worker.substring(previewPolicy, guardedPreview)
        assertTrue(
            "raw interpolation must remain part of mandatory preview policy",
            previewPolicySource.contains("evaluated.requiresRawInterpolationConfirmation"),
        )
        assertTrue(
            "mutation declarations must force preview independently of the user setting",
            previewPolicySource.contains("requiresMutationConfirmation"),
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
