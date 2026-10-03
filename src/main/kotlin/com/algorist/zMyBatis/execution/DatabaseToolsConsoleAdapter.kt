package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.services.ConsoleCacheService
import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.database.model.DasNamespace
import com.intellij.database.psi.DbDataSource
import com.intellij.database.settings.DatabaseSettings
import com.intellij.database.util.DasUtil
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.LightVirtualFile

internal sealed interface DatabaseToolsConsoleAcquisitionFailure {
    data class SchemaSwitchFailed(val schemaName: String) : DatabaseToolsConsoleAcquisitionFailure
    data class ConsoleCreationFailed(
        val dataSourceName: String,
        val detail: String?,
    ) : DatabaseToolsConsoleAcquisitionFailure
}

internal sealed interface DatabaseToolsSqlExecutionFailure {
    data class EditorUnavailable(val consoleTitle: String) : DatabaseToolsSqlExecutionFailure
    data object ScriptModelUnavailable : DatabaseToolsSqlExecutionFailure
    data class ExecutionFailed(val detail: String) : DatabaseToolsSqlExecutionFailure
}

/**
 * Project-scoped boundary for low-level JetBrains Database Tools console mechanics.
 *
 * This adapter owns only ephemeral console/resource operations. Target persistence, target
 * selection, mapper/input semantics, preview/confirmation, and clipboard policy remain outside.
 */
@Service(Service.Level.PROJECT)
internal class DatabaseToolsConsoleAdapter(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(DatabaseToolsConsoleAdapter::class.java)

        fun getInstance(project: Project): DatabaseToolsConsoleAdapter = project.service()
    }

    private fun isProjectUnavailable(): Boolean =
        project.isDisposed || ConsoleCacheService.getInstance(project).isShuttingDown()

    @Suppress("TooGenericExceptionCaught")
    fun acquireAndDeliverConsole(
        dataSource: DbDataSource,
        schema: DasNamespace?,
        resourceKey: String,
        forceNew: Boolean,
        onConsoleReady: (JdbcConsole) -> Unit,
        onFailure: (DatabaseToolsConsoleAcquisitionFailure) -> Unit,
    ) {
        var console: JdbcConsole? = null
        try {
            if (isProjectUnavailable()) return
            val cache = ConsoleCacheService.getInstance(project)
            val sqlFileType = FileTypeManager.getInstance().getFileTypeByExtension("sql")
            val consoleName = resourceKey.substringAfterLast('/').substringAfterLast('\\') + " - zMyBatis"
            val lightFile = LightVirtualFile(consoleName, sqlFileType, "")

            console = JdbcConsole.newConsole(project)
                .fromDataSource(dataSource)
                .forFile(lightFile)
                .build()
            LOG.info("zMyBatis: console created for ${dataSource.name} (name=$consoleName)")

            if (schema != null && !switchSchemaOnConsole(console, schema)) {
                val schemaName = schema.name
                Disposer.dispose(console)
                console = null
                onFailure(DatabaseToolsConsoleAcquisitionFailure.SchemaSwitchFailed(schemaName))
                return
            }

            val schemaName = schema?.name

            if (!forceNew) {
                cache.putEphemeral(
                    mapperKey = resourceKey,
                    console = console,
                )
                if (cache.get(resourceKey) !== console) {
                    LOG.warn("zMyBatis: console was not live after cache registration for $resourceKey — skipping query")
                    Disposer.dispose(console)
                    console = null
                    return
                }
            }

            if (isProjectUnavailable()) {
                Disposer.dispose(console)
                console = null
                return
            }

            onConsoleReady(console)
            console = null
            LOG.info(
                "zMyBatis: session prepared for $resourceKey " +
                    "(ds=${dataSource.name}, schema=${schemaName ?: "<default>"})"
            )
        } catch (ex: ProcessCanceledException) {
            console?.let { Disposer.dispose(it) }
            throw ex
        } catch (ex: Throwable) {
            console?.let { Disposer.dispose(it) }
            LOG.error("zMyBatis: failed to create console for ${dataSource.name}", ex)
            if (!isProjectUnavailable()) {
                onFailure(
                    DatabaseToolsConsoleAcquisitionFailure.ConsoleCreationFailed(
                        dataSourceName = dataSource.name,
                        detail = ex.message,
                    )
                )
            }
        }
    }

    private fun switchSchemaOnConsole(console: JdbcConsole, schema: DasNamespace): Boolean =
        try {
            val kind = DasUtil.getKind(schema)
            val path = ObjectPath.create(schema.name, kind)
            console.switchSchema(SearchPath.of(path), false)
            LOG.info("zMyBatis: schema '${schema.name}' (kind=$kind) switched on console")
            true
        } catch (ex: ProcessCanceledException) {
            throw ex
        } catch (ex: Throwable) {
            LOG.warn("zMyBatis: failed to switch schema '${schema.name}'", ex)
            false
        }

    fun executeSql(
        console: JdbcConsole,
        sql: String,
        onExecuted: () -> Unit,
        onFailure: (DatabaseToolsSqlExecutionFailure) -> Unit,
    ) {
        if (isProjectUnavailable()) return
        if (sql.isBlank()) {
            LOG.warn("zMyBatis: SQL is blank, skipping execution")
            return
        }

        val consoleDoc = console.document
        val consolePsiFile = console.file
        val existingEditor = EditorFactory.getInstance().getEditors(consoleDoc, project)
            .firstOrNull { it is EditorEx } as? EditorEx

        if (existingEditor == null) {
            LOG.info("zMyBatis: no existing editor for console '${console.title}', attempting to open...")
            val vFile = consolePsiFile.virtualFile
            if (vFile != null) {
                FileEditorManager.getInstance(project).openFile(vFile, true)
                ApplicationManager.getApplication().invokeLater({
                    if (isProjectUnavailable()) return@invokeLater
                    val retryEditor = EditorFactory.getInstance().getEditors(consoleDoc, project)
                        .firstOrNull { it is EditorEx } as? EditorEx
                    if (retryEditor != null) {
                        performExecution(console, sql, retryEditor, onExecuted, onFailure)
                    } else {
                        LOG.warn("zMyBatis: editor still null after opening for '${console.title}'")
                        onFailure(DatabaseToolsSqlExecutionFailure.EditorUnavailable(console.title))
                    }
                }, ModalityState.any())
            } else {
                LOG.warn("zMyBatis: console virtual file is null")
            }
        } else {
            performExecution(console, sql, existingEditor, onExecuted, onFailure)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun performExecution(
        console: JdbcConsole,
        sql: String,
        consoleEditor: EditorEx,
        onExecuted: () -> Unit,
        onFailure: (DatabaseToolsSqlExecutionFailure) -> Unit,
    ) {
        if (isProjectUnavailable()) return
        val consoleDoc = console.document
        val consolePsiFile = console.file
        val originalText = consoleDoc.text

        try {
            consoleEditor.contentComponent.requestFocusInWindow()

            WriteCommandAction.runWriteCommandAction(project, "zMyBatis: Inject SQL", null, {
                consoleDoc.text = sql
                consoleEditor.selectionModel.setSelection(0, sql.length)
                consoleEditor.caretModel.moveToOffset(0)
                PsiDocumentManager.getInstance(project).commitDocument(consoleDoc)
            })

            val fullRange = TextRange(0, consoleDoc.textLength)
            val info = JdbcConsoleProvider.findScriptModelNoInject(
                project,
                consolePsiFile,
                consoleEditor,
                fullRange,
                DatabaseSettings.getDefaultExecOption(),
            )
            if (info == null) {
                LOG.warn("zMyBatis: findScriptModelNoInject returned null (SQL length=${sql.length})")
                restoreConsoleDocument(consoleDoc, originalText)
                onFailure(DatabaseToolsSqlExecutionFailure.ScriptModelUnavailable)
                return
            }

            if (isProjectUnavailable()) return
            LOG.info("zMyBatis: executing on console '${console.title}'")
            JdbcConsoleProvider.doRunQueryInConsole(console, info)
            onExecuted()
        } catch (ex: ProcessCanceledException) {
            restoreConsoleDocumentAfterFailure(consoleDoc, originalText)
            throw ex
        } catch (ex: Throwable) {
            LOG.error("zMyBatis: execution failed", ex)
            restoreConsoleDocumentAfterFailure(consoleDoc, originalText)
            if (!isProjectUnavailable()) {
                onFailure(
                    DatabaseToolsSqlExecutionFailure.ExecutionFailed(
                        ex.message ?: ex.javaClass.simpleName,
                    )
                )
            }
        }
    }

    private fun restoreConsoleDocument(
        consoleDoc: com.intellij.openapi.editor.Document,
        originalText: String,
    ) {
        WriteCommandAction.runWriteCommandAction(project) {
            consoleDoc.text = originalText
            PsiDocumentManager.getInstance(project).commitDocument(consoleDoc)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun restoreConsoleDocumentAfterFailure(
        consoleDoc: com.intellij.openapi.editor.Document,
        originalText: String,
    ) {
        try {
            restoreConsoleDocument(consoleDoc, originalText)
        } catch (restoreEx: Throwable) {
            LOG.warn("zMyBatis: failed to restore console document: ${restoreEx.message}")
        }
    }
}
