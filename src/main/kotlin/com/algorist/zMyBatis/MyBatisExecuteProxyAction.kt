@file:Suppress("DialogTitleCapitalization", "WrongInvocationKind", "ActionCallSuperActions", "UnstableApiUsage", "CallToAction")

package com.algorist.zMyBatis

import com.algorist.zMyBatis.MyBatisContextAnalyzer.analyze
import com.algorist.zMyBatis.services.ConsoleCacheService
import com.algorist.zMyBatis.settings.ConsoleSessionPolicy
import com.algorist.zMyBatis.settings.ZMyBatisSettings
import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.database.model.DasNamespace
import com.intellij.database.psi.DbDataSource
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.settings.DatabaseSettings
import com.intellij.database.util.DasUtil
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import java.awt.datatransfer.StringSelection
import java.util.concurrent.ConcurrentHashMap

/** Guard against multiple concurrent console selection dialogs for the same file. */
private val activeSelections = ConcurrentHashMap.newKeySet<String>()

@Suppress("UnstableApiUsage", "TooManyFunctions")
open class MyBatisExecuteProxyAction : AnAction() {

    companion object {
        private val LOG = Logger.getInstance(MyBatisExecuteProxyAction::class.java)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        // Always keep the action visible so it behaves like the original in all contexts
        // (DB console toolbar, right-click menu, MyBatis XML/annotation files, etc.).
        e.presentation.isEnabledAndVisible = true
    }


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

    @Suppress("ReturnCount")
    private fun runMyBatisQuery(e: AnActionEvent, context: MyBatisContextAnalyzer.ContextType) {
        try {
            val project = e.project ?: return
            val editor = e.getData(CommonDataKeys.EDITOR) ?: return
            val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return

            val sqlContent = extractSqlContent(context, editor, psiFile)
            if (sqlContent == null) {
                LOG.warn("zMyBatis: extractSqlContent returned null. Context: $context")
                return
            }

            val sourceFileKey = psiFile.virtualFile?.path ?: psiFile.name
            val statementKey = extractStatementKey(context, editor, psiFile, sourceFileKey)
            val cache = ConsoleCacheService.getInstance(project)

            // NEW_EACH policy: skip the cache and always open a fresh console
            val forceNew = ZMyBatisSettings.getInstance().consoleSessionPolicy == ConsoleSessionPolicy.NEW_EACH
            val cachedConsole = if (forceNew) null else cache.get(sourceFileKey)

            if (cachedConsole != null) {
                LOG.info("zMyBatis: reusing cached console for $sourceFileKey")
                proceedWithParamsAndExecute(e, project, sqlContent, context, cachedConsole, statementKey)
            } else {
                LOG.info("zMyBatis: no live console for $sourceFileKey, showing data-source chooser")
                ensureConsole(e, project, sourceFileKey) { console ->
                    // Only cache when policy is REUSE
                    if (!forceNew) cache.put(sourceFileKey, console)
                    proceedWithParamsAndExecute(e, project, sqlContent, context, console, statementKey)
                }
            }
        } catch (ex: Throwable) {
            LOG.error("zMyBatis runMyBatisQuery failed", ex)
            Messages.showErrorDialog(e.project, "Error preparing MyBatis query:\n${ex.message}", "zMyBatis Error")
        }
    }


    @Suppress("TooGenericExceptionCaught")
    private fun proceedWithParamsAndExecute(
        @Suppress("UNUSED_PARAMETER") e: AnActionEvent,
        project: com.intellij.openapi.project.Project,
        sqlContent: String,
        context: MyBatisContextAnalyzer.ContextType,
        console: JdbcConsole,
        statementKey: String? = null
    ) {
        val paramValues = resolveParameters(project, sqlContent, statementKey)
        if (paramValues == null) {
            LOG.info("zMyBatis: resolveParameters returned null (user cancelled or failed)")
            return
        }
        LOG.info("zMyBatis params: $paramValues")

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val rawSql = MyBatisEvaluator.evaluate(wrapForEvaluator(sqlContent, context), paramValues)
                LOG.info("zMyBatis SQL: $rawSql")

                val settings = ZMyBatisSettings.getInstance()

                // SqlFormatter.format() requires the EDT (PSI write action) — run it inside invokeLater
                ApplicationManager.getApplication().invokeLater {
                    val pureSql = if (settings.autoFormatSql) SqlFormatter.format(project, rawSql) else rawSql

                    if (settings.sqlPreview) {
                        // Show preview dialog on the EDT; execute only if user confirms
                        val dialog = SqlPreviewDialog(project, pureSql)
                        if (dialog.showAndGet()) {
                            executeOnConsole(console, project, pureSql)
                        } else {
                            LOG.info("zMyBatis: user cancelled from SQL preview dialog")
                        }
                    } else {
                        executeOnConsole(console, project, pureSql)
                    }
                }
            } catch (ex: Throwable) {
                LOG.error("zMyBatis evaluation failed", ex)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, "Error evaluating MyBatis SQL:\n${ex.message}", "zMyBatis Error")
                }
            }
        }
    }

    // ── Console acquisition ────────────────────────────────────────────────────────────────

    @Suppress("TooGenericExceptionCaught", "LongMethod")
    private fun ensureConsole(
        originalEvent: AnActionEvent,
        project: com.intellij.openapi.project.Project,
        fileKey: String,
        onConsoleReady: (JdbcConsole) -> Unit
    ) {
        if (!activeSelections.add(fileKey)) {
            LOG.info("zMyBatis: console selection already in progress for $fileKey")
            return
        }

        val dataSources = DbPsiFacade.getInstance(project).dataSources.toList()
        if (dataSources.isEmpty()) {
            activeSelections.remove(fileKey)
            Messages.showErrorDialog(project,
                "No data sources configured.\nPlease add a data source in the Database tool window first.",
                "zMyBatis: No Data Source")
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
                    activeSelections.remove(fileKey)
                    buildAndDeliverConsole(project, ds, null, fileKey, onConsoleReady)
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
                            activeSelections.remove(fileKey)
                            buildAndDeliverConsole(project, ds, schema, fileKey, onConsoleReady)
                        }
                    })
                }
            } else {
                dsGroup.add(object : AnAction("No schemas found") {
                    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
                    override fun update(e: AnActionEvent) { e.presentation.isEnabled = false }
                    override fun actionPerformed(e: AnActionEvent) { /* disabled */ }
                })
            }
            group.add(dsGroup)
        }

        ApplicationManager.getApplication().invokeLater({
            val popup = JBPopupFactory.getInstance().createActionGroupPopup(
                "Choose Data Source & Schema",
                group,
                originalEvent.dataContext,
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                true
            )
            popup.addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    if (!event.isOk) activeSelections.remove(fileKey)
                }
            })
            popup.showInBestPositionFor(originalEvent.dataContext)
        }, ModalityState.any())
    }

    @Suppress("TooGenericExceptionCaught")
    private fun buildAndDeliverConsole(
        project: com.intellij.openapi.project.Project,
        ds: DbDataSource,
        schema: DasNamespace?,
        fileKey: String,
        onConsoleReady: (JdbcConsole) -> Unit
    ) {
        try {
            // LightVirtualFile = in-memory only.
            // This is intentional: a real on-disk file causes VFS change events whenever
            // the document is written during execution, which triggers a project reload and
            // immediately disposes the console.  LightVirtualFile never hits the disk.
            val sqlFileType = com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
                .getFileTypeByExtension("sql")
            // Use the original mapper filename (e.g. "CustomerMapper.xml") as the console
            // title — that is what the user sees in the Services tab.
            val consoleName = fileKey.substringAfterLast('/').substringAfterLast('\\')
            val lightFile = com.intellij.testFramework.LightVirtualFile(consoleName, sqlFileType, "")

            val console = JdbcConsole.newConsole(project)
                .fromDataSource(ds)
                .forFile(lightFile)
                .build()
            LOG.info("zMyBatis: console created for ${ds.name} (name=$consoleName)")

            if (schema != null) switchSchemaOnConsole(console, schema)

            // Persist ds + schema name so we can silently re-create the console after restart.
            val schemaName = schema?.name ?: ""
            ConsoleCacheService.saveSession(fileKey, ds.name, schemaName)
            ConsoleCacheService.addToIndex(project, fileKey)
            LOG.info("zMyBatis: session saved for $fileKey (ds=${ds.name}, schema=$schemaName)")

            onConsoleReady(console)
        } catch (ex: Throwable) {
            LOG.error("zMyBatis: failed to create console for ${ds.name}", ex)
            Messages.showErrorDialog(project,
                "Could not create database console for ${ds.name}.\n${ex.message}",
                "zMyBatis Error")
        }
    }

    private fun switchSchemaOnConsole(console: JdbcConsole, schema: DasNamespace) {
        try {
            val kind = DasUtil.getKind(schema)
            val path = ObjectPath.create(schema.name, kind)
            console.switchSchema(SearchPath.of(path), false)
            LOG.info("zMyBatis: schema '${schema.name}' (kind=$kind) switched on console")
        } catch (ex: Throwable) {
            LOG.warn("zMyBatis: failed to switch schema '${schema.name}': ${ex.message}")
        }
    }

    // ── Execute on an existing console ───────────────────────────────────────────────────

    @Suppress("TooGenericExceptionCaught")
    private fun executeOnConsole(
        console: JdbcConsole,
        project: com.intellij.openapi.project.Project,
        pureSql: String
    ) {
        if (pureSql.isBlank()) {
            LOG.warn("zMyBatis: pureSql is blank, skipping execution")
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
                    val retryEditor = EditorFactory.getInstance().getEditors(consoleDoc, project)
                        .firstOrNull { it is EditorEx } as? EditorEx
                    if (retryEditor != null) {
                        performExecution(console, project, pureSql, retryEditor)
                    } else {
                        LOG.warn("zMyBatis: editor still null after opening for '${console.title}'")
                        Messages.showErrorDialog(project, "Cannot find editor for console '${console.title}'.", "zMyBatis Error")
                    }
                }, ModalityState.any())
            } else {
                LOG.warn("zMyBatis: console virtual file is null")
            }
        } else {
            performExecution(console, project, pureSql, existingEditor)
        }
    }

    private fun performExecution(
        console: JdbcConsole,
        project: com.intellij.openapi.project.Project,
        pureSql: String,
        consoleEditor: EditorEx
    ) {
        val consoleDoc = console.document
        val consolePsiFile = console.file
        val originalText = consoleDoc.text

        try {
            consoleEditor.contentComponent.requestFocusInWindow()

            WriteCommandAction.runWriteCommandAction(project, "zMyBatis: inject SQL", null, {
                consoleDoc.setText(pureSql)
                consoleEditor.selectionModel.setSelection(0, pureSql.length)
                consoleEditor.caretModel.moveToOffset(0)
                PsiDocumentManager.getInstance(project).commitDocument(consoleDoc)
            })

            val fullRange = TextRange(0, consoleDoc.textLength)
            val info = JdbcConsoleProvider.findScriptModelNoInject(
                project, consolePsiFile, consoleEditor,
                fullRange, DatabaseSettings.getDefaultExecOption()
            )
            if (info == null) {
                LOG.warn("zMyBatis: findScriptModelNoInject returned null (SQL length=${pureSql.length})")
                WriteCommandAction.runWriteCommandAction(project) {
                    consoleDoc.setText(originalText)
                    PsiDocumentManager.getInstance(project).commitDocument(consoleDoc)
                }
                Messages.showErrorDialog(project, "Failed to parse SQL for execution.", "zMyBatis Error")
                return
            }
            LOG.info("zMyBatis: executing on console '${console.title}'")
            JdbcConsoleProvider.doRunQueryInConsole(console, info)

            if (ZMyBatisSettings.getInstance().copyToClipboard) {
                CopyPasteManager.getInstance().setContents(StringSelection(pureSql))
            }


        } catch (ex: Throwable) {
            LOG.error("zMyBatis: execution failed", ex)
            try {
                WriteCommandAction.runWriteCommandAction(project) {
                    consoleDoc.setText(originalText)
                    PsiDocumentManager.getInstance(project).commitDocument(consoleDoc)
                }
            } catch (restoreEx: Throwable) {
                LOG.warn("zMyBatis: failed to restore console document: ${restoreEx.message}")
            }
            Messages.showErrorDialog(project,
                "Failed to execute SQL:\n${ex.message ?: ex.javaClass.simpleName}",
                "zMyBatis: Execution Error")
        }
    }

    @Suppress("ReturnCount")
    private fun extractSqlContent(
        context: MyBatisContextAnalyzer.ContextType,
        editor: Editor,
        psiFile: PsiFile
    ): String? {
        // Always extract the full MyBatis statement regardless of any text selection.
        // Using selectedText here would return only the dragged portion, which is an
        // incomplete (and invalid) dynamic SQL fragment.

        // Use the selection start offset when text is selected so we locate the PSI
        // element that the user is pointing at (within the selection).
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
        LOG.info("zMyBatis extractResult — params: ${extracted.params}, objectParams: ${extracted.objectParams}")
        if (extracted.params.isEmpty()) return emptyMap()
        val dialog = ParameterInputDialog(project, extracted.params, extracted.objectParams, statementKey)
        if (!dialog.showAndGet()) return null
        val values = dialog.getValues()
        LOG.info("zMyBatis getValues — keys: ${values.keys}, values: $values")
        LOG.info("zMyBatis getValues — types: ${values.mapValues { (_, v) -> v?.javaClass?.simpleName ?: "null" }}")
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

    /**
     * Builds a stable key that identifies the specific Mapper statement the user invoked.
     * Format:
     *  - XML mapper    : "{fileKey}::{id attribute of the statement tag}"
     *  - Annotation    : "{fileKey}::{ClassName}#{methodName}"
     * Falls back to [fileKey] alone if the statement ID cannot be determined.
     */
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


