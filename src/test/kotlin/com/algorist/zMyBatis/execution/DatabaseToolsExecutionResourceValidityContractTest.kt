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

        val disposedCheck = executeSql.indexOf("Disposer.isDisposed(console)")
        val documentAccess = executeSql.indexOf("val consoleDoc = console.document")
        val fileAccess = executeSql.indexOf("val consolePsiFile = console.file")

        assertTrue("executeSql must check console disposal", disposedCheck >= 0)
        assertTrue("console disposal must be checked before document access", documentAccess > disposedCheck)
        assertTrue("console disposal must be checked before file access", fileAccess > disposedCheck)
        assertTrue(
            "disposed console must become a typed execution failure",
            executeSql.contains("onFailure(DatabaseToolsSqlExecutionFailure.ConsoleUnavailable)"),
        )
    }

    @Test
    fun `editor open retry revalidates console and excludes disposed editors`() {
        val adapter = source(
            "src/main/kotlin/com/algorist/zMyBatis/execution/DatabaseToolsConsoleAdapter.kt"
        )
        val executeSql = adapter
            .substringAfter("fun executeSql(")
            .substringBefore("@Suppress(\"UsePropertyAccessSyntax\", \"TooGenericExceptionCaught\"")
        val retry = executeSql
            .substringAfter("ApplicationManager.getApplication().invokeLater({")
            .substringBefore("}, ModalityState.any())")

        val disposedCheck = retry.indexOf("Disposer.isDisposed(console)")
        val editorLookup = retry.indexOf("EditorFactory.getInstance().getEditors")

        assertTrue("retry callback must re-check console disposal", disposedCheck >= 0)
        assertTrue("retry disposal check must precede editor lookup", editorLookup > disposedCheck)
        assertTrue(
            "initial and retry editor lookup must exclude disposed editors",
            Regex("""firstOrNull \{ it is EditorEx && !it\.isDisposed \}""")
                .findAll(executeSql)
                .count() >= 2,
        )
    }

    @Test
    fun `perform execution revalidates console and editor before native query`() {
        val adapter = source(
            "src/main/kotlin/com/algorist/zMyBatis/execution/DatabaseToolsConsoleAdapter.kt"
        )
        val perform = adapter
            .substringAfter("private fun performExecution(")
            .substringBefore("@Suppress(\"UsePropertyAccessSyntax\")\n    private fun restoreConsoleDocument")

        val firstDisposedCheck = perform.indexOf("Disposer.isDisposed(console)")
        val documentAccess = perform.indexOf("val consoleDoc = console.document")
        val firstEditorCheck = perform.indexOf("consoleEditor.isDisposed")
        val nativeQuery = perform.indexOf("JdbcConsoleProvider.doRunQueryInConsole")
        val lastDisposedCheck = perform.lastIndexOf("Disposer.isDisposed(console)")
        val lastEditorCheck = perform.lastIndexOf("consoleEditor.isDisposed")

        assertTrue("performExecution must check console before document access", firstDisposedCheck >= 0)
        assertTrue(documentAccess > firstDisposedCheck)
        assertTrue("performExecution must check editor before document access", firstEditorCheck >= 0)
        assertTrue(documentAccess > firstEditorCheck)

        assertTrue("native query invocation must exist", nativeQuery >= 0)
        assertTrue("console must be rechecked immediately before native execution path", lastDisposedCheck >= 0)
        assertTrue(nativeQuery > lastDisposedCheck)
        assertTrue("editor must be rechecked before native execution path", lastEditorCheck >= 0)
        assertTrue(nativeQuery > lastEditorCheck)
    }

    @Test
    fun `shipping action presents typed console unavailable refusal`() {
        val adapter = source(
            "src/main/kotlin/com/algorist/zMyBatis/execution/DatabaseToolsConsoleAdapter.kt"
        )
        val action = source(
            "src/main/kotlin/com/algorist/zMyBatis/MyBatisExecuteProxyAction.kt"
        )

        assertTrue(
            "adapter must declare typed console-unavailable execution failure",
            adapter.contains("data object ConsoleUnavailable : DatabaseToolsSqlExecutionFailure"),
        )
        assertTrue(
            "shipping action must handle the typed console-unavailable outcome",
            action.contains("DatabaseToolsSqlExecutionFailure.ConsoleUnavailable ->"),
        )
        assertTrue(action.contains("zMyBatis: Console Unavailable"))
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
