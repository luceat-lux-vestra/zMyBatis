package com.algorist.zMyBatis.execution

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseToolsConsoleAdapterBoundaryTest {

    @Test
    fun `registered action keeps low level Database Tools mechanics behind delivery adapter`() {
        val action = source("src/main/kotlin/com/algorist/zMyBatis/MyBatisExecuteAction.kt")
        val delivery = source(
            "src/main/kotlin/com/algorist/zMyBatis/execution/LeapExecutionDeliveryCoordinator.kt"
        )

        assertTrue(delivery.contains("consoleAdapter.acquireAndDeliverConsole("))
        assertTrue(delivery.contains("nativeBackend.execute("))
        assertTrue(delivery.contains("preparedBackend.execute("))
        assertFalse(delivery.contains("consoleAdapter.executeMaterialized("))
        assertFalse(action.contains("JdbcConsole.newConsole"))
        assertFalse(action.contains(".switchSchema("))
        assertFalse(action.contains("JdbcConsoleProvider.findScriptModelNoInject"))
        assertFalse(action.contains("JdbcConsoleProvider.doRunQueryInConsole"))
        assertFalse(action.contains("WriteCommandAction.runWriteCommandAction"))
        assertFalse(action.contains("FileEditorManager.getInstance"))
        assertFalse(action.contains("project.getService(FileEditorManager::class.java)"))
        assertFalse(action.contains("executeMaterialized("))
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
        // IDEA 263: use the stable, public navigation entry point, not either
        // the internal editor options API or the experimental file-open request.
        // The internal console must open without stealing focus from the mapper.
        // The EDT continuation must recheck the immutable execution authority
        // and the actual SQL editor before any native query is issued.
        assertTrue(adapter.contains("OpenFileDescriptor(project, vFile).navigate(false)"))
        assertFalse(adapter.contains("OpenFileDescriptor(project, vFile).navigate(true)"))
        assertFalse(adapter.contains("consoleEditor.contentComponent.requestFocusInWindow()"))
        assertTrue(
            adapter.contains(
                "val consoleName = resourceKey.substringAfterLast('/').substringAfterLast('\\\\')"
            )
        )
        assertFalse(adapter.contains("+ \" - zMyBatis\""))
        assertTrue(adapter.contains("ApplicationManager.getApplication().invokeLater({"))
        assertTrue(adapter.contains("if (!preExecutionCheck())"))
        assertTrue(adapter.contains("if (!console.isValid)"))
        assertTrue(adapter.contains("onFailure(DatabaseToolsSqlExecutionFailure.EditorUnavailable)"))
        assertFalse(adapter.contains("FileEditorManagerEx"))
        assertFalse(adapter.contains("FileEditorOpenOptions"))
        assertFalse(adapter.contains("FileEditorOpenRequest"))
        assertFalse(adapter.contains("requestOpenFile("))

        assertFalse(adapter.contains("ExecutionTargetDescriptorStore"))
        assertFalse(adapter.contains("StoredExecutionTargetBridge"))
        assertFalse(adapter.contains("PropertiesComponent"))
        assertFalse(adapter.contains("ZMyBatisSettings"))
        assertFalse(adapter.contains("CopyPasteManager"))
        assertFalse(adapter.contains("Messages.show"))
    }

    @Test
    fun `model-changing continuations use write-safe modality`() {
        val adapter = source(
            "src/main/kotlin/com/algorist/zMyBatis/execution/DatabaseToolsConsoleAdapter.kt"
        )
        val approval = source(
            "src/main/kotlin/com/algorist/zMyBatis/execution/LeapExecutionApprovalAdapter.kt"
        )

        assertFalse(adapter.contains("ModalityState.any()"))
        assertFalse(approval.contains("ModalityState.any()"))
        assertTrue(adapter.contains("ModalityState.defaultModalityState()"))
        assertTrue(approval.contains("ModalityState.defaultModalityState()"))
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

    @Test
    fun `REUSE lease is held through terminal execution instead of acquisition only`() {
        val adapter = source(
            "src/main/kotlin/com/algorist/zMyBatis/execution/DatabaseToolsConsoleAdapter.kt"
        )
        val delivery = source(
            "src/main/kotlin/com/algorist/zMyBatis/execution/LeapExecutionDeliveryCoordinator.kt"
        )

        assertTrue(adapter.contains("cache.beginReuseLease(resourceKey)"))
        assertTrue(adapter.contains("cache.endReuseLease(resourceKey)"))
        assertTrue(adapter.contains("onConsoleReady: (JdbcConsole, () -> Unit) -> Unit"))
        assertTrue(delivery.contains("onConsoleReady = { console, releaseConsole ->"))
        assertTrue(delivery.contains("onFinished = releaseConsole"))
        assertFalse(adapter.contains("beginSelection("))
        assertFalse(adapter.contains("endSelection("))
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
