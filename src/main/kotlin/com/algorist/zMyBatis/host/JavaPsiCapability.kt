package com.algorist.zMyBatis.host

import com.algorist.zMyBatis.core.source.XmlStatementId
import com.algorist.zMyBatis.source.JavaAnnotationSourceCaptureResult
import com.algorist.zMyBatis.source.XmlMapperMethodCaptureResult
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Optional Java-language boundary for hosts that ship the IntelliJ Java plugin.
 *
 * Signatures intentionally expose only IntelliJ Platform PSI plus zMyBatis immutable result types.
 * DataGrip can therefore load and execute the XML/Database Tools path without resolving Java PSI.
 */
internal interface JavaPsiCapability {
    fun classifyContext(
        project: Project,
        psiFile: PsiFile,
        element: PsiElement,
    ): Context

    fun captureAnnotationSource(
        project: Project,
        editor: Editor,
        psiFile: PsiFile,
    ): JavaAnnotationSourceCaptureResult?

    fun captureXmlMapperMethod(
        project: Project,
        statementId: XmlStatementId,
    ): XmlMapperMethodCaptureResult

    enum class Context {
        ANNOTATION,
        PROVIDER,
        NONE,
    }

    companion object {
        val EP_NAME: ExtensionPointName<JavaPsiCapability> =
            ExtensionPointName.create("com.algorist.zMyBatis.javaPsiCapability")
    }
}

internal object JavaPsiCapabilities {
    fun current(): JavaPsiCapability? = JavaPsiCapability.EP_NAME.extensionList.singleOrNull()
}
