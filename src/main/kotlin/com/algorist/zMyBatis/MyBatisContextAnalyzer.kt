package com.algorist.zMyBatis

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
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

    fun analyze(e: AnActionEvent): ContextType {
        val project = e.project
        val editor: Editor = e.getData(CommonDataKeys.EDITOR) ?: return ContextType.NONE
        val psiFile: PsiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return ContextType.NONE
        if (project == null) return ContextType.NONE

        val element = psiFile.findElementAt(editor.caretModel.offset) ?: return ContextType.NONE

        // 1. Check XML
        if (psiFile is XmlFile && isInMyBatisStatementTag(element)) {
            return ContextType.XML
        }

        // 2. Check Java (use safe hasAnnotation)
        if (psiFile is PsiJavaFile) {
            val method: PsiMethod =
                PsiTreeUtil.getParentOfType(element, PsiMethod::class.java) ?: return ContextType.NONE

            if (methodHasAnyAnnotation(method, PROVIDER_ANNOTATIONS)) return ContextType.PROVIDER
            if (methodHasAnyAnnotation(method, STATEMENT_ANNOTATIONS)) return ContextType.ANNOTATION
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