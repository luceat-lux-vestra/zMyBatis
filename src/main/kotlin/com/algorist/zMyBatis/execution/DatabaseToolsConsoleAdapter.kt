package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.ResolvedExecutionTarget
import com.algorist.zMyBatis.core.materialization.MaterializedExecution
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.services.ConsoleCacheService
import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.database.console.session.DatabaseSessionManager
import com.intellij.database.model.DasNamespace
import com.intellij.database.psi.DbDataSource
import com.intellij.database.settings.DatabaseSettings
import com.intellij.database.util.DasUtil
import com.intellij.database.util.DbImplUtil
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.LightVirtualFile
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface DatabaseToolsConsoleAcquisitionFailure {
    data object ReuseBusy : DatabaseToolsConsoleAcquisitionFailure
    data object SchemaSwitchFailed : DatabaseToolsConsoleAcquisitionFailure
    data object ConsoleCreationFailed : DatabaseToolsConsoleAcquisitionFailure
    data object ConsoleSelectionUnavailable : DatabaseToolsConsoleAcquisitionFailure
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
 * This adapter prefers existing Database Tools console/session clients, remembers runtime
 * source+target selections, and creates one console only when no suitable client exists or the user
 * explicitly chooses creation. Existing consoles remain Database Tools-owned; only zMyBatis-created
 * consoles are lifecycle-owned here.
 * Target persistence, target selection, mapper/input semantics, preview/confirmation, and clipboard
 * policy remain outside.
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
        targetId: ExecutionTargetId? = null,
        resourceKey: String,
        dataContext: DataContext? = null,
        onCancelled: () -> Unit = {},
        onConsoleReady: (JdbcConsole, () -> Unit) -> Unit,
        onFailure: (DatabaseToolsConsoleAcquisitionFailure) -> Unit,
    ) {
        if (isProjectUnavailable()) return

        val cache = ConsoleCacheService.getInstance(project)
        if (!cache.beginReuseLease(resourceKey)) {
            if (!isProjectUnavailable()) {
                onFailure(DatabaseToolsConsoleAcquisitionFailure.ReuseBusy)
            }
            return
        }

        val sourceLeaseReleased = AtomicBoolean(false)
        fun releaseSourceLease() {
            if (sourceLeaseReleased.compareAndSet(false, true)) {
                cache.endReuseLease(resourceKey)
            }
        }

        fun fail(failure: DatabaseToolsConsoleAcquisitionFailure) {
            releaseSourceLease()
            if (!isProjectUnavailable()) {
                onFailure(failure)
            }
        }

        fun cancel() {
            releaseSourceLease()
            if (!isProjectUnavailable()) {
                onCancelled()
            }
        }

        fun deliver(
            console: JdbcConsole,
            remember: Boolean = true,
            createdByZMyBatis: Boolean = false,
        ) {
            if (isProjectUnavailable() || !console.isValid) {
                releaseSourceLease()
                return
            }

            val consoleLeaseKey = consoleLeaseKey(console)
            if (!cache.beginReuseLease(consoleLeaseKey)) {
                fail(DatabaseToolsConsoleAcquisitionFailure.ReuseBusy)
                return
            }
            val released = AtomicBoolean(false)
            val releaseAll = {
                if (released.compareAndSet(false, true)) {
                    cache.endReuseLease(consoleLeaseKey)
                    releaseSourceLease()
                }
            }

            if (schema != null && !switchSchemaOnConsole(console, schema)) {
                releaseAll()
                if (createdByZMyBatis && console.isValid) {
                    Disposer.dispose(console)
                }
                if (!isProjectUnavailable()) {
                    onFailure(DatabaseToolsConsoleAcquisitionFailure.SchemaSwitchFailed)
                }
                return
            }

            if (createdByZMyBatis) {
                val ownershipKey = "created-console:" + consoleLeaseKey(console)
                if (!cache.putEphemeral(ownershipKey, console, targetId)) {
                    releaseAll()
                    if (console.isValid) {
                        Disposer.dispose(console)
                    }
                    if (!isProjectUnavailable()) {
                        onFailure(DatabaseToolsConsoleAcquisitionFailure.ConsoleCreationFailed)
                    }
                    return
                }
            }

            if (remember && targetId != null) {
                cache.rememberConsole(resourceKey, targetId, console)
            }
            onConsoleReady(console, releaseAll)
        }

        fun createAndDeliver(
            sessionDataSource: com.intellij.database.dataSource.LocalDataSource,
            existing: List<JdbcConsole>,
        ) {
            val console = createConsole(
                dataSource = sessionDataSource,
                resourceKey = resourceKey,
                existing = existing,
            )
            if (console == null) {
                fail(DatabaseToolsConsoleAcquisitionFailure.ConsoleCreationFailed)
                return
            }

            deliver(
                console = console,
                createdByZMyBatis = true,
            )
            LOG.info("zMyBatis: Database Tools console created for mapper execution")
        }

        try {
            val sessionDataSource = DbImplUtil.getMaybeLocalDataSource(dataSource)
            if (sessionDataSource == null) {
                fail(DatabaseToolsConsoleAcquisitionFailure.ConsoleCreationFailed)
                return
            }

            val consoles = DatabaseSessionManager.getSessions(project, sessionDataSource)
                .asSequence()
                .flatMap { session -> session.clients.asSequence() }
                .filterIsInstance<JdbcConsole>()
                .filter { console -> console.isValid }
                .distinct()
                .toList()

            targetId
                ?.let { cache.rememberedConsole(resourceKey, it) }
                ?.let { remembered ->
                    deliver(remembered)
                    LOG.info("zMyBatis: remembered Database Tools console reused")
                    return
                }

            val mapperName = mapperDisplayName(resourceKey)
            val mapperMatches = consoles.filter { console ->
                console.file.virtualFile?.name == mapperName
            }

            when {
                mapperMatches.size == 1 -> {
                    deliver(mapperMatches.single())
                    LOG.info("zMyBatis: unique mapper-name console reused")
                }
                consoles.size == 1 -> {
                    deliver(consoles.single())
                    LOG.info("zMyBatis: sole Database Tools console reused")
                }
                consoles.isEmpty() -> createAndDeliver(sessionDataSource, consoles)
                else -> {
                    if (dataContext == null) {
                        fail(DatabaseToolsConsoleAcquisitionFailure.ConsoleSelectionUnavailable)
                        return
                    }
                    requestConsoleChoice(
                        consoles = consoles,
                        dataContext = dataContext,
                        onSelected = { selected -> deliver(selected) },
                        onCreate = { createAndDeliver(sessionDataSource, consoles) },
                        onCancelled = ::cancel,
                        onFailure = {
                            fail(DatabaseToolsConsoleAcquisitionFailure.ConsoleSelectionUnavailable)
                        },
                    )
                }
            }
        } catch (ex: ProcessCanceledException) {
            releaseSourceLease()
            throw ex
        } catch (ex: Exception) {
            LOG.warn("zMyBatis: console acquisition failed (type=${ex.javaClass.name})")
            fail(DatabaseToolsConsoleAcquisitionFailure.ConsoleCreationFailed)
        } catch (fatal: Throwable) {
            releaseSourceLease()
            LOG.error("zMyBatis: fatal console acquisition failure", fatal)
            throw fatal
        }
    }

    private fun requestConsoleChoice(
        consoles: List<JdbcConsole>,
        dataContext: DataContext,
        onSelected: (JdbcConsole) -> Unit,
        onCreate: () -> Unit,
        onCancelled: () -> Unit,
        onFailure: () -> Unit,
    ) {
        ApplicationManager.getApplication().invokeLater({
            if (isProjectUnavailable()) {
                onCancelled()
                return@invokeLater
            }

            val completed = AtomicBoolean(false)
            fun finish(action: () -> Unit) {
                if (completed.compareAndSet(false, true)) {
                    action()
                }
            }

            try {
                val group = DefaultActionGroup()
                consoles.forEachIndexed { index, console ->
                    group.add(
                        object : AnAction(consoleChoiceLabel(console, index)) {
                            override fun getActionUpdateThread(): ActionUpdateThread =
                                ActionUpdateThread.BGT

                            override fun actionPerformed(event: AnActionEvent) {
                                if (!console.isValid) {
                                    finish(onFailure)
                                } else {
                                    finish { onSelected(console) }
                                }
                            }
                        },
                    )
                }
                group.addSeparator()
                group.add(
                    object : AnAction("Create New Console") {
                        override fun getActionUpdateThread(): ActionUpdateThread =
                            ActionUpdateThread.BGT

                        override fun actionPerformed(event: AnActionEvent) {
                            finish(onCreate)
                        }
                    },
                )

                val popup = JBPopupFactory.getInstance().createActionGroupPopup(
                    "Choose Database Console",
                    group,
                    dataContext,
                    JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                    true,
                )
                popup.addListener(
                    object : JBPopupListener {
                        override fun onClosed(event: LightweightWindowEvent) {
                            if (!event.isOk) {
                                finish(onCancelled)
                            }
                        }
                    },
                )
                popup.showInBestPositionFor(dataContext)
            } catch (ex: ProcessCanceledException) {
                throw ex
            } catch (_: Exception) {
                finish(onFailure)
            }
        }, ModalityState.any())
    }

    private fun createConsole(
        dataSource: com.intellij.database.dataSource.LocalDataSource,
        resourceKey: String,
        existing: List<JdbcConsole>,
    ): JdbcConsole? =
        try {
            val sqlFileType = FileTypeManager.getInstance().getFileTypeByExtension("sql")
            val consoleName = nextConsoleName(
                baseName = mapperDisplayName(resourceKey),
                existing = existing,
            )
            val requestedSession = DatabaseSessionManager.getSession(project, dataSource)
            JdbcConsole.newConsole(project)
                .fromDataSource(dataSource)
                .useSession(requestedSession)
                .forFile(LightVirtualFile(consoleName, sqlFileType, ""))
                .build()
        } catch (ex: ProcessCanceledException) {
            throw ex
        } catch (ex: Exception) {
            LOG.warn("zMyBatis: console creation failed (type=${ex.javaClass.name})")
            null
        }

    private fun mapperDisplayName(resourceKey: String): String =
        resourceKey
            .removePrefix("vfs:")
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .ifBlank { "zMyBatis.sql" }

    private fun nextConsoleName(
        baseName: String,
        existing: List<JdbcConsole>,
    ): String {
        val existingNames = existing.mapNotNull { it.file.virtualFile?.name }.toSet()
        if (baseName !in existingNames) return baseName

        var ordinal = 2
        while ("$baseName ($ordinal)" in existingNames) {
            ordinal += 1
        }
        return "$baseName ($ordinal)"
    }

    private fun consoleChoiceLabel(
        console: JdbcConsole,
        index: Int,
    ): String {
        val file = console.file.virtualFile
        val name = file?.name?.takeIf { it.isNotBlank() } ?: "Database Console"
        return "$name  [${index + 1}]"
    }

    private fun consoleLeaseKey(console: JdbcConsole): String =
        console.file.virtualFile?.url?.let { "database-console:$it" }
            ?: "database-console-identity:${System.identityHashCode(console)}"

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
        val editorFactory = EditorFactory.getInstance()
        val existingEditor = editorFactory.getEditors(consoleDoc, project)
            .firstOrNull { it is EditorEx && !it.isDisposed } as? EditorEx

        if (existingEditor != null) {
            performExecution(
                console,
                sql,
                existingEditor,
                preExecutionCheck,
                onExecuted,
                onFailure,
                finish,
            )
            return
        }

        // Native Database Tools execution needs an Editor, but it does not need an editor tab.
        // Use a temporary off-screen editor over the existing console document and release it
        // immediately after the native request has been dispatched. The mapper remains active.
        val transientEditor = try {
            editorFactory.createEditor(consoleDoc, project) as? EditorEx
        } catch (canceled: ProcessCanceledException) {
            finish()
            throw canceled
        } catch (ex: Exception) {
            LOG.warn("zMyBatis: could not create transient console editor (type=${ex.javaClass.name})")
            null
        }
        if (transientEditor == null) {
            fail(DatabaseToolsSqlExecutionFailure.EditorUnavailable)
            return
        }
        try {
            performExecution(
                console,
                sql,
                transientEditor,
                preExecutionCheck,
                onExecuted,
                onFailure,
                finish,
            )
        } finally {
            editorFactory.releaseEditor(transientEditor)
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
            // The console editor is an internal execution surface. Do not request focus here:
            // the mapper that initiated zMyBatis remains the user's active editing context.
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
