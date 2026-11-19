package com.algorist.zMyBatis.execution

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseToolsExecutionResourceValidityContractTest {

    @Test
    fun `execute rejects disposed console before resource dereference`() {
        val adapter = source(
            "src/main/kotlin/com/algorist/zMyBatis/execution/DatabaseToolsConsoleAdapter.kt"
        )
        val executeSql = adapter
            .substringAfter("fun executeSql(")
            .substringBefore("@Suppress(\"UsePropertyAccessSyntax\", \"TooGenericExceptionCaught\"")

        val disposedCheck = executeSql.indexOf("!console.isValid")
        val documentAccess = executeSql.indexOf("val consoleDoc = console.document")
        val editorLookup = executeSql.indexOf("editorFactory.getEditors(consoleDoc, project)")

        assertTrue("executeSql must check console disposal", disposedCheck >= 0)
        assertTrue("console disposal must be checked before document access", documentAccess > disposedCheck)
        assertTrue("editor lookup must happen only after validated document access", editorLookup > documentAccess)
        assertTrue(
            "disposed console must become a typed execution failure",
            executeSql.contains("onFailure(DatabaseToolsSqlExecutionFailure.ConsoleUnavailable)"),
        )
    }

    @Test
    fun `missing persistent editor uses one transient off-screen editor and releases it`() {
        val adapter = source(
            "src/main/kotlin/com/algorist/zMyBatis/execution/DatabaseToolsConsoleAdapter.kt"
        )
        val executeSql = adapter
            .substringAfter("fun executeSql(")
            .substringBefore("@Suppress(\"UsePropertyAccessSyntax\", \"TooGenericExceptionCaught\"")

        assertTrue(
            "existing editor lookup must exclude disposed editors",
            executeSql.contains("firstOrNull { it is EditorEx && !it.isDisposed }"),
        )
        val create = executeSql.indexOf("editorFactory.createEditor(consoleDoc, project)")
        val execute = executeSql.indexOf("performExecution(", create)
        val release = executeSql.indexOf("editorFactory.releaseEditor(transientEditor)", execute)

        assertTrue("transient editor must be created only when no live editor exists", create >= 0)
        assertTrue("transient editor must drive the same execution boundary", execute > create)
        assertTrue("transient editor must be released after dispatch", release > execute)
        assertTrue("shipping execution must not navigate to a console tab", !executeSql.contains("OpenFileDescriptor"))
        assertTrue("shipping execution must not request console focus", !executeSql.contains("requestFocusInWindow"))
    }

    @Test
    fun `perform execution revalidates console and editor before native query`() {
        val adapter = source(
            "src/main/kotlin/com/algorist/zMyBatis/execution/DatabaseToolsConsoleAdapter.kt"
        )
        val perform = adapter
            .substringAfter("private fun performExecution(")
            .substringBefore("@Suppress(\"UsePropertyAccessSyntax\")\n    private fun restoreConsoleDocument")

        val firstDisposedCheck = perform.indexOf("!console.isValid")
        val documentAccess = perform.indexOf("val consoleDoc = console.document")
        val firstEditorCheck = perform.indexOf("consoleEditor.isDisposed")
        val nativeQuery = perform.indexOf("JdbcConsoleProvider.doRunQueryInConsole")
        val lastProjectCheck = perform.lastIndexOf("if (isProjectUnavailable())")
        val lastDisposedCheck = perform.lastIndexOf("!console.isValid")
        val lastEditorCheck = perform.lastIndexOf("consoleEditor.isDisposed")

        assertTrue("performExecution must check console before document access", firstDisposedCheck >= 0)
        assertTrue(documentAccess > firstDisposedCheck)
        assertTrue("performExecution must check editor before document access", firstEditorCheck >= 0)
        assertTrue(documentAccess > firstEditorCheck)

        assertTrue("native query invocation must exist", nativeQuery >= 0)
        assertTrue("project lifecycle must be rechecked after console document mutation", lastProjectCheck >= 0)
        val finalProjectBranch = perform.substring(lastProjectCheck, lastDisposedCheck)
        assertTrue(
            "lifecycle closure after document mutation must restore the original console text",
            finalProjectBranch.contains("restoreConsoleDocumentAfterFailure(consoleDoc, originalText)"),
        )
        assertTrue("console must be rechecked immediately before native execution path", lastDisposedCheck >= 0)
        assertTrue(nativeQuery > lastDisposedCheck)
        assertTrue("editor must be rechecked before native execution path", lastEditorCheck >= 0)
        assertTrue(nativeQuery > lastEditorCheck)
    }

    @Test
    fun `registered action preserves typed sql execution failure diagnostics`() {
        val adapter = source(
            "src/main/kotlin/com/algorist/zMyBatis/execution/DatabaseToolsConsoleAdapter.kt"
        )
        val action = source(
            "src/main/kotlin/com/algorist/zMyBatis/MyBatisExecuteAction.kt"
        )

        assertTrue(
            "adapter must declare typed console-unavailable execution failure",
            adapter.contains("data object ConsoleUnavailable : DatabaseToolsSqlExecutionFailure"),
        )
        assertTrue(
            "registered action must preserve typed delivery failure classification",
            action.contains("is LeapExecutionDeliveryFailure.NativeExecutionFailed"),
        )
        assertTrue(action.contains("\"native-execution-failed:\""))
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
