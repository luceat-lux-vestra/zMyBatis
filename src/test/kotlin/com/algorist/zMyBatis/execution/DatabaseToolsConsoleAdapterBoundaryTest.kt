package com.algorist.zMyBatis.execution

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseToolsConsoleAdapterBoundaryTest {

    @Test
    fun `shipping action delegates low level Database Tools console mechanics`() {
        val action = source("src/main/kotlin/com/algorist/zMyBatis/MyBatisExecuteProxyAction.kt")

        assertTrue(action.contains("DatabaseToolsConsoleAdapter.getInstance"))
        assertFalse(action.contains("JdbcConsole.newConsole"))
        assertFalse(action.contains(".switchSchema("))
        assertFalse(action.contains("JdbcConsoleProvider.findScriptModelNoInject"))
        assertFalse(action.contains("JdbcConsoleProvider.doRunQueryInConsole"))
        assertFalse(action.contains("WriteCommandAction.runWriteCommandAction"))
        assertFalse(action.contains("FileEditorManager.getInstance"))
    }

    @Test
    fun `adapter owns console mechanics but no target persistence`() {
        val adapter = source(
            "src/main/kotlin/com/algorist/zMyBatis/execution/DatabaseToolsConsoleAdapter.kt"
        )

        assertTrue(adapter.contains("JdbcConsole.newConsole"))
        assertTrue(adapter.contains(".switchSchema("))
        assertTrue(adapter.contains("JdbcConsoleProvider.findScriptModelNoInject"))
        assertTrue(adapter.contains("JdbcConsoleProvider.doRunQueryInConsole"))
        assertTrue(adapter.contains("WriteCommandAction.runWriteCommandAction"))
        assertTrue(adapter.contains("FileEditorManager.getInstance"))

        assertFalse(adapter.contains("ExecutionTargetDescriptorStore"))
        assertFalse(adapter.contains("StoredExecutionTargetBridge"))
        assertFalse(adapter.contains("PropertiesComponent"))
        assertFalse(adapter.contains("ZMyBatisSettings"))
        assertFalse(adapter.contains("CopyPasteManager"))
        assertFalse(adapter.contains("Messages.show"))
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
