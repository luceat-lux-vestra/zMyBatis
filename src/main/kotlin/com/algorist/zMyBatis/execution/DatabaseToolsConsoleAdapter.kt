package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.ResolvedExecutionTarget
import com.algorist.zMyBatis.core.materialization.MaterializedExecution
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
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
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface DatabaseToolsConsoleAcquisitionFailure {
    data object ReuseBusy : DatabaseToolsConsoleAcquisitionFailure
    data object SchemaSwitchFailed : DatabaseToolsConsoleAcquisitionFailure
    data object ConsoleCreationFailed : DatabaseToolsConsoleAcquisitionFailure
}

internal sealed interface DatabaseToolsSqlExecutionFailure {
    data object InvocationInvalidated : DatabaseToolsSqlExecutionFailure
    data object ConsoleUnavailable : DatabaseToolsSqlExecutionFailure
    data object EditorUnavailable : DatabaseToolsSqlExecutionFailure
    data object ScriptModelUnavailable : DatabaseToolsSqlExecutionFailure
    data object ExecutionFailed : DatabaseToolsSqlExecutionFailure
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
        onConsoleReady: (JdbcConsole, () -> Unit) -> Unit,
        onFailure: (DatabaseToolsConsoleAcquisitionFailure) -> Unit,
    ) {
        if (isProjectUnavailable()) return

        val cache = ConsoleCacheService.getInstance(project)
        val reuseLeaseHeld = !forceNew && cache.beginReuseLease(resourceKey)
        if (!forceNew && !reuseLeaseHeld) {
            if (!isProjectUnavailable()) {
                onFailure(DatabaseToolsConsoleAcquisitionFailure.ReuseBusy)
            }
            return
        }

        val reuseLeaseReleased = AtomicBoolean(false)
        val releaseReuseLease = {
            if (reuseLeaseHeld && reuseLeaseReleased.compareAndSet(false, true)) {
                cache.endReuseLease(resourceKey)
            }
        }

        var console: JdbcConsole? = null
        try {
            if (isProjectUnavailable()) {
                releaseReuseLease()
                return
            }

            val targetId = databaseToolsExecutionTargetId(dataSource, schema)
            if (!forceNew) {
                val cached = cache.get(resourceKey, targetId)
                if (cached != null) {
                    if (schema != null && !switchSchemaOnConsole(cached, schema)) {
                        cache.evict(resourceKey)
                        releaseReuseLease()
                        onFailure(DatabaseToolsConsoleAcquisitionFailure.SchemaSwitchFailed)
                        return
                    }
                    onConsoleReady(cached, releaseReuseLease)
                    LOG.info("zMyBatis: matching live console reused")
                    return
                }
            }

            val sqlFileType = FileTypeManager.getInstance().getFileTypeByExtension("sql")
            val consoleName = resourceKey.substringAfterLast('/').substringAfterLast('\\') + " - zMyBatis"
            val lightFile = LightVirtualFile(consoleName, sqlFileType, "")

            console = JdbcConsole.newConsole(project)
                .fromDataSource(dataSource)
                .forFile(lightFile)
                .build()
            // Keep disposal authority with this resource, independently of Disposer diagnostics.
            val consoleLifetime = Disposer.newCheckedDisposable(console)
            LOG.info("zMyBatis: console created")

            if (schema != null && !switchSchemaOnConsole(console, schema)) {
                Disposer.dispose(console)
                console = null
                releaseReuseLease()
                onFailure(DatabaseToolsConsoleAcquisitionFailure.SchemaSwitchFailed)
                return
            }

            if (!forceNew) {
                if (!cache.putEphemeral(
                        mapperKey = resourceKey,
                        console = console,
                        targetId = targetId,
                    )
                ) {
                    if (!consoleLifetime.isDisposed) {
                        Disposer.dispose(console)
                    }
                    console = null
                    releaseReuseLease()
                    if (!isProjectUnavailable()) {
                        onFailure(DatabaseToolsConsoleAcquisitionFailure.ConsoleCreationFailed)
                    }
                    return
                }
                if (cache.get(resourceKey, targetId) !== console) {
                    LOG.warn("zMyBatis: console was not live after cache registration — skipping query")
                    if (!consoleLifetime.isDisposed) {
                        Disposer.dispose(console)
                    }
                    console = null
                    releaseReuseLease()
                    return
                }
            }

            if (isProjectUnavailable()) {
                Disposer.dispose(console)
                console = null
                releaseReuseLease()
                return
            }

            onConsoleReady(console, releaseReuseLease)
            console = null
            LOG.info("zMyBatis: session prepared")
        } catch (ex: ProcessCanceledException) {
            console?.let { Disposer.dispose(it) }
            releaseReuseLease()
            throw ex
        } catch (ex: Exception) {
            console?.let { Disposer.dispose(it) }
            releaseReuseLease()
            LOG.warn("zMyBatis: console creation failed (type=${ex.javaClass.name})")
            if (!isProjectUnavailable()) {
                onFailure(DatabaseToolsConsoleAcquisitionFailure.ConsoleCreationFailed)
            }
        } catch (fatal: Throwable) {
            console?.let { Disposer.dispose(it) }
            releaseReuseLease()
            LOG.error("zMyBatis: fatal console creation failure", fatal)
            throw fatal
        }
    }

    private fun switchSchemaOnConsole(console: JdbcConsole, schema: DasNamespace): Boolean =
        try {
            val kind = DasUtil.getKind(schema)
            val path = ObjectPath.create(schema.name, kind)
            console.switchSchema(SearchPath.of(path), false)
            LOG.info("zMyBatis: schema switched on console (kind=$kind)")
            true
        } catch (ex: ProcessCanceledException) {
            throw ex
        } catch (ex: Exception) {
            LOG.warn("zMyBatis: schema switch failed (type=${ex.javaClass.name})")
            false
        } catch (fatal: Throwable) {
            LOG.error("zMyBatis: fatal schema-switch failure", fatal)
            throw fatal
        }

    /**
     * Leap delivery entry point used by the registered execution authority.
     *
     * The caller owns invocation/cancellation and source-revision validity and supplies a freshly
     * resolved exact Database Tools target. Every asynchronous/native dispatch boundary reruns the
     * same checks against the immutable artifact snapshot before [executeSql] may continue.
     *
     * Neither preview text nor formatting output can become the execution payload.
     */
    fun executeMaterialized(
        console: JdbcConsole,
        artifact: MaterializedExecution,
        currentTarget: () -> ResolvedExecutionTarget?,
        isInvocationActive: () -> Boolean,
        areSourceRevisionsCurrent: (Map<SourceFileId, SourceRevision>) -> Boolean,
        onExecuted: () -> Unit,
        onFailure: (DatabaseToolsSqlExecutionFailure) -> Unit,
        onFinished: () -> Unit = {},
    ) {
        val boundTarget = artifact.executionTargetId
        val sourceRevisions = artifact.sourceRevisions
        val deliveryValid = try {
            materializedDeliveryValid(
                boundTarget = boundTarget,
                sourceRevisions = sourceRevisions,
                currentTarget = currentTarget,
                isInvocationActive = isInvocationActive,
                areSourceRevisionsCurrent = areSourceRevisionsCurrent,
            )
        } catch (failure: Throwable) {
            onFinished()
            throw failure
        }
        if (!deliveryValid) {
            try {
                onFailure(DatabaseToolsSqlExecutionFailure.InvocationInvalidated)
            } finally {
                onFinished()
            }
            return
        }

        executeSql(
            console = console,
            sql = artifact.executionSql,
            preExecutionCheck = {
                materializedDeliveryValid(
                    boundTarget = boundTarget,
                    sourceRevisions = sourceRevisions,
                    currentTarget = currentTarget,
                    isInvocationActive = isInvocationActive,
                    areSourceRevisionsCurrent = areSourceRevisionsCurrent,
                )
            },
            onExecuted = onExecuted,
            onFailure = onFailure,
            onFinished = onFinished,
        )
    }

    fun executeSql(
        console: JdbcConsole,
        sql: String,
        preExecutionCheck: () -> Boolean,
        onExecuted: () -> Unit,
        onFailure: (DatabaseToolsSqlExecutionFailure) -> Unit,
        onFinished: () -> Unit = {},
    ) {
        val finished = AtomicBoolean(false)
        val finish = {
            if (finished.compareAndSet(false, true)) {
                onFinished()
            }
        }
        fun fail(failure: DatabaseToolsSqlExecutionFailure) {
            try {
                onFailure(failure)
            } finally {
                finish()
            }
        }

        if (isProjectUnavailable()) {
            finish()
            return
        }
        if (!preExecutionCheck()) {
            fail(DatabaseToolsSqlExecutionFailure.InvocationInvalidated)
            return
        }
        if (!console.isValid) {
            LOG.warn("zMyBatis: console unavailable before SQL execution")
            fail(DatabaseToolsSqlExecutionFailure.ConsoleUnavailable)
            return
        }
        if (sql.isBlank()) {
            LOG.warn("zMyBatis: SQL is blank, skipping execution")
            finish()
            return
        }

        val consoleDoc = console.document
        val consolePsiFile = console.file
        val existingEditor = EditorFactory.getInstance().getEditors(consoleDoc, project)
            .firstOrNull { it is EditorEx && !it.isDisposed } as? EditorEx

        if (existingEditor == null) {
            LOG.info("zMyBatis: no existing console editor, attempting to open one")
            val vFile = consolePsiFile.virtualFile
            if (vFile != null) {
                FileEditorManager.getInstance(project).openFile(vFile, true)
                ApplicationManager.getApplication().invokeLater({
                    try {
                        if (isProjectUnavailable()) return@invokeLater
                        if (!preExecutionCheck()) {
                            onFailure(DatabaseToolsSqlExecutionFailure.InvocationInvalidated)
                            return@invokeLater
                        }
                        if (!console.isValid) {
                            LOG.warn("zMyBatis: console unavailable after editor-open scheduling")
                            onFailure(DatabaseToolsSqlExecutionFailure.ConsoleUnavailable)
                            return@invokeLater
                        }
                        val retryEditor = EditorFactory.getInstance().getEditors(consoleDoc, project)
                            .firstOrNull { it is EditorEx && !it.isDisposed } as? EditorEx
                        if (retryEditor != null) {
                            performExecution(
                                console,
                                sql,
                                retryEditor,
                                preExecutionCheck,
                                onExecuted,
                                onFailure,
                                finish,
                            )
                        } else {
                            LOG.warn("zMyBatis: console editor still unavailable after opening")
                            onFailure(DatabaseToolsSqlExecutionFailure.EditorUnavailable)
                        }
                    } finally {
                        finish()
                    }
                }, ModalityState.defaultModalityState())
            } else {
                LOG.warn("zMyBatis: console virtual file is null")
                finish()
            }
        } else {
            performExecution(
                console,
                sql,
                existingEditor,
                preExecutionCheck,
                onExecuted,
                onFailure,
                finish,
            )
        }
    }

    @Suppress("UsePropertyAccessSyntax", "TooGenericExceptionCaught", "DialogTitleCapitalization")
    private fun performExecution(
        console: JdbcConsole,
        sql: String,
        consoleEditor: EditorEx,
        preExecutionCheck: () -> Boolean,
        onExecuted: () -> Unit,
        onFailure: (DatabaseToolsSqlExecutionFailure) -> Unit,
        onFinished: () -> Unit,
    ) {
        if (isProjectUnavailable()) {
            onFinished()
            return
        }
        if (!preExecutionCheck()) {
            try {
                onFailure(DatabaseToolsSqlExecutionFailure.InvocationInvalidated)
            } finally {
                onFinished()
            }
            return
        }
        if (!console.isValid) {
            LOG.warn("zMyBatis: console unavailable before execution preparation")
            try {
                onFailure(DatabaseToolsSqlExecutionFailure.ConsoleUnavailable)
            } finally {
                onFinished()
            }
            return
        }
        if (consoleEditor.isDisposed) {
            LOG.warn("zMyBatis: console editor unavailable before execution preparation")
            try {
                onFailure(DatabaseToolsSqlExecutionFailure.EditorUnavailable)
            } finally {
                onFinished()
            }
            return
        }

        val consoleDoc = console.document
        val consolePsiFile = console.file
        val originalText = consoleDoc.text

        try {
            consoleEditor.contentComponent.requestFocusInWindow()

            WriteCommandAction.runWriteCommandAction(project, "zMyBatis: Inject SQL", null, {
                consoleDoc.setText(sql)
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

            if (isProjectUnavailable()) {
                restoreConsoleDocumentAfterFailure(consoleDoc, originalText)
                return
            }
            if (!preExecutionCheck()) {
                restoreConsoleDocumentAfterFailure(consoleDoc, originalText)
                onFailure(DatabaseToolsSqlExecutionFailure.InvocationInvalidated)
                return
            }
            if (!console.isValid) {
                LOG.warn("zMyBatis: console unavailable before native query invocation")
                restoreConsoleDocumentAfterFailure(consoleDoc, originalText)
                onFailure(DatabaseToolsSqlExecutionFailure.ConsoleUnavailable)
                return
            }
            if (consoleEditor.isDisposed) {
                LOG.warn("zMyBatis: console editor unavailable before native query invocation")
                restoreConsoleDocumentAfterFailure(consoleDoc, originalText)
                onFailure(DatabaseToolsSqlExecutionFailure.EditorUnavailable)
                return
            }

            LOG.info("zMyBatis: executing prepared artifact on console")
            JdbcConsoleProvider.doRunQueryInConsole(console, info)
            onExecuted()
        } catch (ex: ProcessCanceledException) {
            restoreConsoleDocumentAfterFailure(consoleDoc, originalText)
            throw ex
        } catch (ex: Exception) {
            LOG.warn("zMyBatis: SQL execution failed (type=${ex.javaClass.name})")
            restoreConsoleDocumentAfterFailure(consoleDoc, originalText)
            if (!isProjectUnavailable()) {
                onFailure(DatabaseToolsSqlExecutionFailure.ExecutionFailed)
            }
        } catch (fatal: Throwable) {
            LOG.error("zMyBatis: fatal SQL execution failure", fatal)
            restoreConsoleDocumentAfterFailure(consoleDoc, originalText)
            throw fatal
        } finally {
            onFinished()
        }
    }

    @Suppress("UsePropertyAccessSyntax")
    private fun restoreConsoleDocument(
        consoleDoc: com.intellij.openapi.editor.Document,
        originalText: String,
    ) {
        WriteCommandAction.runWriteCommandAction(project) {
            consoleDoc.setText(originalText)
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
        } catch (restoreEx: ProcessCanceledException) {
            throw restoreEx
        } catch (restoreEx: Exception) {
            LOG.warn(
                "zMyBatis: failed to restore console document " +
                    "(type=${restoreEx.javaClass.name})"
            )
        }
    }
}

/**
 * Shared fail-closed admission predicate for immutable artifact delivery.
 *
 * Ordering is deliberate: an unbound artifact, cancelled invocation, or stale source never reaches
 * target lookup. The target supplied by the future #66 caller must be freshly resolved through the
 * maintained Database Tools exact datasource/schema resolver.
 */
internal fun materializedDeliveryValid(
    boundTarget: ExecutionTargetId?,
    sourceRevisions: Map<SourceFileId, SourceRevision>,
    currentTarget: () -> ResolvedExecutionTarget?,
    isInvocationActive: () -> Boolean,
    areSourceRevisionsCurrent: (Map<SourceFileId, SourceRevision>) -> Boolean,
): Boolean =
    boundTarget != null &&
        isInvocationActive() &&
        areSourceRevisionsCurrent(sourceRevisions) &&
        currentTarget()?.targetId == boundTarget
