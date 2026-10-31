package com.algorist.zMyBatis

import com.algorist.zMyBatis.host.JavaPsiCapabilities
import com.algorist.zMyBatis.host.JavaPsiCapability
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

object MyBatisContextAnalyzer {

    val MYBATIS_STATEMENT_TAGS = setOf("select", "insert", "update", "delete")

    /**
     * Classifies only the current local editor/PSI context.
     *
     * This method is intentionally safe to use from action update(): it performs no mapper SQL
     * extraction, parameter analysis, datasource enumeration, persistence, or console work.
     * XML statement-tag classification remains local PSI only. Java annotation classification is
     * delegated to the optional Java capability and is absent on hosts such as DataGrip.
     */
    fun analyze(e: AnActionEvent): ContextType {
        val project = e.project ?: return ContextType.NONE
        if (project.isDisposed) return ContextType.NONE

        val editor: Editor = e.getData(CommonDataKeys.EDITOR) ?: return ContextType.NONE
        if (editor.isDisposed) return ContextType.NONE

        val psiFile: PsiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return ContextType.NONE
        if (!psiFile.isValid || psiFile.textLength == 0) return ContextType.NONE

        val offset = editor.caretModel.offset.coerceIn(0, psiFile.textLength - 1)
        val element = psiFile.findElementAt(offset) ?: return ContextType.NONE

        if (psiFile is XmlFile && isInMyBatisStatementTag(element)) {
            return ContextType.XML
        }

        return when (
            JavaPsiCapabilities.current()?.classifyContext(project, psiFile, element)
                ?: JavaPsiCapability.Context.NONE
        ) {
            JavaPsiCapability.Context.ANNOTATION -> ContextType.ANNOTATION
            JavaPsiCapability.Context.PROVIDER -> ContextType.PROVIDER
            JavaPsiCapability.Context.NONE -> ContextType.NONE
        }
    }

    private fun isInMyBatisStatementTag(element: com.intellij.psi.PsiElement): Boolean {
        var tag: XmlTag? = PsiTreeUtil.getParentOfType(element, XmlTag::class.java)
        while (tag != null) {
            if (tag.name.lowercase() in MYBATIS_STATEMENT_TAGS) {
                val mapper = tag.parentTag
                return mapper?.name?.lowercase() == "mapper" &&
                    mapper.parentTag == null &&
                    !mapper.getAttributeValue("namespace").isNullOrBlank() &&
                    !tag.getAttributeValue("id").isNullOrBlank()
            }
            tag = tag.parentTag
        }
        return false
    }

    enum class ContextType {
        XML, ANNOTATION, PROVIDER, NONE
    }
}
