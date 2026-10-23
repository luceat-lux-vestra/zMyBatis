package com.algorist.zMyBatis.execution

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseToolsConsoleAdapterBoundaryTest {

    @Test
    fun `shipping action delegates through Leap before Database Tools mechanics`() {
        val action = source("src/main/kotlin/com/algorist/zMyBatis/MyBatisExecuteProxyAction.kt")
        val delivery = source(
            "src/main/kotlin/com/algorist/zMyBatis/execution/LeapExecutionDeliveryCoordinator.kt"
        )

        assertTrue(action.contains("LeapActionContextAdmissionCoordinator(project).execute("))
        assertFalse(action.contains("DatabaseToolsConsoleAdapter"))
        assertFalse(action.contains("JdbcConsole.newConsole"))
        assertFalse(action.contains(".switchSchema("))
        assertFalse(action.contains("JdbcConsoleProvider.findScriptModelNoInject"))
        assertFalse(action.contains("JdbcConsoleProvider.doRunQueryInConsole"))
        assertFalse(action.contains("WriteCommandAction.runWriteCommandAction"))
        assertFalse(action.contains("FileEditorManager.getInstance"))
        assertTrue(delivery.contains("consoleAdapter.executeMaterialized("))
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

    @Test
    fun `Leap artifact delivery preserves immutable SQL and explicit revalidation inputs`() {
        val adapter = source(
            "src/main/kotlin/com/algorist/zMyBatis/execution/DatabaseToolsConsoleAdapter.kt"
        )
        val method = adapter.substringAfter("fun executeMaterialized(")
            .substringBefore("\n    fun executeSql(")

        assertTrue(method.contains("val boundTarget = artifact.executionTargetId"))
        assertTrue(method.contains("val sourceRevisions = artifact.sourceRevisions"))
        assertTrue(method.contains("currentTarget = currentTarget"))
        assertTrue(method.contains("areSourceRevisionsCurrent = areSourceRevisionsCurrent"))
        assertTrue(method.contains("sql = artifact.executionSql"))
        assertFalse(method.contains("artifact.toString()"))
        assertFalse(method.contains("SqlFormatter"))
        assertTrue(
            adapter.contains(
                "currentTarget()?.targetId == boundTarget"
            )
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
