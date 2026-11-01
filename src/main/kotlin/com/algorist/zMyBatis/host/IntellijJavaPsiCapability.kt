package com.algorist.zMyBatis.host

import com.algorist.zMyBatis.core.source.XmlStatementId
import com.algorist.zMyBatis.source.JavaAnnotationSourceCaptureAdapter
import com.algorist.zMyBatis.source.JavaAnnotationSourceCaptureResult
import com.algorist.zMyBatis.source.XmlMapperMethodCaptureAdapter
import com.algorist.zMyBatis.source.XmlMapperMethodCaptureResult
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil

/** Java-plugin implementation loaded only through the optional com.intellij.java descriptor. */
internal class IntellijJavaPsiCapability : JavaPsiCapability {
    override fun classifyContext(
        project: Project,
        psiFile: PsiFile,
        element: PsiElement,
    ): JavaPsiCapability.Context {
        if (psiFile !is PsiJavaFile) return JavaPsiCapability.Context.NONE
        if (DumbService.isDumb(project)) return JavaPsiCapability.Context.NONE

        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
            ?: return JavaPsiCapability.Context.NONE

        return try {
            when {
                providerAnnotations.any(method::hasAnnotation) -> JavaPsiCapability.Context.PROVIDER
                statementAnnotations.any(method::hasAnnotation) -> JavaPsiCapability.Context.ANNOTATION
                else -> JavaPsiCapability.Context.NONE
            }
        } catch (_: IndexNotReadyException) {
            JavaPsiCapability.Context.NONE
        }
    }

    override fun captureAnnotationSource(
        project: Project,
        editor: Editor,
        psiFile: PsiFile,
    ): JavaAnnotationSourceCaptureResult? {
        if (psiFile !is PsiJavaFile) return null
        return JavaAnnotationSourceCaptureAdapter.capture(project, editor)
    }

    override fun captureXmlMapperMethod(
        project: Project,
        statementId: XmlStatementId,
    ): XmlMapperMethodCaptureResult =
        XmlMapperMethodCaptureAdapter.capture(project, statementId)

    private val providerAnnotations = setOf(
        "org.apache.ibatis.annotations.SelectProvider",
        "org.apache.ibatis.annotations.UpdateProvider",
        "org.apache.ibatis.annotations.InsertProvider",
        "org.apache.ibatis.annotations.DeleteProvider",
    )

    private val statementAnnotations = setOf(
        "org.apache.ibatis.annotations.Select",
        "org.apache.ibatis.annotations.Update",
        "org.apache.ibatis.annotations.Insert",
        "org.apache.ibatis.annotations.Delete",
    )
}
