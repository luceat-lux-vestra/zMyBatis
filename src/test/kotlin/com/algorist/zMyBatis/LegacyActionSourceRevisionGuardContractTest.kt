package com.algorist.zMyBatis

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyActionSourceRevisionGuardContractTest {

    @Test
    fun `shipping action carries one source revision through parameter preview and execution boundaries`() {
        val action = source(
            "src/main/kotlin/com/algorist/zMyBatis/MyBatisExecuteProxyAction.kt",
        )

        assertTrue(
            "action must capture the invocation source revision before legacy extraction",
            action.contains("LegacyActionSourceRevisionGuard.capture(project, editor, psiFile)"),
        )
        assertTrue(
            "action must reject source drift after parameter collection",
            action.contains("if (!isSourceRevisionCurrent(project, sourceRevision)) return"),
        )
        assertTrue(
            "EDT continuation must reject source drift before preview/execution",
            action.contains(
                "if (!isSourceRevisionCurrent(project, sourceRevision)) return@invokeLater",
            ),
        )
        assertTrue(
            "preview confirmation must re-check source revision before execution",
            action.contains(
                "if (!isSourceRevisionCurrent(project, sourceRevision)) return@invokeLater\n" +
                    "                            executeOnConsole(console, project, pureSql, sourceRevision)",
            ),
        )
        assertTrue(
            "Database Tools execution must receive the action-owned validity callback",
            action.contains(
                "preExecutionCheck = { LegacyActionSourceRevisionGuard.isCurrent(project, sourceRevision) }",
            ),
        )
    }

    @Test
    fun `database adapter rechecks invocation before delayed resource access and native query`() {
        val adapter = source(
            "src/main/kotlin/com/algorist/zMyBatis/execution/DatabaseToolsConsoleAdapter.kt",
        )
        val executeSql = adapter
            .substringAfter("fun executeSql(")
            .substringBefore(
                "@Suppress(\"UsePropertyAccessSyntax\", \"TooGenericExceptionCaught\"",
            )

        val initialCheck = executeSql.indexOf("if (!preExecutionCheck()) {")
        val documentAccess = executeSql.indexOf("val consoleDoc = console.document")
        assertTrue(initialCheck >= 0)
        assertTrue(documentAccess > initialCheck)

        val retry = executeSql
            .substringAfter("ApplicationManager.getApplication().invokeLater({")
            .substringBefore("}, ModalityState.any())")
        val retryCheck = retry.indexOf("if (!preExecutionCheck()) {")
        val retryEditorLookup = retry.indexOf("EditorFactory.getInstance().getEditors")
        assertTrue(retryCheck >= 0)
        assertTrue(retryEditorLookup > retryCheck)

        val perform = adapter
            .substringAfter("private fun performExecution(")
            .substringBefore(
                "@Suppress(\"UsePropertyAccessSyntax\")\n    private fun restoreConsoleDocument",
            )
        val initialPerformCheck = perform.indexOf("if (!preExecutionCheck()) {")
        val performDocumentAccess = perform.indexOf("val consoleDoc = console.document")
        val nativeQuery = perform.indexOf("JdbcConsoleProvider.doRunQueryInConsole")
        val finalCheck = perform.lastIndexOf("if (!preExecutionCheck()) {")

        assertTrue(initialPerformCheck >= 0)
        assertTrue(performDocumentAccess > initialPerformCheck)
        assertTrue(finalCheck > performDocumentAccess)
        assertTrue(nativeQuery > finalCheck)

        val finalBranch = perform.substring(finalCheck, nativeQuery)
        assertTrue(
            "source invalidation after SQL injection must restore original console text",
            finalBranch.contains("restoreConsoleDocumentAfterFailure(consoleDoc, originalText)"),
        )
        assertTrue(
            "adapter must report invalidation only after cleanup",
            finalBranch.indexOf("restoreConsoleDocumentAfterFailure(consoleDoc, originalText)") <
                finalBranch.indexOf(
                    "onFailure(DatabaseToolsSqlExecutionFailure.InvocationInvalidated)",
                ),
        )
    }

    @Test
    fun `temporary guard retains revision metadata rather than platform source objects`() {
        val guard = source(
            "src/main/kotlin/com/algorist/zMyBatis/LegacyActionSourceRevisionGuard.kt",
        )
        val revision = guard
            .substringAfter("internal data class LegacyActionSourceRevision(")
            .substringBefore(")")

        assertTrue(revision.contains("sourceUrl: String"))
        assertTrue(revision.contains("documentModificationStamp: Long"))
        assertTrue(!revision.contains("Editor"))
        assertTrue(!revision.contains("Document"))
        assertTrue(!revision.contains("Psi"))
        assertTrue(!revision.contains("VirtualFile"))
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
