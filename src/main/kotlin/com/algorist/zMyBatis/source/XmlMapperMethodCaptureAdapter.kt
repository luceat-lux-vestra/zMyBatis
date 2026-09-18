package com.algorist.zMyBatis.source

import com.algorist.zMyBatis.core.source.JavaMethodParameterMetadata
import com.algorist.zMyBatis.core.source.JavaTypeIdentity
import com.algorist.zMyBatis.core.source.XmlMapperMethodCapture
import com.algorist.zMyBatis.core.source.XmlStatementId
import com.algorist.zMyBatis.core.source.SourceRange
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import com.intellij.psi.PsiWildcardType
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.GlobalSearchScope

sealed interface XmlMapperMethodCaptureResult {
    data class Captured(val capture: XmlMapperMethodCapture) : XmlMapperMethodCaptureResult

    data class Failed(val failure: XmlMapperMethodCaptureFailure) : XmlMapperMethodCaptureResult
}

enum class XmlMapperMethodCaptureFailure {
    UNRESOLVED_MAPPER_TYPE,
    AMBIGUOUS_MAPPER_TYPE,
    UNSUPPORTED_MAPPER_TYPE,
    MISSING_METHOD,
    AMBIGUOUS_METHOD,
    UNSUPPORTED_METHOD_FORM,
    UNSUPPORTED_SPECIAL_PARAMETER,
    UNRESOLVED_PARAMETER_TYPE,
    UNRESOLVED_PARAM_ALIAS,
    MAPPER_SOURCE_UNAVAILABLE,
    MAPPER_SOURCE_TOO_LARGE,
    SOURCE_CHANGED_DURING_CAPTURE,
    SOURCE_PSI_MISMATCH,
}

/**
 * Associates one canonical XML statement with one exact Java mapper method.
 *
 * PSI is used only inside this host adapter. The result contains immutable core/JDK source evidence
 * and declaration-ordered parameter metadata; no caller-input alias semantics are inferred here.
 */
object XmlMapperMethodCaptureAdapter {
    private const val DEFAULT_MAX_SOURCE_LENGTH = 2 * 1024 * 1024
    private const val PARAM_ANNOTATION = "org.apache.ibatis.annotations.Param"
    private val specialParameterTypes = setOf(
        "org.apache.ibatis.session.RowBounds",
        "org.apache.ibatis.session.ResultHandler",
    )

    fun capture(
        project: Project,
        statementId: XmlStatementId,
        maxSourceLength: Int = DEFAULT_MAX_SOURCE_LENGTH,
    ): XmlMapperMethodCaptureResult {
        require(maxSourceLength in 1 until Int.MAX_VALUE) {
            "max source length must be positive and leave room for the overflow probe"
        }

        val initialClass = when (
            val resolution = resolveMapperClass(project, statementId.namespace)
        ) {
            is MapperClassResolution.Resolved -> resolution.mapperClass
            is MapperClassResolution.Failed -> return failed(resolution.failure)
        }
        if (!initialClass.isInterface) {
            return failed(XmlMapperMethodCaptureFailure.UNSUPPORTED_MAPPER_TYPE)
        }

        val initialFile = initialClass.containingFile as? PsiJavaFile
            ?: return failed(XmlMapperMethodCaptureFailure.MAPPER_SOURCE_UNAVAILABLE)
        val virtualFile = initialFile.virtualFile
            ?: return failed(XmlMapperMethodCaptureFailure.MAPPER_SOURCE_UNAVAILABLE)

        val beforeSynchronization = when (
            val result = DependentMapperSourceSnapshotAdapter.capture(virtualFile, maxSourceLength)
        ) {
            is DependentMapperSourceCaptureResult.Captured -> result.snapshot
            is DependentMapperSourceCaptureResult.ContentTooLarge -> {
                return failed(XmlMapperMethodCaptureFailure.MAPPER_SOURCE_TOO_LARGE)
            }
            DependentMapperSourceCaptureResult.SourceChangedDuringCapture -> {
                return failed(XmlMapperMethodCaptureFailure.SOURCE_CHANGED_DURING_CAPTURE)
            }
            DependentMapperSourceCaptureResult.InvalidSource,
            DependentMapperSourceCaptureResult.UnreadableSource,
            -> return failed(XmlMapperMethodCaptureFailure.MAPPER_SOURCE_UNAVAILABLE)
        }

        synchronizeCachedDocument(project, virtualFile)

        val afterSynchronization = when (
            val result = DependentMapperSourceSnapshotAdapter.capture(virtualFile, maxSourceLength)
        ) {
            is DependentMapperSourceCaptureResult.Captured -> result.snapshot
            is DependentMapperSourceCaptureResult.ContentTooLarge -> {
                return failed(XmlMapperMethodCaptureFailure.MAPPER_SOURCE_TOO_LARGE)
            }
            DependentMapperSourceCaptureResult.SourceChangedDuringCapture -> {
                return failed(XmlMapperMethodCaptureFailure.SOURCE_CHANGED_DURING_CAPTURE)
            }
            DependentMapperSourceCaptureResult.InvalidSource,
            DependentMapperSourceCaptureResult.UnreadableSource,
            -> return failed(XmlMapperMethodCaptureFailure.MAPPER_SOURCE_UNAVAILABLE)
        }
        if (beforeSynchronization != afterSynchronization) {
            return failed(XmlMapperMethodCaptureFailure.SOURCE_CHANGED_DURING_CAPTURE)
        }

        val mapperClass = when (
            val resolution = resolveMapperClass(project, statementId.namespace)
        ) {
            is MapperClassResolution.Resolved -> resolution.mapperClass
            is MapperClassResolution.Failed -> return failed(resolution.failure)
        }
        if (!mapperClass.isInterface) {
            return failed(XmlMapperMethodCaptureFailure.UNSUPPORTED_MAPPER_TYPE)
        }
        val mapperFile = mapperClass.containingFile as? PsiJavaFile
            ?: return failed(XmlMapperMethodCaptureFailure.MAPPER_SOURCE_UNAVAILABLE)
        if (
            mapperFile.virtualFile != virtualFile ||
            mapperFile.text != afterSynchronization.content
        ) {
            return failed(XmlMapperMethodCaptureFailure.SOURCE_PSI_MISMATCH)
        }

        val method = when (
            val resolution = resolveMethod(mapperClass, statementId.statementId)
        ) {
            is MethodResolution.Resolved -> resolution.method
            is MethodResolution.Failed -> return failed(resolution.failure)
        }
        if (
            method.hasModifierProperty(PsiModifier.STATIC) ||
            method.hasModifierProperty(PsiModifier.DEFAULT) ||
            method.hasModifierProperty(PsiModifier.PRIVATE)
        ) {
            return failed(XmlMapperMethodCaptureFailure.UNSUPPORTED_METHOD_FORM)
        }

        val methodRange = method.textRange
        if (
            methodRange.startOffset < 0 ||
            methodRange.endOffset > afterSynchronization.content.length ||
            afterSynchronization.content.substring(methodRange.startOffset, methodRange.endOffset) != method.text
        ) {
            return failed(XmlMapperMethodCaptureFailure.SOURCE_PSI_MISMATCH)
        }

        val parameters = mutableListOf<JavaMethodParameterMetadata>()
        method.parameterList.parameters.forEachIndexed { index, parameter ->
            if (isSpecialParameter(parameter.type)) {
                return failed(XmlMapperMethodCaptureFailure.UNSUPPORTED_SPECIAL_PARAMETER)
            }
            val typeIdentity = parameterTypeIdentity(parameter.type)
                ?: return failed(XmlMapperMethodCaptureFailure.UNRESOLVED_PARAMETER_TYPE)
            val paramAlias = parameter.getAnnotation(PARAM_ANNOTATION)?.let { annotation ->
                val value = annotation.findAttributeValue("value") as? PsiLiteralExpression
                    ?: return failed(XmlMapperMethodCaptureFailure.UNRESOLVED_PARAM_ALIAS)
                (value.value as? String)
                    ?.takeIf(String::isNotBlank)
                    ?: return failed(XmlMapperMethodCaptureFailure.UNRESOLVED_PARAM_ALIAS)
            }
            parameters += JavaMethodParameterMetadata(
                index = index,
                sourceName = parameter.name.takeIf(String::isNotBlank),
                typeIdentity = typeIdentity,
                myBatisParamAlias = paramAlias,
            )
        }

        return XmlMapperMethodCaptureResult.Captured(
            XmlMapperMethodCapture(
                statementId = statementId,
                mapperSource = afterSynchronization,
                methodSourceRange = SourceRange(methodRange.startOffset, methodRange.endOffset),
                parameters = parameters,
            ),
        )
    }

    private fun synchronizeCachedDocument(
        project: Project,
        virtualFile: com.intellij.openapi.vfs.VirtualFile,
    ) {
        val document = FileDocumentManager.getInstance().getCachedDocument(virtualFile) ?: return
        val documentManager = PsiDocumentManager.getInstance(project)
        if (!documentManager.isCommitted(document)) {
            documentManager.commitDocument(document)
        }
    }

    private fun resolveMapperClass(
        project: Project,
        qualifiedName: String,
    ): MapperClassResolution {
        val candidates = JavaPsiFacade.getInstance(project)
            .findClasses(qualifiedName, GlobalSearchScope.projectScope(project))
            .toList()
        return when (candidates.size) {
            0 -> MapperClassResolution.Failed(XmlMapperMethodCaptureFailure.UNRESOLVED_MAPPER_TYPE)
            1 -> MapperClassResolution.Resolved(candidates.single())
            else -> MapperClassResolution.Failed(XmlMapperMethodCaptureFailure.AMBIGUOUS_MAPPER_TYPE)
        }
    }

    private fun resolveMethod(
        mapperClass: PsiClass,
        methodName: String,
    ): MethodResolution {
        val methods = mapperClass.findMethodsByName(methodName, false).toList()
        return when (methods.size) {
            0 -> MethodResolution.Failed(XmlMapperMethodCaptureFailure.MISSING_METHOD)
            1 -> MethodResolution.Resolved(methods.single())
            else -> MethodResolution.Failed(XmlMapperMethodCaptureFailure.AMBIGUOUS_METHOD)
        }
    }

    private fun isSpecialParameter(type: PsiType): Boolean {
        val resolved = (type as? PsiClassType)?.resolve() ?: return false
        return isSpecialParameterClass(resolved, mutableSetOf())
    }

    private fun isSpecialParameterClass(
        psiClass: PsiClass,
        visited: MutableSet<PsiClass>,
    ): Boolean {
        if (!visited.add(psiClass)) return false
        if (psiClass.qualifiedName in specialParameterTypes) return true
        return psiClass.supers.any { superClass ->
            isSpecialParameterClass(superClass, visited)
        }
    }

    private fun parameterTypeIdentity(type: PsiType): JavaTypeIdentity? {
        if (!isResolvableType(type)) return null
        return type.canonicalText.takeIf(String::isNotBlank)?.let(::JavaTypeIdentity)
    }

    private fun isResolvableType(type: PsiType): Boolean = when (type) {
        is PsiPrimitiveType -> true
        is PsiArrayType -> isResolvableType(type.componentType)
        is PsiWildcardType -> type.bound?.let(::isResolvableType) ?: true
        is PsiClassType -> type.resolve() != null && type.parameters.all(::isResolvableType)
        else -> false
    }

    private fun failed(failure: XmlMapperMethodCaptureFailure): XmlMapperMethodCaptureResult =
        XmlMapperMethodCaptureResult.Failed(failure)

    private sealed interface MapperClassResolution {
        data class Resolved(val mapperClass: PsiClass) : MapperClassResolution
        data class Failed(val failure: XmlMapperMethodCaptureFailure) : MapperClassResolution
    }

    private sealed interface MethodResolution {
        data class Resolved(val method: PsiMethod) : MethodResolution
        data class Failed(val failure: XmlMapperMethodCaptureFailure) : MethodResolution
    }
}
