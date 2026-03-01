// @file:Suppress(
//     "DialogTitleCapitalization",
//     "WrongInvocationKind",
//     "ActionCallSuperActions",
//     "UnstableApiUsage",
//     "CallToAction"
// )
//
// package com.algorist.zMyBatis
//
// import com.algorist.zMyBatis.MyBatisContextAnalyzer.analyze
// import com.intellij.ide.scratch.ScratchFileService
// import com.intellij.ide.scratch.ScratchRootType
// import com.intellij.openapi.actionSystem.*
// import com.intellij.openapi.actionSystem.impl.SimpleDataContext
// import com.intellij.openapi.application.ApplicationManager
// import com.intellij.openapi.diagnostic.Logger
// import com.intellij.openapi.fileEditor.FileEditorManager
// import com.intellij.openapi.fileEditor.FileEditorManagerListener
// import com.intellij.openapi.project.Project
// import com.intellij.openapi.ui.Messages
// import com.intellij.openapi.vfs.VirtualFile
// import com.intellij.psi.PsiManager
// import com.intellij.psi.PsiMethod
// import com.intellij.psi.util.PsiTreeUtil
// import com.intellij.psi.xml.XmlTag
// import com.intellij.sql.psi.SqlLanguage
//
// @Suppress("UnstableApiUsage", "TooManyFunctions")
// class MyBatisExecuteProxyAction(private val originalAction: AnAction) : AnAction() {
//
//     companion object {
//         private val LOG = Logger.getInstance(MyBatisExecuteProxyAction::class.java)
//     }
//
//     init {
//         templatePresentation.icon = originalAction.templatePresentation.icon
//         templatePresentation.text = originalAction.templatePresentation.text
//         templatePresentation.description = originalAction.templatePresentation.description
//     }
//
//     override fun setShortcutSet(shortcutSet: ShortcutSet) {
//         // Intentionally left blank — prevents IntelliJ from reassigning shortcuts
//     }
//
//     override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
//
//     override fun update(e: AnActionEvent) {
//         originalAction.update(e)
//         // DataGrip disables the run button in Java files; force-enable it for MyBatis contexts
//         if (analyze(e) != MyBatisContextAnalyzer.ContextType.NONE) {
//             e.presentation.isEnabledAndVisible = true
//         }
//     }
//
//     @Suppress("ReturnCount")
//     override fun actionPerformed(e: AnActionEvent) {
//         when (val context = analyze(e)) {
//             MyBatisContextAnalyzer.ContextType.NONE -> {
//                 originalAction.actionPerformed(e)
//             }
//
//             MyBatisContextAnalyzer.ContextType.PROVIDER -> {
//                 Messages.showInfoMessage(
//                     e.project,
//                     "@SelectProvider / @InsertProvider / @UpdateProvider / @DeleteProvider\n" +
//                             "generate SQL dynamically at runtime.\n\n" +
//                             "zMyBatis cannot statically extract the SQL from a Provider class.\n" +
//                             "Please run the query directly from the generated SQL or a mapper XML.",
//                     "zMyBatis: Provider Not Supported"
//                 )
//             }
//
//             else -> runMyBatisQuery(e, context)
//         }
//     }
//
//     @Suppress("ReturnCount")
//     private fun runMyBatisQuery(e: AnActionEvent, context: MyBatisContextAnalyzer.ContextType) {
//         try {
//             val project = e.project ?: return
//             val editor = e.getData(CommonDataKeys.EDITOR) ?: return
//             val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
//
//             val sqlContent = extractSqlContent(context, editor, psiFile)
//             if (sqlContent == null) {
//                 LOG.warn("zMyBatis: extractSqlContent returned null. Context: $context")
//                 return
//             }
//
//             val paramValues = resolveParameters(project, sqlContent)
//             if (paramValues == null) {
//                 LOG.info("zMyBatis: User cancelled parameter input.")
//                 return
//             }
//
//             val pureSql = MyBatisEvaluator.evaluate(wrapForEvaluator(sqlContent, context), paramValues)
//             LOG.info("zMyBatis SQL: $pureSql")
//
//             executeInScratchFile(project, pureSql)
//
//         } catch (ex: Throwable) {
//             LOG.error("zMyBatis runMyBatisQuery failed", ex)
//             Messages.showErrorDialog(e.project, "Error preparing MyBatis query:\n${ex.message}", "zMyBatis Error")
//         }
//     }
//
//     private fun executeInScratchFile(project: Project, pureSql: String) {
//         val scratchFile = ScratchRootType.getInstance().createScratchFile(
//             project,
//             "zMyBatis.sql",
//             SqlLanguage.INSTANCE,
//             pureSql,
//             ScratchFileService.Option.create_new_always
//         )
//
//         if (scratchFile != null) {
//             // To invoke the "execute" action, we need the context of the new editor.
//             // We listen for the file to be opened, then trigger the action.
//             val connection = project.messageBus.connect()
//             connection.subscribe(
//                 FileEditorManagerListener.FILE_EDITOR_MANAGER,
//                 object : FileEditorManagerListener {
//                     override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
//                         if (file == scratchFile) {
//                             // Once the correct file is opened, trigger the execution and disconnect the listener.
//                             ApplicationManager.getApplication().invokeLater {
//                                 triggerExecuteInNewEditor(source, file)
//                             }
//                             connection.disconnect()
//                         }
//                     }
//                 }
//             )
//             // This opens the file and triggers the listener above.
//             FileEditorManager.getInstance(project).openFile(scratchFile, true)
//         } else {
//             Messages.showErrorDialog(project, "Failed to create scratch file.", "zMyBatis Error")
//         }
//     }
//
//     private fun triggerExecuteInNewEditor(manager: FileEditorManager, file: VirtualFile) {
//         val project = manager.project
//         // Using `getSelectedTextEditor` is a more reliable way to get the editor for a text file.
//         val editor = manager.selectedTextEditor
//         val psiFile = PsiManager.getInstance(project).findFile(file)
//         val executeAction = ActionManager.getInstance().getAction("Console.Jdbc.Execute")
//
//         if (editor == null || psiFile == null || executeAction == null) {
//             LOG.warn("zMyBatis: Could not get context for new scratch file to trigger execution.")
//             return
//         }
//
//         // Build a new context for the scratch file editor
//         val dataContext = SimpleDataContext.builder()
//             .add(CommonDataKeys.PROJECT, project)
//             .add(CommonDataKeys.VIRTUAL_FILE, file)
//             .add(CommonDataKeys.PSI_FILE, psiFile)
//             .add(CommonDataKeys.EDITOR, editor)
//             .build()
//
//         val event = AnActionEvent.createFromDataContext(ActionPlaces.EDITOR_TOOLBAR, null, dataContext)
//         LOG.info("zMyBatis: Programmatically triggering 'Console.Jdbc.Execute' on new scratch file.")
//         executeAction.actionPerformed(event)
//     }
//
//
//     @Suppress("ReturnCount")
//     private fun extractSqlContent(
//         context: MyBatisContextAnalyzer.ContextType,
//         editor: com.intellij.openapi.editor.Editor,
//         psiFile: com.intellij.psi.PsiFile
//     ): String? {
//         editor.selectionModel.selectedText
//             ?.takeIf { it.isNotBlank() }
//             ?.let { return it }
//
//         var offset = editor.caretModel.offset
//         if (offset > 0 && offset == psiFile.textLength) {
//             offset--
//         }
//
//         var element = psiFile.findElementAt(offset)
//         if (element is com.intellij.psi.PsiWhiteSpace && offset > 0) {
//             element = psiFile.findElementAt(offset - 1)
//         }
//
//         if (element == null) return null
//
//         return when (context) {
//             MyBatisContextAnalyzer.ContextType.XML ->
//                 findMyBatisStatementTag(element)?.text
//
//             MyBatisContextAnalyzer.ContextType.ANNOTATION -> {
//                 val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
//                 val annotation = method?.annotations?.firstOrNull {
//                     it.qualifiedName in MyBatisContextAnalyzer.STATEMENT_ANNOTATIONS
//                 }
//                 AnnotationSqlExtractor.extract(annotation)
//             }
//
//             else -> null
//         }
//     }
//
//     @Suppress("ReturnCount")
//     private fun resolveParameters(
//         project: Project,
//         sqlContent: String
//     ): Map<String, Any?>? {
//         val extracted = ParameterExtractor.extractResult(sqlContent)
//         LOG.info("zMyBatis extractResult — params: ${extracted.params}, objectParams: ${extracted.objectParams}")
//         if (extracted.params.isEmpty()) return emptyMap()
//         val dialog = ParameterInputDialog(project, extracted.params, extracted.objectParams)
//         if (!dialog.showAndGet()) return null
//         val values = dialog.getValues()
//         LOG.info("zMyBatis getValues — keys: ${values.keys}, values: $values")
//         return values
//     }
//
//     private fun findMyBatisStatementTag(element: com.intellij.psi.PsiElement): XmlTag? {
//         var tag: XmlTag? = PsiTreeUtil.getParentOfType(element, XmlTag::class.java, false)
//         while (tag != null) {
//             if (tag.name.lowercase() in MyBatisContextAnalyzer.MYBATIS_STATEMENT_TAGS) return tag
//             tag = tag.parentTag
//         }
//         return null
//     }
//
//     private fun wrapForEvaluator(sql: String, context: MyBatisContextAnalyzer.ContextType): String =
//         if (context == MyBatisContextAnalyzer.ContextType.ANNOTATION && !sql.trim().startsWith("<script>")) {
//             "<![CDATA[$sql]]>"
//         } else {
//             sql
//         }
// }
