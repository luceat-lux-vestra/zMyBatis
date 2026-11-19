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
    fun `adapter reuses chooses or creates a console without opening a console tab`() {
        val adapter = source(
            "src/main/kotlin/com/algorist/zMyBatis/execution/DatabaseToolsConsoleAdapter.kt"
        )

        assertTrue(adapter.contains("DatabaseSessionManager.getSessions(project, sessionDataSource)"))
        assertTrue(adapter.contains("session.clientsWithFile"))
        assertTrue(adapter.contains(".filterIsInstance<JdbcConsole>()"))
        assertTrue(adapter.contains("cache.rememberedConsole(resourceKey, targetId, consoles)"))
        assertTrue(adapter.contains("cache.rememberConsole(resourceKey, targetId, console)"))
        assertTrue(adapter.contains("console.file.virtualFile?.name == mapperName"))
        assertTrue(adapter.contains("JBPopupFactory.getInstance().createActionGroupPopup("))
        assertTrue(adapter.contains("\"Choose Database Console\""))
        assertTrue(adapter.contains("\"Create New Console\""))
        assertTrue(adapter.contains("JdbcConsole.newConsole(project)"))
        assertTrue(adapter.contains("nextConsoleName("))
        assertTrue(adapter.contains("cache.putEphemeral(ownershipKey, console, targetId)"))
        assertTrue(adapter.contains(".switchSchema("))
        assertTrue(adapter.contains("JdbcConsoleProvider.findScriptModelNoInject"))
        assertTrue(adapter.contains("JdbcConsoleProvider.doRunQueryInConsole"))
        assertTrue(adapter.contains("editorFactory.createEditor(consoleDoc, project)"))
        assertTrue(adapter.contains("editorFactory.releaseEditor(transientEditor)"))

        assertFalse(adapter.contains("CustomerMapper.xml"))
        assertFalse(adapter.contains("OpenFileDescriptor"))
        assertFalse(adapter.contains(".navigate("))
        assertFalse(adapter.contains("requestFocusInWindow"))
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
    fun `adapter chooser scheduling never becomes editor-tab navigation`() {
        val adapter = source(
            "src/main/kotlin/com/algorist/zMyBatis/execution/DatabaseToolsConsoleAdapter.kt"
        )
        val approval = source(
            "src/main/kotlin/com/algorist/zMyBatis/execution/LeapExecutionApprovalAdapter.kt"
        )

        assertTrue(adapter.contains("ApplicationManager.getApplication().invokeLater"))
        assertTrue(adapter.contains("ModalityState.any()"))
        assertFalse(adapter.contains("OpenFileDescriptor"))
        assertFalse(adapter.contains(".navigate("))
        assertFalse(approval.contains("ModalityState.any()"))
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
