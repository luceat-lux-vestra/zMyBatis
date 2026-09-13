package com.algorist.zMyBatis.source

import com.algorist.zMyBatis.core.source.CapturedStatement
import com.algorist.zMyBatis.core.source.JavaAnnotationStatementCapture
import com.algorist.zMyBatis.core.source.JavaMethodParameterMetadata
import com.algorist.zMyBatis.core.source.JavaStatementId
import com.algorist.zMyBatis.core.source.JavaTypeIdentity
import com.algorist.zMyBatis.core.source.MethodSignature
import com.algorist.zMyBatis.core.source.SourceDependencyEdge
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.StatementSourceGraph
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiParenthesizedExpression
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiType
import com.intellij.psi.PsiWildcardType
import com.intellij.psi.util.PsiTreeUtil

sealed interface JavaAnnotationSourceCaptureResult {
    data class Captured(val capture: JavaAnnotationStatementCapture) : JavaAnnotationSourceCaptureResult

    data class Failed(val failure: JavaAnnotationSourceCaptureFailure) : JavaAnnotationSourceCaptureResult
}

enum class JavaAnnotationSourceCaptureFailure {
    MISSING_VIRTUAL_FILE,
    SOURCE_CHANGED_DURING_CAPTURE,
    INVALID_CARET_OFFSET,
    UNSUPPORTED_KOTLIN_SOURCE,
    UNSUPPORTED_SOURCE_LANGUAGE,
    MISSING_METHOD,
    UNSUPPORTED_METHOD_FORM,
    PROVIDER_ANNOTATION,
    MISSING_STATEMENT_ANNOTATION,
    AMBIGUOUS_STATEMENT_ANNOTATION,
    UNSUPPORTED_DATABASE_ID,
    UNSUPPORTED_AFFECT_DATA,
    UNSUPPORTED_LANGUAGE_DRIVER,
    UNRESOLVED_MAPPER_TYPE,
    UNRESOLVED_PARAMETER_TYPE,
    UNRESOLVED_PARAM_ALIAS,
    UNRESOLVED_ANNOTATION_VALUE,
    DEPENDENT_SOURCE_UNAVAILABLE,
    CONSTANT_DEPENDENCY_CYCLE,
    SOURCE_PSI_MISMATCH,
}

object JavaAnnotationSourceCaptureAdapter {
    private const val DEFAULT_MAX_DEPENDENT_CONTENT_LENGTH = 2 * 1024 * 1024
    private const val PARAM_ANNOTATION = "org.apache.ibatis.annotations.Param"
    private const val LANG_ANNOTATION = "org.apache.ibatis.annotations.Lang"

    private val statementAnnotations = linkedMapOf(
        "org.apache.ibatis.annotations.Select" to StatementKind.SELECT,
        "org.apache.ibatis.annotations.Insert" to StatementKind.INSERT,
        "org.apache.ibatis.annotations.Update" to StatementKind.UPDATE,
        "org.apache.ibatis.annotations.Delete" to StatementKind.DELETE,
    )

    private val statementAnnotationContainers = statementAnnotations.keys.mapTo(linkedSetOf()) { "$it.List" }

    private val providerAnnotations = setOf(
        "org.apache.ibatis.annotations.SelectProvider",
        "org.apache.ibatis.annotations.InsertProvider",
        "org.apache.ibatis.annotations.UpdateProvider",
        "org.apache.ibatis.annotations.DeleteProvider",
    )

    private val providerAnnotationContainers = providerAnnotations.mapTo(linkedSetOf()) { "$it.List" }

    fun capture(
        project: Project,
        editor: Editor,
        maxDependentContentLength: Int = DEFAULT_MAX_DEPENDENT_CONTENT_LENGTH,
    ): JavaAnnotationSourceCaptureResult {
        require(maxDependentContentLength in 1 until Int.MAX_VALUE) {
            "max dependent content length must be positive and leave room for the overflow probe"
        }

        val beforeSynchronization = when (val result = ActiveEditorSourceSnapshotAdapter.capture(editor)) {
            is ActiveEditorSourceCaptureResult.Captured -> result.capture
            ActiveEditorSourceCaptureResult.MissingVirtualFile -> return failed(
                JavaAnnotationSourceCaptureFailure.MISSING_VIRTUAL_FILE,
            )
            ActiveEditorSourceCaptureResult.SourceChangedDuringCapture -> return failed(
                JavaAnnotationSourceCaptureFailure.SOURCE_CHANGED_DURING_CAPTURE,
            )
            is ActiveEditorSourceCaptureResult.InvalidCaretOffset -> return failed(
                JavaAnnotationSourceCaptureFailure.INVALID_CARET_OFFSET,
            )
        }

        val documentManager = PsiDocumentManager.getInstance(project)
        documentManager.commitDocument(editor.document)

        val afterSynchronization = when (val result = ActiveEditorSourceSnapshotAdapter.capture(editor)) {
            is ActiveEditorSourceCaptureResult.Captured -> result.capture
            ActiveEditorSourceCaptureResult.MissingVirtualFile -> return failed(
                JavaAnnotationSourceCaptureFailure.MISSING_VIRTUAL_FILE,
            )
            ActiveEditorSourceCaptureResult.SourceChangedDuringCapture -> return failed(
                JavaAnnotationSourceCaptureFailure.SOURCE_CHANGED_DURING_CAPTURE,
            )
            is ActiveEditorSourceCaptureResult.InvalidCaretOffset -> return failed(
                JavaAnnotationSourceCaptureFailure.INVALID_CARET_OFFSET,
            )
        }

        if (beforeSynchronization != afterSynchronization) {
            return failed(JavaAnnotationSourceCaptureFailure.SOURCE_CHANGED_DURING_CAPTURE)
        }

        val psiFile = documentManager.getPsiFile(editor.document)
            ?: return failed(JavaAnnotationSourceCaptureFailure.SOURCE_PSI_MISMATCH)
        if (psiFile !is PsiJavaFile) {
            return failed(
                if (psiFile.language.id.equals("kotlin", ignoreCase = true)) {
                    JavaAnnotationSourceCaptureFailure.UNSUPPORTED_KOTLIN_SOURCE
                } else {
                    JavaAnnotationSourceCaptureFailure.UNSUPPORTED_SOURCE_LANGUAGE
                },
            )
        }
        if (psiFile.text != afterSynchronization.snapshot.content) {
            return failed(JavaAnnotationSourceCaptureFailure.SOURCE_PSI_MISMATCH)
        }

        val element = elementAtCaret(psiFile, afterSynchronization.caretOffset)
            ?: return failed(JavaAnnotationSourceCaptureFailure.MISSING_METHOD)
        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)
            ?: return failed(JavaAnnotationSourceCaptureFailure.MISSING_METHOD)
        if (method.containingFile !== psiFile || !rangeMatchesSnapshot(method, afterSynchronization.snapshot)) {
            return failed(JavaAnnotationSourceCaptureFailure.SOURCE_PSI_MISMATCH)
        }
        if (method.hasModifierProperty(PsiModifier.DEFAULT)) {
            return failed(JavaAnnotationSourceCaptureFailure.UNSUPPORTED_METHOD_FORM)
        }

        val methodAnnotations = method.modifierList.annotations.toList()
        if (
            methodAnnotations.any { annotation ->
                providerAnnotations.any { annotationMatches(annotation, it) } ||
                    providerAnnotationContainers.any { annotationMatches(annotation, it) }
            }
        ) {
            return failed(JavaAnnotationSourceCaptureFailure.PROVIDER_ANNOTATION)
        }
        if (method.hasAnnotation(LANG_ANNOTATION)) {
            return failed(JavaAnnotationSourceCaptureFailure.UNSUPPORTED_LANGUAGE_DRIVER)
        }
        if (
            methodAnnotations.any { annotation ->
                statementAnnotationContainers.any { annotationMatches(annotation, it) }
            }
        ) {
            return failed(JavaAnnotationSourceCaptureFailure.AMBIGUOUS_STATEMENT_ANNOTATION)
        }

        val directAnnotations = methodAnnotations.mapNotNull { annotation ->
            statementAnnotations.entries.firstOrNull { (annotationName, _) ->
                annotationMatches(annotation, annotationName)
            }?.let { (_, kind) -> annotation to kind }
        }
        if (directAnnotations.isEmpty()) {
            return failed(JavaAnnotationSourceCaptureFailure.MISSING_STATEMENT_ANNOTATION)
        }
        if (directAnnotations.size != 1) {
            return failed(JavaAnnotationSourceCaptureFailure.AMBIGUOUS_STATEMENT_ANNOTATION)
        }

        val (statementAnnotation, statementKind) = directAnnotations.single()
        if (statementAnnotation.findDeclaredAttributeValue("databaseId") != null) {
            return failed(JavaAnnotationSourceCaptureFailure.UNSUPPORTED_DATABASE_ID)
        }
        if (
            statementKind == StatementKind.SELECT &&
            statementAnnotation.findDeclaredAttributeValue("affectData") != null
        ) {
            return failed(JavaAnnotationSourceCaptureFailure.UNSUPPORTED_AFFECT_DATA)
        }

        val qualifiedMapperType = method.containingClass?.qualifiedName
            ?.takeIf(String::isNotBlank)
            ?: return failed(JavaAnnotationSourceCaptureFailure.UNRESOLVED_MAPPER_TYPE)

        val parameterTypeIdentities = mutableListOf<JavaTypeIdentity>()
        method.parameterList.parameters.forEach { parameter ->
            val identity = parameterTypeIdentity(parameter.type)
                ?: return failed(JavaAnnotationSourceCaptureFailure.UNRESOLVED_PARAMETER_TYPE)
            parameterTypeIdentities += identity
        }

        val state = CaptureState(
            project = project,
            activeSnapshot = afterSynchronization.snapshot,
            maxDependentContentLength = maxDependentContentLength,
        )

        val parameters = mutableListOf<JavaMethodParameterMetadata>()
        method.parameterList.parameters.forEachIndexed { index, parameter ->
            val paramAnnotation = parameter.getAnnotation(PARAM_ANNOTATION)
            val paramAlias = if (paramAnnotation == null) {
                null
            } else {
                when (
                    val resolution = resolveAnnotationSingleString(
                        paramAnnotation,
                        afterSynchronization.snapshot.fileId,
                        state,
                    )
                ) {
                    is StringResolution.Resolved -> resolution.value.takeIf(String::isNotBlank)
                        ?: return failed(JavaAnnotationSourceCaptureFailure.UNRESOLVED_PARAM_ALIAS)
                    is StringResolution.Failed -> return failed(
                        if (resolution.failure == JavaAnnotationSourceCaptureFailure.UNRESOLVED_ANNOTATION_VALUE) {
                            JavaAnnotationSourceCaptureFailure.UNRESOLVED_PARAM_ALIAS
                        } else {
                            resolution.failure
                        },
                    )
                }
            }
            parameters += JavaMethodParameterMetadata(
                index = index,
                sourceName = parameter.name.takeIf { it.isNotBlank() },
                typeIdentity = parameterTypeIdentities[index],
                myBatisParamAlias = paramAlias,
            )
        }

        val sqlSegments = when (
            val resolution = resolveAnnotationStrings(
                statementAnnotation,
                afterSynchronization.snapshot.fileId,
                state,
            )
        ) {
            is StringsResolution.Resolved -> resolution.values
            is StringsResolution.Failed -> return failed(resolution.failure)
        }

        val methodRange = method.textRange
        val statementId = JavaStatementId(
            sourceFileId = afterSynchronization.snapshot.fileId,
            qualifiedMapperType = qualifiedMapperType,
            methodSignature = MethodSignature(method.name, parameterTypeIdentities),
        )
        val statement = CapturedStatement(
            id = statementId,
            kind = statementKind,
            sourceRange = SourceRange(methodRange.startOffset, methodRange.endOffset),
        )

        val graph = try {
            StatementSourceGraph(
                rootStatement = statement,
                sourceSnapshots = state.snapshots(),
                dependencies = state.dependencies(),
            )
        } catch (_: IllegalArgumentException) {
            return failed(JavaAnnotationSourceCaptureFailure.SOURCE_PSI_MISMATCH)
        }

        return JavaAnnotationSourceCaptureResult.Captured(
            JavaAnnotationStatementCapture(
                sourceGraph = graph,
                sqlSegments = sqlSegments,
                parameters = parameters,
            ),
        )
    }

    private fun elementAtCaret(file: PsiJavaFile, caretOffset: Int): PsiElement? {
        if (file.textLength == 0) return null
        val effectiveOffset = if (caretOffset == file.textLength) caretOffset - 1 else caretOffset
        if (effectiveOffset !in 0 until file.textLength) return null
        return file.findElementAt(effectiveOffset)
    }

    private fun rangeMatchesSnapshot(element: PsiElement, snapshot: SourceSnapshot): Boolean {
        val range = element.textRange
        return range.startOffset >= 0 &&
            range.endOffset <= snapshot.content.length &&
            snapshot.content.substring(range.startOffset, range.endOffset) == element.text
    }

    private fun annotationMatches(annotation: PsiAnnotation, annotationName: String): Boolean =
        annotation.hasQualifiedName(annotationName)

    private fun parameterTypeIdentity(type: PsiType): JavaTypeIdentity? {
        if (!isResolvableType(type)) return null
        val canonicalText = type.canonicalText.takeIf(String::isNotBlank) ?: return null
        return JavaTypeIdentity(canonicalText)
    }

    private fun isResolvableType(type: PsiType): Boolean = when (type) {
        is PsiPrimitiveType -> true
        is PsiArrayType -> isResolvableType(type.componentType)
        is PsiWildcardType -> type.bound?.let(::isResolvableType) ?: true
        is PsiClassType -> type.resolve() != null && type.parameters.all(::isResolvableType)
        else -> false
    }

    private fun resolveAnnotationStrings(
        annotation: PsiAnnotation,
        ownerFileId: SourceFileId,
        state: CaptureState,
    ): StringsResolution {
        val value = annotation.findAttributeValue("value")
            ?: return StringsResolution.Failed(JavaAnnotationSourceCaptureFailure.UNRESOLVED_ANNOTATION_VALUE)
        val values = if (value is PsiArrayInitializerMemberValue) value.initializers.toList() else listOf(value)
        if (values.isEmpty()) {
            return StringsResolution.Failed(JavaAnnotationSourceCaptureFailure.UNRESOLVED_ANNOTATION_VALUE)
        }

        val resolved = mutableListOf<String>()
        values.forEach { member ->
            when (val result = resolveStringMember(member, ownerFileId, state)) {
                is StringResolution.Resolved -> resolved += result.value
                is StringResolution.Failed -> return StringsResolution.Failed(result.failure)
            }
        }
        return StringsResolution.Resolved(resolved)
    }

    private fun resolveAnnotationSingleString(
        annotation: PsiAnnotation,
        ownerFileId: SourceFileId,
        state: CaptureState,
    ): StringResolution {
        val value = annotation.findAttributeValue("value")
            ?: return StringResolution.Failed(JavaAnnotationSourceCaptureFailure.UNRESOLVED_ANNOTATION_VALUE)
        if (value is PsiArrayInitializerMemberValue) {
            return StringResolution.Failed(JavaAnnotationSourceCaptureFailure.UNRESOLVED_ANNOTATION_VALUE)
        }
        return resolveStringMember(value, ownerFileId, state)
    }

    private fun resolveStringMember(
        member: PsiAnnotationMemberValue,
        ownerFileId: SourceFileId,
        state: CaptureState,
    ): StringResolution = if (member is PsiExpression) {
        resolveStringExpression(member, ownerFileId, state)
    } else {
        StringResolution.Failed(JavaAnnotationSourceCaptureFailure.UNRESOLVED_ANNOTATION_VALUE)
    }

    private fun resolveStringExpression(
        expression: PsiExpression,
        ownerFileId: SourceFileId,
        state: CaptureState,
    ): StringResolution = when (val resolution = resolveConstantExpression(expression, ownerFileId, state)) {
        is ConstantResolution.Resolved -> if (resolution.value is String) {
            StringResolution.Resolved(resolution.value)
        } else {
            StringResolution.Failed(JavaAnnotationSourceCaptureFailure.UNRESOLVED_ANNOTATION_VALUE)
        }
        is ConstantResolution.Failed -> StringResolution.Failed(resolution.failure)
    }

    private fun resolveConstantExpression(
        expression: PsiExpression,
        ownerFileId: SourceFileId,
        state: CaptureState,
    ): ConstantResolution = when (expression) {
        is PsiLiteralExpression -> expression.value?.let(ConstantResolution::Resolved)
            ?: ConstantResolution.Failed(JavaAnnotationSourceCaptureFailure.UNRESOLVED_ANNOTATION_VALUE)
        is PsiReferenceExpression -> resolveConstantReference(expression, ownerFileId, state)
        is PsiParenthesizedExpression -> expression.expression?.let {
            resolveConstantExpression(it, ownerFileId, state)
        } ?: ConstantResolution.Failed(JavaAnnotationSourceCaptureFailure.UNRESOLVED_ANNOTATION_VALUE)
        else -> {
            when (val dependencyResolution = prepareConstantDependencies(expression, ownerFileId, state)) {
                is ConstantResolution.Failed -> dependencyResolution
                is ConstantResolution.Resolved -> {
                    val value = JavaPsiFacade.getInstance(state.project)
                        .constantEvaluationHelper
                        .computeConstantExpression(expression)
                    if (isSupportedConstantValue(value)) {
                        ConstantResolution.Resolved(value!!)
                    } else {
                        ConstantResolution.Failed(JavaAnnotationSourceCaptureFailure.UNRESOLVED_ANNOTATION_VALUE)
                    }
                }
            }
        }
    }

    private fun prepareConstantDependencies(
        expression: PsiExpression,
        ownerFileId: SourceFileId,
        state: CaptureState,
    ): ConstantResolution {
        val references = PsiTreeUtil.findChildrenOfType(expression, PsiReferenceExpression::class.java)
            .filter { it.parent !is PsiReferenceExpression }
            .sortedBy { it.textRange.startOffset }
        references.forEach { reference ->
            when (val resolution = resolveConstantReference(reference, ownerFileId, state)) {
                is ConstantResolution.Resolved -> Unit
                is ConstantResolution.Failed -> return resolution
            }
        }
        return ConstantResolution.Resolved(Unit)
    }

    private fun resolveConstantReference(
        reference: PsiReferenceExpression,
        ownerFileId: SourceFileId,
        state: CaptureState,
    ): ConstantResolution {
        val initiallyResolvedField = reference.resolve() as? PsiField
            ?: return ConstantResolution.Failed(JavaAnnotationSourceCaptureFailure.UNRESOLVED_ANNOTATION_VALUE)
        val fieldResolution = state.authoritativeField(initiallyResolvedField)
        val field: PsiField
        val requiredSnapshot: SourceSnapshot
        when (fieldResolution) {
            is FieldResolution.Resolved -> {
                field = fieldResolution.field
                requiredSnapshot = fieldResolution.snapshot
            }
            is FieldResolution.Failed -> return ConstantResolution.Failed(fieldResolution.failure)
        }

        if (!field.hasModifierProperty(PsiModifier.STATIC) || !field.hasModifierProperty(PsiModifier.FINAL)) {
            return ConstantResolution.Failed(JavaAnnotationSourceCaptureFailure.UNRESOLVED_ANNOTATION_VALUE)
        }
        if (!isSupportedConstantFieldType(field.type)) {
            return ConstantResolution.Failed(JavaAnnotationSourceCaptureFailure.UNRESOLVED_ANNOTATION_VALUE)
        }

        val ownerSnapshot = state.snapshot(ownerFileId)
            ?: return ConstantResolution.Failed(JavaAnnotationSourceCaptureFailure.SOURCE_PSI_MISMATCH)
        val referenceRange = reference.textRange
        if (
            referenceRange.startOffset < 0 ||
            referenceRange.endOffset > ownerSnapshot.content.length ||
            ownerSnapshot.content.substring(referenceRange.startOffset, referenceRange.endOffset) != reference.text
        ) {
            return ConstantResolution.Failed(JavaAnnotationSourceCaptureFailure.SOURCE_PSI_MISMATCH)
        }
        state.addDependency(
            SourceDependencyEdge(
                dependentFileId = ownerFileId,
                requiredFileId = requiredSnapshot.fileId,
                referenceRange = SourceRange(referenceRange.startOffset, referenceRange.endOffset),
            ),
        )

        val containingClassName = field.containingClass?.qualifiedName
            ?: return ConstantResolution.Failed(JavaAnnotationSourceCaptureFailure.UNRESOLVED_ANNOTATION_VALUE)
        val fieldKey = "${requiredSnapshot.fileId.value}#$containingClassName#${field.name}"
        state.resolvedFieldValue(fieldKey)?.let { return ConstantResolution.Resolved(it) }
        if (!state.beginResolvingField(fieldKey)) {
            return ConstantResolution.Failed(JavaAnnotationSourceCaptureFailure.CONSTANT_DEPENDENCY_CYCLE)
        }

        val initializer = field.initializer
        val dependencyProof = if (initializer == null) {
            ConstantResolution.Failed(JavaAnnotationSourceCaptureFailure.UNRESOLVED_ANNOTATION_VALUE)
        } else {
            resolveConstantExpression(initializer, requiredSnapshot.fileId, state)
        }
        state.endResolvingField(fieldKey)
        if (dependencyProof is ConstantResolution.Failed) {
            return dependencyProof
        }

        val compilerValue = field.computeConstantValue()
        if (!isSupportedConstantValue(compilerValue)) {
            return ConstantResolution.Failed(JavaAnnotationSourceCaptureFailure.UNRESOLVED_ANNOTATION_VALUE)
        }
        state.rememberFieldValue(fieldKey, compilerValue!!)
        return ConstantResolution.Resolved(compilerValue)
    }

    private fun isSupportedConstantFieldType(type: PsiType): Boolean =
        type is PsiPrimitiveType || type.canonicalText == "java.lang.String" || type.canonicalText == "String"

    private fun isSupportedConstantValue(value: Any?): Boolean =
        value is String || value is Number || value is Boolean || value is Char

    private fun failed(failure: JavaAnnotationSourceCaptureFailure): JavaAnnotationSourceCaptureResult =
        JavaAnnotationSourceCaptureResult.Failed(failure)

    private sealed interface StringResolution {
        data class Resolved(val value: String) : StringResolution
        data class Failed(val failure: JavaAnnotationSourceCaptureFailure) : StringResolution
    }

    private sealed interface StringsResolution {
        data class Resolved(val values: List<String>) : StringsResolution
        data class Failed(val failure: JavaAnnotationSourceCaptureFailure) : StringsResolution
    }

    private sealed interface ConstantResolution {
        data class Resolved(val value: Any) : ConstantResolution
        data class Failed(val failure: JavaAnnotationSourceCaptureFailure) : ConstantResolution
    }

    private sealed interface SnapshotResolution {
        data class Resolved(val snapshot: SourceSnapshot) : SnapshotResolution
        data class Failed(val failure: JavaAnnotationSourceCaptureFailure) : SnapshotResolution
    }

    private sealed interface FieldResolution {
        data class Resolved(val field: PsiField, val snapshot: SourceSnapshot) : FieldResolution
        data class Failed(val failure: JavaAnnotationSourceCaptureFailure) : FieldResolution
    }

    private class CaptureState(
        val project: Project,
        val activeSnapshot: SourceSnapshot,
        private val maxDependentContentLength: Int,
    ) {
        private val snapshotsByFileId = linkedMapOf(activeSnapshot.fileId to activeSnapshot)
        private val dependencySet = linkedSetOf<SourceDependencyEdge>()
        private val resolvingFieldKeys = linkedSetOf<String>()
        private val resolvedFieldValues = linkedMapOf<String, Any>()

        fun snapshotFor(file: com.intellij.psi.PsiFile?): SnapshotResolution {
            val virtualFile = file?.virtualFile
                ?: return SnapshotResolution.Failed(JavaAnnotationSourceCaptureFailure.DEPENDENT_SOURCE_UNAVAILABLE)
            val fileId = SourceFileId("vfs:${virtualFile.url}")
            if (fileId == activeSnapshot.fileId) {
                return SnapshotResolution.Resolved(activeSnapshot)
            }

            val captured = when (
                val result = DependentMapperSourceSnapshotAdapter.capture(virtualFile, maxDependentContentLength)
            ) {
                is DependentMapperSourceCaptureResult.Captured -> result.snapshot
                DependentMapperSourceCaptureResult.SourceChangedDuringCapture -> {
                    return SnapshotResolution.Failed(JavaAnnotationSourceCaptureFailure.SOURCE_CHANGED_DURING_CAPTURE)
                }
                is DependentMapperSourceCaptureResult.ContentTooLarge,
                DependentMapperSourceCaptureResult.InvalidSource,
                DependentMapperSourceCaptureResult.UnreadableSource -> {
                    return SnapshotResolution.Failed(JavaAnnotationSourceCaptureFailure.DEPENDENT_SOURCE_UNAVAILABLE)
                }
            }

            val existing = snapshotsByFileId[captured.fileId]
            if (existing != null && existing != captured) {
                return SnapshotResolution.Failed(JavaAnnotationSourceCaptureFailure.SOURCE_CHANGED_DURING_CAPTURE)
            }
            snapshotsByFileId[captured.fileId] = captured
            return SnapshotResolution.Resolved(captured)
        }

        fun authoritativeField(initialField: PsiField): FieldResolution {
            val containingClassName = initialField.containingClass?.qualifiedName
                ?: return FieldResolution.Failed(JavaAnnotationSourceCaptureFailure.UNRESOLVED_ANNOTATION_VALUE)
            val fieldName = initialField.name
            val initialFile = initialField.containingFile
                ?: return FieldResolution.Failed(JavaAnnotationSourceCaptureFailure.DEPENDENT_SOURCE_UNAVAILABLE)
            val virtualFile = initialFile.virtualFile
                ?: return FieldResolution.Failed(JavaAnnotationSourceCaptureFailure.DEPENDENT_SOURCE_UNAVAILABLE)

            val capturedBeforeSynchronization = when (val result = snapshotFor(initialFile)) {
                is SnapshotResolution.Resolved -> result.snapshot
                is SnapshotResolution.Failed -> return FieldResolution.Failed(result.failure)
            }

            val fileDocumentManager = FileDocumentManager.getInstance()
            val cachedDocument = fileDocumentManager.getCachedDocument(virtualFile)
            if (cachedDocument == null) {
                return FieldResolution.Resolved(initialField, capturedBeforeSynchronization)
            }

            val documentManager = PsiDocumentManager.getInstance(project)
            if (documentManager.isCommitted(cachedDocument) && !fileDocumentManager.isDocumentUnsaved(cachedDocument)) {
                return FieldResolution.Resolved(initialField, capturedBeforeSynchronization)
            }
            if (!documentManager.isCommitted(cachedDocument)) {
                documentManager.commitDocument(cachedDocument)
            }
            val synchronizedFile = documentManager.getPsiFile(cachedDocument)
                ?: return FieldResolution.Failed(JavaAnnotationSourceCaptureFailure.SOURCE_PSI_MISMATCH)
            val capturedAfterSynchronization = when (val result = snapshotFor(synchronizedFile)) {
                is SnapshotResolution.Resolved -> result.snapshot
                is SnapshotResolution.Failed -> return FieldResolution.Failed(result.failure)
            }
            if (capturedBeforeSynchronization != capturedAfterSynchronization) {
                return FieldResolution.Failed(JavaAnnotationSourceCaptureFailure.SOURCE_CHANGED_DURING_CAPTURE)
            }
            if (synchronizedFile.text != capturedBeforeSynchronization.content) {
                return FieldResolution.Failed(JavaAnnotationSourceCaptureFailure.SOURCE_PSI_MISMATCH)
            }
            val containingClass = PsiTreeUtil.findChildrenOfType(synchronizedFile, PsiClass::class.java)
                .singleOrNull { it.qualifiedName == containingClassName }
                ?: return FieldResolution.Failed(JavaAnnotationSourceCaptureFailure.UNRESOLVED_ANNOTATION_VALUE)
            val field = containingClass.fields.singleOrNull { it.name == fieldName }
                ?: return FieldResolution.Failed(JavaAnnotationSourceCaptureFailure.UNRESOLVED_ANNOTATION_VALUE)
            if (!rangeMatchesSnapshot(field, capturedBeforeSynchronization)) {
                return FieldResolution.Failed(JavaAnnotationSourceCaptureFailure.SOURCE_PSI_MISMATCH)
            }
            return FieldResolution.Resolved(field, capturedBeforeSynchronization)
        }

        fun snapshot(fileId: SourceFileId): SourceSnapshot? = snapshotsByFileId[fileId]

        fun addDependency(edge: SourceDependencyEdge) {
            dependencySet += edge
        }

        fun snapshots(): List<SourceSnapshot> = snapshotsByFileId.values.toList()

        fun dependencies(): List<SourceDependencyEdge> = dependencySet.toList()

        fun beginResolvingField(key: String): Boolean = resolvingFieldKeys.add(key)

        fun endResolvingField(key: String) {
            resolvingFieldKeys.remove(key)
        }

        fun resolvedFieldValue(key: String): Any? = resolvedFieldValues[key]

        fun rememberFieldValue(key: String, value: Any) {
            resolvedFieldValues[key] = value
        }
    }
}
