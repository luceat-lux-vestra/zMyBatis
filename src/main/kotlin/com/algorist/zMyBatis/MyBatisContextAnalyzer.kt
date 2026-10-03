package com.algorist.zMyBatis

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

object MyBatisContextAnalyzer {

    val MYBATIS_STATEMENT_TAGS = setOf("select", "insert", "update", "delete")

    val PROVIDER_ANNOTATIONS = setOf(
        "org.apache.ibatis.annotations.SelectProvider",
        "org.apache.ibatis.annotations.UpdateProvider",
        "org.apache.ibatis.annotations.InsertProvider",
        "org.apache.ibatis.annotations.DeleteProvider",
    )

    val STATEMENT_ANNOTATIONS = setOf(
        "org.apache.ibatis.annotations.Select",
        "org.apache.ibatis.annotations.Update",
        "org.apache.ibatis.annotations.Insert",
        "org.apache.ibatis.annotations.Delete",
    )

    /**
     * Classifies only the current local editor/PSI context.
     *
     * This method is intentionally safe to use from action update(): it performs no mapper SQL
     * extraction, parameter analysis, datasource enumeration, persistence, or console work.
     * Java annotation resolution is refused while indexes are unavailable; XML statement-tag
     * classification remains local PSI only.
     */
    fun analyze(e: AnActionEvent): ContextType {
        val project = e.project ?: return ContextType.NONE
        if (project.isDisposed) return ContextType.NONE

        val editor: Editor = e.getData(CommonDataKeys.EDITOR) ?: return ContextType.NONE
        val psiFile: PsiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return ContextType.NONE
        if (!psiFile.isValid || psiFile.textLength == 0) return ContextType.NONE

        val offset = editor.caretModel.offset.coerceIn(0, psiFile.textLength - 1)
        val element = psiFile.findElementAt(offset) ?: return ContextType.NONE

        if (psiFile is XmlFile && isInMyBatisStatementTag(element)) {
            return ContextType.XML
        }

        if (psiFile is PsiJavaFile) {
            if (DumbService.isDumb(project)) return ContextType.NONE

            val method: PsiMethod =
                PsiTreeUtil.getParentOfType(element, PsiMethod::class.java) ?: return ContextType.NONE

            return try {
                when {
                    methodHasAnyAnnotation(method, PROVIDER_ANNOTATIONS) -> ContextType.PROVIDER
                    methodHasAnyAnnotation(method, STATEMENT_ANNOTATIONS) -> ContextType.ANNOTATION
                    else -> ContextType.NONE
                }
            } catch (_: IndexNotReadyException) {
                ContextType.NONE
            }
        }

        return ContextType.NONE
    }

    private fun isInMyBatisStatementTag(element: com.intellij.psi.PsiElement): Boolean {
        var tag: XmlTag? = PsiTreeUtil.getParentOfType(element, XmlTag::class.java)
        while (tag != null) {
            if (tag.name.lowercase() in MYBATIS_STATEMENT_TAGS) return true
            tag = tag.parentTag
        }
        return false
    }

    private fun methodHasAnyAnnotation(method: PsiMethod, annotationFqns: Set<String>): Boolean =
        annotationFqns.any(method::hasAnnotation)

    enum class ContextType {
        XML, ANNOTATION, PROVIDER, NONE
    }
}
