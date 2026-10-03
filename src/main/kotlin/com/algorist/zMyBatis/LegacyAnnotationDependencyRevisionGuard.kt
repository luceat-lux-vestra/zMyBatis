package com.algorist.zMyBatis

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiField
import com.intellij.psi.PsiReferenceExpression

internal enum class LegacyAnnotationDependencyRevisionAuthority {
    DOCUMENT,
    VFS,
}

internal data class LegacyAnnotationDependencyRevision(
    val sourceUrl: String,
    val authority: LegacyAnnotationDependencyRevisionAuthority,
    val modificationStamp: Long,
)

internal data class LegacyActionInvocationSourceRevision(
    val root: LegacyActionSourceRevision,
    val annotationDependencies: List<LegacyAnnotationDependencyRevision>,
)

internal sealed interface LegacyAnnotationDependencyRevisionCaptureResult {
    data class Captured(
        val revisions: List<LegacyAnnotationDependencyRevision>,
    ) : LegacyAnnotationDependencyRevisionCaptureResult

    data class UncommittedSource(
        val sourceUrl: String,
    ) : LegacyAnnotationDependencyRevisionCaptureResult

    data object SourceUnavailable : LegacyAnnotationDependencyRevisionCaptureResult
}

/**
 * Temporary dependency guard for the still-shipping legacy Java annotation extractor.
 *
 * AnnotationSqlExtractor resolves direct annotation references and array elements one level to
 * PsiField initializers. This guard mirrors only that shipping dependency footprint. It does not
 * discover arbitrary Java dependencies or replace Leap source-graph authority.
 *
 * The token retained across asynchronous boundaries contains only source URL, authority kind, and
 * revision metadata. No PSI, Document, VirtualFile, Editor, or Project object is retained.
 */
internal object LegacyAnnotationDependencyRevisionGuard {

    fun capture(
        project: Project,
        annotation: PsiAnnotation?,
    ): LegacyAnnotationDependencyRevisionCaptureResult {
        if (project.isDisposed || annotation == null || !annotation.isValid) {
            return LegacyAnnotationDependencyRevisionCaptureResult.SourceUnavailable
        }

        val value = annotation.findAttributeValue("value")
            ?: return LegacyAnnotationDependencyRevisionCaptureResult.Captured(emptyList())
        val references = when (value) {
            is PsiReferenceExpression -> listOf(value)
            is PsiArrayInitializerMemberValue ->
                value.initializers.filterIsInstance<PsiReferenceExpression>()
            else -> emptyList()
        }

        val revisionsByUrl = linkedMapOf<String, LegacyAnnotationDependencyRevision>()
        for (reference in references) {
            val field = reference.resolve() as? PsiField ?: continue
            if (!field.hasInitializer()) continue
            val sourceFile = field.containingFile
                ?: return LegacyAnnotationDependencyRevisionCaptureResult.SourceUnavailable
            val virtualFile = sourceFile.virtualFile
                ?: return LegacyAnnotationDependencyRevisionCaptureResult.SourceUnavailable
            if (!sourceFile.isValid || !virtualFile.isValid) {
                return LegacyAnnotationDependencyRevisionCaptureResult.SourceUnavailable
            }

            when (val captured = captureSource(project, virtualFile)) {
                is LegacyAnnotationDependencyRevisionCaptureResult.Captured -> {
                    captured.revisions.forEach { revision ->
                        revisionsByUrl[revision.sourceUrl] = revision
                    }
                }
                is LegacyAnnotationDependencyRevisionCaptureResult.UncommittedSource -> return captured
                LegacyAnnotationDependencyRevisionCaptureResult.SourceUnavailable ->
                    return LegacyAnnotationDependencyRevisionCaptureResult.SourceUnavailable
            }
        }

        return LegacyAnnotationDependencyRevisionCaptureResult.Captured(
            revisionsByUrl.values.sortedBy { it.sourceUrl },
        )
    }

    fun areCurrent(
        project: Project,
        revisions: List<LegacyAnnotationDependencyRevision>,
    ): Boolean {
        if (project.isDisposed) return false

        val fileDocumentManager = FileDocumentManager.getInstance()
        val psiDocumentManager = PsiDocumentManager.getInstance(project)
        for (revision in revisions) {
            val virtualFile = VirtualFileManager.getInstance().findFileByUrl(revision.sourceUrl)
                ?: return false
            if (!virtualFile.isValid || virtualFile.url != revision.sourceUrl) return false

            when (revision.authority) {
                LegacyAnnotationDependencyRevisionAuthority.DOCUMENT -> {
                    val document = fileDocumentManager.getCachedDocument(virtualFile)
                        ?: return false
                    if (!psiDocumentManager.isCommitted(document)) return false
                    if (document.modificationStamp != revision.modificationStamp) return false
                }
                LegacyAnnotationDependencyRevisionAuthority.VFS -> {
                    // A later cached Document is a source-authority takeover. Even if its current
                    // text matches disk, force a new invocation to establish one coherent token.
                    if (fileDocumentManager.getCachedDocument(virtualFile) != null) return false
                    if (virtualFile.modificationStamp != revision.modificationStamp) return false
                }
            }
        }
        return true
    }

    private fun captureSource(
        project: Project,
        virtualFile: VirtualFile,
    ): LegacyAnnotationDependencyRevisionCaptureResult {
        if (project.isDisposed || !virtualFile.isValid) {
            return LegacyAnnotationDependencyRevisionCaptureResult.SourceUnavailable
        }

        val document = FileDocumentManager.getInstance().getCachedDocument(virtualFile)
        if (document != null) {
            if (!PsiDocumentManager.getInstance(project).isCommitted(document)) {
                return LegacyAnnotationDependencyRevisionCaptureResult.UncommittedSource(
                    virtualFile.url,
                )
            }
            return LegacyAnnotationDependencyRevisionCaptureResult.Captured(
                listOf(
                    LegacyAnnotationDependencyRevision(
                        sourceUrl = virtualFile.url,
                        authority = LegacyAnnotationDependencyRevisionAuthority.DOCUMENT,
                        modificationStamp = document.modificationStamp,
                    ),
                ),
            )
        }

        return LegacyAnnotationDependencyRevisionCaptureResult.Captured(
            listOf(
                LegacyAnnotationDependencyRevision(
                    sourceUrl = virtualFile.url,
                    authority = LegacyAnnotationDependencyRevisionAuthority.VFS,
                    modificationStamp = virtualFile.modificationStamp,
                ),
            ),
        )
    }
}
