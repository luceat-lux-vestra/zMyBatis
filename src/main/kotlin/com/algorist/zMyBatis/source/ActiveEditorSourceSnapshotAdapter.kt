package com.algorist.zMyBatis.source

import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager

data class ActiveEditorSourceCapture(
    val snapshot: SourceSnapshot,
    val caretOffset: Int,
)

sealed interface ActiveEditorSourceCaptureResult {
    data class Captured(val capture: ActiveEditorSourceCapture) : ActiveEditorSourceCaptureResult

    data object MissingVirtualFile : ActiveEditorSourceCaptureResult

    data object SourceChangedDuringCapture : ActiveEditorSourceCaptureResult

    data class InvalidCaretOffset(
        val caretOffset: Int,
        val contentLength: Int,
    ) : ActiveEditorSourceCaptureResult
}

object ActiveEditorSourceSnapshotAdapter {
    fun capture(editor: Editor): ActiveEditorSourceCaptureResult {
        val document = editor.document
        val virtualFile = FileDocumentManager.getInstance().getFile(document)
            ?: return ActiveEditorSourceCaptureResult.MissingVirtualFile

        val revisionBeforeCapture = document.modificationStamp
        val content = document.immutableCharSequence.toString()
        val caretOffset = editor.caretModel.offset
        val revisionAfterCapture = document.modificationStamp

        if (revisionBeforeCapture != revisionAfterCapture) {
            return ActiveEditorSourceCaptureResult.SourceChangedDuringCapture
        }
        if (caretOffset !in 0..content.length) {
            return ActiveEditorSourceCaptureResult.InvalidCaretOffset(caretOffset, content.length)
        }

        return ActiveEditorSourceCaptureResult.Captured(
            ActiveEditorSourceCapture(
                snapshot = SourceSnapshot(
                    fileId = SourceFileId("vfs:${virtualFile.url}"),
                    revision = SourceRevision("document:$revisionAfterCapture"),
                    content = content,
                ),
                caretOffset = caretOffset,
            ),
        )
    }
}
