@file:Suppress("DialogTitleCapitalization", "WrongInvocationKind", "ActionCallSuperActions", "UnstableApiUsage", "CallToAction")

package com.algorist.zMyBatis

import com.algorist.zMyBatis.MyBatisContextAnalyzer.analyze
import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.execution.DatabaseToolsConsoleAcquisitionFailure
import com.algorist.zMyBatis.execution.DatabaseToolsConsoleAdapter
import com.algorist.zMyBatis.execution.DatabaseToolsSqlExecutionFailure
import com.algorist.zMyBatis.execution.StoredExecutionTargetBridge
import com.algorist.zMyBatis.execution.StoredExecutionTargetResolution
import com.algorist.zMyBatis.services.ConsoleCacheService
import com.algorist.zMyBatis.settings.ConsoleSessionPolicy
import com.algorist.zMyBatis.settings.ZMyBatisSettings
import com.intellij.database.console.JdbcConsole
import com.intellij.database.model.DasNamespace
import com.intellij.database.psi.DbDataSource
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.DasUtil
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import java.awt.datatransfer.StringSelection

@Suppress("UnstableApiUsage", "TooManyFunctions")
open class MyBatisExecuteProxyAction : AnAction() {

    companion object {
        private val LOG = Logger.getInstance(MyBatisExecuteProxyAction::class.java)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            analyze(e) != MyBatisContextAnalyzer.ContextType.NONE
    }

    private fun isProjectUnavailable(project: com.intellij.openapi.project.Project): Boolean =
        project.isDisposed || ConsoleCacheService.getInstance(project).isShuttingDown()

    @Suppress("ReturnCount")
    override fun actionPerformed(e: AnActionEvent) {
        when (val context = analyze(e)) {
            MyBatisContextAnalyzer.ContextType.NONE -> return
            MyBatisContextAnalyzer.ContextType.PROVIDER -> {
                Messages.showInfoMessage(
                    e.project,
                    "@SelectProvider / @InsertProvider / @UpdateProvider / @DeleteProvider\n" +
                        "generate SQL dynamically at runtime.\n\n" +
                        "zMyBatis cannot statically extract the SQL from a Provider class.\n" +
                        "Please run the query directly from the generated SQL or a mapper XML.",
                    "zMyBatis: Provider Not Supported"
                )
            }
            else -> runMyBatisQuery(e, context)
        }
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun runMyBatisQuery(e: AnActionEvent, context: MyBatisContextAnalyzer.ContextType) {
        try {
            val project = e.project ?: return
            if (isProjectUnavailable(project)) return
            val editor = e.getData(CommonDataKeys.EDITOR) ?: return
            val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
            val sourceRevision = when (
                val capture = LegacyActionSourceRevisionGuard.capture(project, editor, psiFile)
            ) {
                is LegacyActionSourceRevisionCaptureResult.Captured -> capture.revision
                LegacyActionSourceRevisionCaptureResult.UncommittedSource -> {
                    showSourceRevisionRefusal(
                        project,
                        "The mapper source has uncommitted editor changes. " +
                            "Wait for IDE source synchronization and run zMyBatis again.",
                    )
                    return
                }
                LegacyActionSourceRevisionCaptureResult.SourceUnavailable -> {
                    showSourceRevisionRefusal(
                        project,
                        "The mapper source is no longer available. Run zMyBatis again.",
                    )
                    return
                }
            }

            val sqlContent = extractSqlContent(context, editor, psiFile)
            if (sqlContent == null) {
                LOG.warn("zMyBatis: extractSqlContent returned null. Context: $context")
                return
            }
            if (!isSourceRevisionCurrent(project, sourceRevision)) return

            val historyFileKey = psiFile.virtualFile?.path ?: psiFile.name
            val mapperKey = psiFile.viewProvider.virtualFile.url
            val sourceFileId = SourceFileId("vfs:$mapperKey")
            val statementKey = extractStatementKey(context, editor, psiFile, historyFileKey)
            val cache = ConsoleCacheService.getInstance(project)
            val targetBridge = StoredExecutionTargetBridge.forProject(project)
            val storedTarget = targetBridge.resolve(sourceFileId)
            val forceNew = ZMyBatisSettings.getInstance().consoleSessionPolicy == ConsoleSessionPolicy.NEW_EACH
            val cachedConsole = if (forceNew) {
                null
            } else {
                when (storedTarget) {
                    is StoredExecutionTargetResolution.Success ->
                        cache.get(mapperKey, storedTarget.targetId)
                    is StoredExecutionTargetResolution.Invalid -> null
                    StoredExecutionTargetResolution.Missing -> cache.get(mapperKey, expectedTargetId = null)
                }
            }

            if (cachedConsole != null) {
                LOG.info("zMyBatis: reusing cached console for $mapperKey")
                proceedWithParamsAndExecute(
                    e,
                    project,
                    sqlContent,
                    context,
                    cachedConsole,
                    statementKey,
                    sourceRevision,
                    sourceFileId,
                    targetBridge,
                    (storedTarget as? StoredExecutionTargetResolution.Success)?.targetId,
                )
            } else {
                if (project.isDisposed || cache.isShuttingDown()) return
                when (storedTarget) {
                    is StoredExecutionTargetResolution.Success -> {
                        if (!cache.beginSelection(mapperKey)) {
                            LOG.info("zMyBatis: console acquisition already in progress for $mapperKey")
                            return
                        }
                        try {
                            LOG.info("zMyBatis: resolved persisted execution target for $mapperKey")
                            acquireAndDeliverConsole(
                                project = project,
                                ds = storedTarget.dataSource,
                                schema = storedTarget.schema,
                                fileKey = mapperKey,
                                forceNew = forceNew,
                            ) { console ->
                                proceedWithParamsAndExecute(
                                    e,
                                    project,
                                    sqlContent,
                                    context,
                                    console,
                                    statementKey,
                                    sourceRevision,
                                    sourceFileId,
                                    targetBridge,
                                    storedTarget.targetId,
                                )
                            }
                        } finally {
                            cache.endSelection(mapperKey)
                        }
                    }
                    is StoredExecutionTargetResolution.Invalid -> {
                        cache.evict(mapperKey)
                        LOG.info(
                            "zMyBatis: persisted execution target is stale " +
                                "(code=${storedTarget.failure.code}); requiring explicit re-selection"
                        )
                        ensureConsole(
                            e,
                            project,
                            mapperKey,
                            sourceFileId,
                            targetBridge,
                            forceNew,
                        ) { console, targetId ->
                            proceedWithParamsAndExecute(
                                e,
                                project,
                                sqlContent,
                                context,
                                console,
                                statementKey,
                                sourceRevision,
                                sourceFileId,
                                targetBridge,
                                targetId,
                            )
                        }
                    }
                    StoredExecutionTargetResolution.Missing -> {
                        LOG.info("zMyBatis: no persisted execution target for $mapperKey; showing chooser")
                        ensureConsole(
                            e,
                            project,
                            mapperKey,
                            sourceFileId,
                            targetBridge,
                            forceNew,
                        ) { console, targetId ->
                            proceedWithParamsAndExecute(
                                e,
                                project,
                                sqlContent,
                                context,
                                console,
                                statementKey,
                                sourceRevision,
                                sourceFileId,
                                targetBridge,
                                targetId,
                            )
                        }
                    }
                }
            }
        } catch (ex: ProcessCanceledException) {
            throw ex
        } catch (ex: Exception) {
            LOG.warn("zMyBatis runMyBatisQuery failed (${ex::class.java.name})")
            Messages.showErrorDialog(e.project, "Error preparing MyBatis query:\n${ex.message}", "zMyBatis Error")
        } catch (fatal: Throwable) {
            LOG.error("zMyBatis fatal runMyBatisQuery failure", fatal)
            throw fatal
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun proceedWithParamsAndExecute(
        @Suppress("UNUSED_PARAMETER") e: AnActionEvent,
        project: com.intellij.openapi.project.Project,
        sqlContent: String,
        context: MyBatisContextAnalyzer.ContextType,
        console: JdbcConsole,
        statementKey: String? = null,
        sourceRevision: LegacyActionSourceRevision,
        sourceFileId: SourceFileId,
        targetBridge: StoredExecutionTargetBridge,
        expectedTargetId: ExecutionTargetId?,
    ) {
        if (isProjectUnavailable(project)) return
        if (!isInvocationCurrent(
                project,
                sourceRevision,
                sourceFileId,
                targetBridge,
                expectedTargetId,
            )
        ) return
        val paramValues = resolveParameters(project, sqlContent, statementKey)
        if (paramValues == null) {
            LOG.info("zMyBatis: resolveParameters returned null (user cancelled or failed)")
            return
        }
        if (!isInvocationCurrent(
                project,
                sourceRevision,
                sourceFileId,
                targetBridge,
                expectedTargetId,
            )
        ) return
        LOG.info("zMyBatis: parameters resolved (count=${paramValues.size})")

        ApplicationManager.getApplication().executeOnPooledThread {
            if (isProjectUnavailable(project)) return@executeOnPooledThread
            try {
                val rawSql = MyBatisEvaluator.evaluate(wrapForEvaluator(sqlContent, context), paramValues)
                LOG.info("zMyBatis: SQL evaluated (length=${rawSql.length})")
                val settings = ZMyBatisSettings.getInstance()

                ApplicationManager.getApplication().invokeLater {
                    if (isProjectUnavailable(project)) return@invokeLater
                    if (!isInvocationCurrent(
                            project,
                            sourceRevision,
                            sourceFileId,
                            targetBridge,
                            expectedTargetId,
                        )
                    ) return@invokeLater
                    val pureSql = if (settings.autoFormatSql) SqlFormatter.format(project, rawSql) else rawSql
                    if (settings.sqlPreview) {
                        val dialog = SqlPreviewDialog(project, pureSql)
                        if (dialog.showAndGet()) {
                            if (!isInvocationCurrent(
                                    project,
                                    sourceRevision,
                                    sourceFileId,
                                    targetBridge,
                                    expectedTargetId,
                                )
                            ) return@invokeLater
                            executeOnConsole(
                                console,
                                project,
                                pureSql,
                                sourceRevision,
                                sourceFileId,
                                targetBridge,
                                expectedTargetId,
                            )
                        } else {
                            LOG.info("zMyBatis: user cancelled from SQL preview dialog")
                        }
                    } else {
                        executeOnConsole(
                            console,
                            project,
                            pureSql,
                            sourceRevision,
                            sourceFileId,
                            targetBridge,
                            expectedTargetId,
                        )
                    }
                }
            } catch (ex: ProcessCanceledException) {
                throw ex
            } catch (ex: Exception) {
                LOG.warn("zMyBatis evaluation failed (${ex::class.java.name})")
                ApplicationManager.getApplication().invokeLater {
                    if (isProjectUnavailable(project)) return@invokeLater
                    Messages.showErrorDialog(project, "Error evaluating MyBatis SQL:\n${ex.message}", "zMyBatis Error")
                }
            } catch (fatal: Throwable) {
                LOG.error("zMyBatis fatal evaluation failure", fatal)
                throw fatal
            }
        }
    }

    @Suppress("TooGenericExceptionCaught", "LongMethod")
    private fun ensureConsole(
        originalEvent: AnActionEvent,
        project: com.intellij.openapi.project.Project,
        fileKey: String,
        sourceFileId: SourceFileId,
        targetBridge: StoredExecutionTargetBridge,
        forceNew: Boolean,
        onConsoleReady: (JdbcConsole, ExecutionTargetId?) -> Unit,
    ) {
        val cache = ConsoleCacheService.getInstance(project)
        if (!cache.beginSelection(fileKey)) {
            LOG.info("zMyBatis: console selection already in progress for $fileKey")
            return
        }

        try {
            val dataSources = DbPsiFacade.getInstance(project).dataSources.toList()
            if (dataSources.isEmpty()) {
                cache.endSelection(fileKey)
                Messages.showErrorDialog(
                    project,
                    "No data sources configured.\nPlease add a data source in the Database tool window first.",
                    "zMyBatis: No Data Source"
                )
                return
            }

            val group = DefaultActionGroup()
            for (ds in dataSources) {
                val dsGroup = DefaultActionGroup(ds.name, true)
                dsGroup.templatePresentation.icon = ds.icon

                dsGroup.add(object : AnAction("Use Default Schema") {
                    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
                    override fun actionPerformed(ignored: AnActionEvent) {
                        LOG.info("zMyBatis: Default schema selected for DS: ${ds.name}")
                        try {
                            if (project.isDisposed || cache.isShuttingDown()) return
                            val targetId = targetBridge.rememberTarget(
                                sourceFileId = sourceFileId,
                                dataSource = ds,
                                schemaName = null,
                            )
                            acquireAndDeliverConsole(
                                project,
                                ds,
                                null,
                                fileKey,
                                forceNew,
                            ) { console ->
                                onConsoleReady(console, targetId)
                            }
                        } finally {
                            cache.endSelection(fileKey)
                        }
                    }
                })
                dsGroup.addSeparator()

                val schemas = DasUtil.getSchemas(ds).toList()
                if (schemas.isNotEmpty()) {
                    for (schema in schemas) {
                        dsGroup.add(object : AnAction(schema.name) {
                            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
                            override fun actionPerformed(ignored: AnActionEvent) {
                                LOG.info("zMyBatis: Schema selected: ${schema.name} for DS: ${ds.name}")
                                try {
                                    if (project.isDisposed || cache.isShuttingDown()) return
                                    val targetId = targetBridge.rememberTarget(
                                        sourceFileId = sourceFileId,
                                        dataSource = ds,
                                        schemaName = schema.name,
                                    )
                                    acquireAndDeliverConsole(
                                        project,
                                        ds,
                                        schema,
                                        fileKey,
                                        forceNew,
                                    ) { console ->
                                        onConsoleReady(console, targetId)
                                    }
                                } finally {
                                    cache.endSelection(fileKey)
                                }
                            }
                        })
                    }
                } else {
                    dsGroup.add(object : AnAction("No schemas found") {
                        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
                        override fun update(e: AnActionEvent) { e.presentation.isEnabled = false }
                        override fun actionPerformed(e: AnActionEvent) { }
                    })
                }
                group.add(dsGroup)
            }

            ApplicationManager.getApplication().invokeLater({
                if (isProjectUnavailable(project)) {
                    cache.endSelection(fileKey)
                    return@invokeLater
                }
                try {
                    val popup = JBPopupFactory.getInstance().createActionGroupPopup(
                        "Choose Data Source & Schema",
                        group,
                        originalEvent.dataContext,
                        JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                        true
                    )
                    popup.addListener(object : JBPopupListener {
                        override fun onClosed(event: LightweightWindowEvent) {
                            if (!event.isOk) cache.endSelection(fileKey)
                        }
                    })
                    popup.showInBestPositionFor(originalEvent.dataContext)
                } catch (ex: ProcessCanceledException) {
                    cache.endSelection(fileKey)
                    throw ex
                } catch (ex: Exception) {
                    cache.endSelection(fileKey)
                    LOG.warn("zMyBatis: failed to show datasource chooser for $fileKey (${ex::class.java.name})")
                } catch (fatal: Throwable) {
                    cache.endSelection(fileKey)
                    LOG.error("zMyBatis: fatal datasource chooser failure for $fileKey", fatal)
                    throw fatal
                }
            }, ModalityState.any())
        } catch (ex: ProcessCanceledException) {
            cache.endSelection(fileKey)
            throw ex
        } catch (ex: Throwable) {
            cache.endSelection(fileKey)
            throw ex
        }
    }

    private fun acquireAndDeliverConsole(
        project: com.intellij.openapi.project.Project,
        ds: DbDataSource,
        schema: DasNamespace?,
        fileKey: String,
        forceNew: Boolean,
        onConsoleReady: (JdbcConsole) -> Unit,
    ) {
        DatabaseToolsConsoleAdapter.getInstance(project).acquireAndDeliverConsole(
            dataSource = ds,
            schema = schema,
            resourceKey = fileKey,
            forceNew = forceNew,
            onConsoleReady = onConsoleReady,
            onFailure = { failure ->
                when (failure) {
                    is DatabaseToolsConsoleAcquisitionFailure.SchemaSwitchFailed -> {
                        Messages.showErrorDialog(
                            project,
                            "Could not switch database console to schema '${failure.schemaName}'.\n" +
                                "The query was not executed because using the default schema would be unsafe.",
                            "zMyBatis: Schema Switch Failed",
                        )
                    }
                    is DatabaseToolsConsoleAcquisitionFailure.ConsoleCreationFailed -> {
                        if (!isProjectUnavailable(project)) {
                            Messages.showErrorDialog(
                                project,
                                "Could not create database console for ${failure.dataSourceName}.\n${failure.detail}",
                                "zMyBatis Error",
                            )
                        }
                    }
                }
            },
        )
    }

    @Suppress("UsePropertyAccessSyntax")
    private fun executeOnConsole(
        console: JdbcConsole,
        project: com.intellij.openapi.project.Project,
        pureSql: String,
        sourceRevision: LegacyActionSourceRevision,
        sourceFileId: SourceFileId,
        targetBridge: StoredExecutionTargetBridge,
        expectedTargetId: ExecutionTargetId?,
    ) {
        DatabaseToolsConsoleAdapter.getInstance(project).executeSql(
            console = console,
            sql = pureSql,
            preExecutionCheck = {
                LegacyActionSourceRevisionGuard.isCurrent(project, sourceRevision) &&
                    (expectedTargetId == null ||
                        targetBridge.isCurrent(sourceFileId, expectedTargetId))
            },
            onExecuted = {
                if (ZMyBatisSettings.getInstance().copyToClipboard) {
                    CopyPasteManager.getInstance().setContents(StringSelection(pureSql))
                }
            },
            onFailure = { failure ->
                when (failure) {
                    DatabaseToolsSqlExecutionFailure.InvocationInvalidated -> {
                        showInvocationInvalidated(
                            project,
                            sourceRevision,
                            sourceFileId,
                            targetBridge,
                            expectedTargetId,
                        )
                    }
                    DatabaseToolsSqlExecutionFailure.ConsoleUnavailable -> {
                        Messages.showErrorDialog(
                            project,
                            "The database console is no longer available.\n" +
                                "Run zMyBatis again to acquire a fresh execution console.",
                            "zMyBatis: Console Unavailable",
                        )
                    }
                    is DatabaseToolsSqlExecutionFailure.EditorUnavailable -> {
                        Messages.showErrorDialog(
                            project,
                            "Cannot find editor for console '${failure.consoleTitle}'.",
                            "zMyBatis Error",
                        )
                    }
                    DatabaseToolsSqlExecutionFailure.ScriptModelUnavailable -> {
                        Messages.showErrorDialog(
                            project,
                            "Failed to parse SQL for execution.",
                            "zMyBatis Error",
                        )
                    }
                    is DatabaseToolsSqlExecutionFailure.ExecutionFailed -> {
                        if (!isProjectUnavailable(project)) {
                            Messages.showErrorDialog(
                                project,
                                "Failed to execute SQL:\n${failure.detail}",
                                "zMyBatis: Execution Error",
                            )
                        }
                    }
                }
            },
        )
    }

    private fun isInvocationCurrent(
        project: com.intellij.openapi.project.Project,
        sourceRevision: LegacyActionSourceRevision,
        sourceFileId: SourceFileId,
        targetBridge: StoredExecutionTargetBridge,
        expectedTargetId: ExecutionTargetId?,
    ): Boolean {
        if (!isSourceRevisionCurrent(project, sourceRevision)) return false
        if (expectedTargetId == null) return true
        if (targetBridge.isCurrent(sourceFileId, expectedTargetId)) return true

        showTargetRevisionRefusal(project)
        return false
    }

    private fun showInvocationInvalidated(
        project: com.intellij.openapi.project.Project,
        sourceRevision: LegacyActionSourceRevision,
        sourceFileId: SourceFileId,
        targetBridge: StoredExecutionTargetBridge,
        expectedTargetId: ExecutionTargetId?,
    ) {
        if (!LegacyActionSourceRevisionGuard.isCurrent(project, sourceRevision)) {
            showSourceRevisionRefusal(
                project,
                "The mapper source changed while zMyBatis was preparing the query. " +
                    "Run zMyBatis again from the current source.",
            )
            return
        }
        if (expectedTargetId != null && !targetBridge.isCurrent(sourceFileId, expectedTargetId)) {
            showTargetRevisionRefusal(project)
            return
        }
        if (!isProjectUnavailable(project)) {
            Messages.showErrorDialog(
                project,
                "The execution context changed while zMyBatis was preparing the query. " +
                    "Run zMyBatis again.",
                "zMyBatis: Invocation Invalidated",
            )
        }
    }

    private fun showTargetRevisionRefusal(project: com.intellij.openapi.project.Project) {
        if (isProjectUnavailable(project)) return
        Messages.showErrorDialog(
            project,
            "The configured datasource or schema changed while zMyBatis was preparing the query. " +
                "Select the execution target again.",
            "zMyBatis: Target Changed",
        )
    }

    private fun isSourceRevisionCurrent(
        project: com.intellij.openapi.project.Project,
        sourceRevision: LegacyActionSourceRevision,
    ): Boolean {
        if (LegacyActionSourceRevisionGuard.isCurrent(project, sourceRevision)) return true
        showSourceRevisionRefusal(
            project,
            "The mapper source changed while zMyBatis was preparing the query. " +
                "Run zMyBatis again from the current source.",
        )
        return false
    }

    private fun showSourceRevisionRefusal(
        project: com.intellij.openapi.project.Project,
        message: String,
    ) {
        if (isProjectUnavailable(project)) return
        Messages.showErrorDialog(project, message, "zMyBatis: Source Changed")
    }

    @Suppress("ReturnCount")
    private fun extractSqlContent(
        context: MyBatisContextAnalyzer.ContextType,
        editor: Editor,
        psiFile: PsiFile
    ): String? {
        val baseOffset = if (editor.selectionModel.hasSelection()) {
            editor.selectionModel.selectionStart
        } else {
            editor.caretModel.offset
        }
        var offset = baseOffset
        if (offset > 0 && offset == psiFile.textLength) offset--

        var element = psiFile.findElementAt(offset)
        if (element is com.intellij.psi.PsiWhiteSpace && offset > 0) {
            element = psiFile.findElementAt(offset - 1)
        }
        if (element == null) return null

        return when (context) {
            MyBatisContextAnalyzer.ContextType.XML -> findMyBatisStatementTag(element)?.text
            MyBatisContextAnalyzer.ContextType.ANNOTATION -> {
                val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
                val annotation = method?.annotations?.firstOrNull {
                    it.qualifiedName in MyBatisContextAnalyzer.STATEMENT_ANNOTATIONS
                }
                AnnotationSqlExtractor.extract(annotation)
            }
            else -> null
        }
    }

    @Suppress("ReturnCount")
    private fun resolveParameters(
        project: com.intellij.openapi.project.Project,
        sqlContent: String,
        statementKey: String?
    ): Map<String, Any?>? {
        val extracted = ParameterExtractor.extractResult(sqlContent)
        LOG.info(
            "zMyBatis: parameters discovered " +
                "(count=${extracted.params.size}, objectCount=${extracted.objectParams.size})"
        )
        if (extracted.params.isEmpty()) return emptyMap()
        val dialog = ParameterInputDialog(project, extracted.params, extracted.objectParams, statementKey)
        if (!dialog.showAndGet()) return null
        val values = dialog.getValues()
        LOG.info("zMyBatis: parameter values collected (count=${values.size})")
        return values
    }

    private fun findMyBatisStatementTag(element: com.intellij.psi.PsiElement): XmlTag? {
        var tag: XmlTag? = PsiTreeUtil.getParentOfType(element, XmlTag::class.java, false)
        while (tag != null) {
            if (tag.name.lowercase() in MyBatisContextAnalyzer.MYBATIS_STATEMENT_TAGS) return tag
            tag = tag.parentTag
        }
        return null
    }

    private fun extractStatementKey(
        context: MyBatisContextAnalyzer.ContextType,
        editor: Editor,
        psiFile: PsiFile,
        fileKey: String
    ): String {
        val baseOffset = if (editor.selectionModel.hasSelection()) {
            editor.selectionModel.selectionStart
        } else {
            editor.caretModel.offset
        }
        var offset = baseOffset
        if (offset > 0 && offset == psiFile.textLength) offset--

        var element = psiFile.findElementAt(offset)
        if (element is com.intellij.psi.PsiWhiteSpace && offset > 0) {
            element = psiFile.findElementAt(offset - 1)
        }
        if (element == null) return fileKey

        return when (context) {
            MyBatisContextAnalyzer.ContextType.XML -> {
                val tag = findMyBatisStatementTag(element)
                val id = tag?.getAttributeValue("id") ?: return fileKey
                "$fileKey::$id"
            }
            MyBatisContextAnalyzer.ContextType.ANNOTATION -> {
                val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
                    ?: return fileKey
                val className = method.containingClass?.name ?: ""
                "$fileKey::$className#${method.name}"
            }
            else -> fileKey
        }
    }

    private fun wrapForEvaluator(sql: String, context: MyBatisContextAnalyzer.ContextType): String =
        if (context == MyBatisContextAnalyzer.ContextType.ANNOTATION && !sql.trim().startsWith("<script>")) {
            "<![CDATA[$sql]]>"
        } else {
            sql
        }
}

class MyBatisExecuteAction : MyBatisExecuteProxyAction()
