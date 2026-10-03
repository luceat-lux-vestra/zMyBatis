package com.algorist.zMyBatis

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

internal data class LegacyActionSourceRevision(
    val sourceUrl: String,
    val documentModificationStamp: Long,
)

internal sealed interface LegacyActionSourceRevisionCaptureResult {
    data class Captured(val revision: LegacyActionSourceRevision) :
        LegacyActionSourceRevisionCaptureResult

    data object UncommittedSource : LegacyActionSourceRevisionCaptureResult
    data object SourceUnavailable : LegacyActionSourceRevisionCaptureResult
}

/**
 * Temporary fail-closed guard for the still-shipping legacy PSI extraction path.
 *
 * This does not make legacy PSI extraction authoritative for unsaved edits. Instead, an
 * uncommitted editor document is rejected so last-committed PSI cannot silently continue toward
 * execution. The guard retains only stable source identity + revision metadata across the
 * invocation; it does not retain Editor, Document, PSI, or VirtualFile instances.
 */
internal object LegacyActionSourceRevisionGuard {

    fun capture(
        project: Project,
        editor: Editor,
        psiFile: PsiFile,
    ): LegacyActionSourceRevisionCaptureResult {
        if (project.isDisposed || editor.isDisposed || !psiFile.isValid) {
            return LegacyActionSourceRevisionCaptureResult.SourceUnavailable
        }

        val document = editor.document
        val virtualFile = FileDocumentManager.getInstance().getFile(document)
            ?: return LegacyActionSourceRevisionCaptureResult.SourceUnavailable
        val psiVirtualFile = psiFile.virtualFile
            ?: return LegacyActionSourceRevisionCaptureResult.SourceUnavailable

        if (!virtualFile.isValid || virtualFile.url != psiVirtualFile.url) {
            return LegacyActionSourceRevisionCaptureResult.SourceUnavailable
        }
        if (!PsiDocumentManager.getInstance(project).isCommitted(document)) {
            return LegacyActionSourceRevisionCaptureResult.UncommittedSource
        }

        return LegacyActionSourceRevisionCaptureResult.Captured(
            LegacyActionSourceRevision(
                sourceUrl = virtualFile.url,
                documentModificationStamp = document.modificationStamp,
            ),
        )
    }

    fun isCurrent(
        project: Project,
        revision: LegacyActionSourceRevision,
    ): Boolean {
        if (project.isDisposed) return false

        val virtualFile = VirtualFileManager.getInstance().findFileByUrl(revision.sourceUrl)
            ?: return false
        if (!virtualFile.isValid || virtualFile.url != revision.sourceUrl) return false

        val document = FileDocumentManager.getInstance().getCachedDocument(virtualFile)
            ?: return false
        if (!PsiDocumentManager.getInstance(project).isCommitted(document)) return false

        return document.modificationStamp == revision.documentModificationStamp
    }
}
