@file:Suppress("DialogTitleCapitalization", "WrongInvocationKind", "ActionCallSuperActions", "UnstableApiUsage")

package com.algorist.zMyBatis

import com.algorist.zMyBatis.MyBatisContextAnalyzer.analyze
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag

@Suppress("TooManyFunctions")
class MyBatisExecuteProxyAction_bak(private val originalAction: AnAction) : AnAction() {

    companion object {
        private val LOG = Logger.getInstance(MyBatisExecuteProxyAction_bak::class.java)
    }

    init {
        templatePresentation.icon = originalAction.templatePresentation.icon
        templatePresentation.text = originalAction.templatePresentation.text
        templatePresentation.description = originalAction.templatePresentation.description
    }

    override fun setShortcutSet(shortcutSet: ShortcutSet) {
        // Intentionally left blank
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        originalAction.update(e)
        if (analyze(e) != MyBatisContextAnalyzer.ContextType.NONE) {
            e.presentation.isEnabledAndVisible = true
        }
    }

    @Suppress("ReturnCount")
    override fun actionPerformed(e: AnActionEvent) {
        when (val context = analyze(e)) {
            MyBatisContextAnalyzer.ContextType.NONE -> {
                @Suppress("CallToAction")
                originalAction.actionPerformed(e)
            }
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
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return

        val sqlContent = extractSqlContent(context, editor, psiFile) ?: return
        val paramValues = resolveParameters(project, sqlContent) ?: return
        LOG.info("zMyBatis params: $paramValues")

        val pureSql = MyBatisEvaluator.evaluate(wrapForEvaluator(sqlContent, context), paramValues)
        LOG.info("zMyBatis SQL: $pureSql")

        executeInDataGrip(e, project, pureSql)
    }

    @Suppress("ReturnCount")
    private fun extractSqlContent(
        context: MyBatisContextAnalyzer.ContextType,
        editor: Editor,
        psiFile: PsiFile
    ): String? {
        editor.selectionModel.selectedText?.takeIf { it.isNotBlank() }?.let { return it }

        var offset = editor.caretModel.offset
        if (offset > 0 && offset == psiFile.textLength) offset--
        var element = psiFile.findElementAt(offset)
        if (element is PsiWhiteSpace && offset > 0)
            element = psiFile.findElementAt(offset - 1)
        element ?: return null

        return when (context) {
            MyBatisContextAnalyzer.ContextType.XML ->
                findMyBatisStatementTag(element)?.text
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
        project: Project,
        sqlContent: String
    ): Map<String, Any?>? {
        val extracted = ParameterExtractor.extractResult(sqlContent)
        LOG.info("zMyBatis extractResult — params: ${extracted.params}, objectParams: ${extracted.objectParams}")
        if (extracted.params.isEmpty()) return emptyMap()
        val dialog = ParameterInputDialog(project, extracted.params, extracted.objectParams)
        if (!dialog.showAndGet()) return null
        val values = dialog.getValues()
        LOG.info("zMyBatis getValues — keys: ${values.keys}, values: $values")
        return values
    }

    private fun findMyBatisStatementTag(element: PsiElement): XmlTag? {
        var tag: XmlTag? = PsiTreeUtil.getParentOfType(element, XmlTag::class.java)
        while (tag != null) {
            if (tag.name.lowercase() in MyBatisContextAnalyzer.MYBATIS_STATEMENT_TAGS) return tag
            tag = tag.parentTag
        }
        return null
    }

    private fun wrapForEvaluator(sql: String, context: MyBatisContextAnalyzer.ContextType): String =
        if (context == MyBatisContextAnalyzer.ContextType.ANNOTATION && !sql.trim().startsWith("<script>")) {
            "<![CDATA[$sql]]>"
        } else {
            sql
        }

    /**
     * 현재 XML 에디터의 document 내용을 SQL로 임시 교체하고
     * originalAction(Console.Jdbc.Execute)을 실행한 뒤 원래 내용으로 복원한다.
     * 에디터 탭이 새로 열리지 않는다.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private fun executeInDataGrip(
        e: AnActionEvent,
        project: Project,
        pureSql: String
    ) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: run {
            Messages.showErrorDialog(project, "에디터를 찾지 못했습니다.", "zMyBatis Error")
            return
        }
        val document = editor.document
        val originalText = document.text

        try {
            LOG.info("zMyBatis: originalAction class = ${originalAction.javaClass.name}")

            // 1. document 내용을 SQL로 교체 (undo 히스토리에 남기지 않음)
            WriteAction.runAndWait<Throwable> {
                CommandProcessor.getInstance().runUndoTransparentAction {
                    document.setText(pureSql)
                }
            }

            // 2. 전체 선택
            editor.selectionModel.setSelection(0, document.textLength)

            // 3. 현재 에디터 컨텍스트 그대로 originalAction 실행
            val newEvent = AnActionEvent.createEvent(
                e.dataContext,
                originalAction.templatePresentation.clone(),
                e.place,
                ActionUiKind.NONE,
                null
            )
            newEvent.presentation.isEnabled = true
            newEvent.presentation.isVisible = true

            LOG.info("zMyBatis: firing originalAction on XML editor context")
            @Suppress("CallToAction")
            originalAction.actionPerformed(newEvent)

        } catch (ex: Throwable) {
            LOG.error("zMyBatis: executeInDataGrip failed", ex)
            Messages.showErrorDialog(project, "SQL 실행 중 오류:\n${ex.message}", "zMyBatis Error")
        } finally {
            // 4. document 원래 내용으로 복원
            WriteAction.runAndWait<Throwable> {
                CommandProcessor.getInstance().runUndoTransparentAction {
                    document.setText(originalText)
                }
            }
            editor.selectionModel.removeSelection()
        }
    }
}
