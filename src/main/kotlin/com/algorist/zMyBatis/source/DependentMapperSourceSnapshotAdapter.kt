package com.algorist.zMyBatis.source

import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException

sealed interface DependentMapperSourceCaptureResult {
    data class Captured(val snapshot: SourceSnapshot) : DependentMapperSourceCaptureResult

    data class ContentTooLarge(val maxContentLength: Int) : DependentMapperSourceCaptureResult

    data object InvalidSource : DependentMapperSourceCaptureResult

    data object SourceChangedDuringCapture : DependentMapperSourceCaptureResult

    data object UnreadableSource : DependentMapperSourceCaptureResult
}

object DependentMapperSourceSnapshotAdapter {
    fun capture(
        virtualFile: VirtualFile,
        maxContentLength: Int,
    ): DependentMapperSourceCaptureResult =
        capture(
            virtualFile = virtualFile,
            maxContentLength = maxContentLength,
            fileDocumentManager = FileDocumentManager.getInstance(),
            vfsTextLoader = VfsUtilCore::loadText,
        )

    internal fun capture(
        virtualFile: VirtualFile,
        maxContentLength: Int,
        fileDocumentManager: FileDocumentManager,
        vfsTextLoader: (VirtualFile, Int) -> String,
    ): DependentMapperSourceCaptureResult {
        require(maxContentLength in 1 until Int.MAX_VALUE) {
            "max content length must be positive and leave room for the overflow probe"
        }
        if (!virtualFile.isValid || virtualFile.isDirectory || virtualFile.fileType.isBinary) {
            return DependentMapperSourceCaptureResult.InvalidSource
        }

        val document = fileDocumentManager.getCachedDocument(virtualFile)
        return if (document != null) {
            captureDocument(virtualFile, document, maxContentLength)
        } else {
            captureVfs(virtualFile, maxContentLength, vfsTextLoader)
        }
    }

    private fun captureDocument(
        virtualFile: VirtualFile,
        document: Document,
        maxContentLength: Int,
    ): DependentMapperSourceCaptureResult {
        val locatorBeforeCapture = virtualFile.url
        val revisionBeforeCapture = document.modificationStamp
        if (document.textLength > maxContentLength) {
            return DependentMapperSourceCaptureResult.ContentTooLarge(maxContentLength)
        }

        val content = document.immutableCharSequence.toString()
        val revisionAfterCapture = document.modificationStamp

        return capturedIfStable(
            virtualFile = virtualFile,
            locatorBeforeCapture = locatorBeforeCapture,
            revisionBeforeCapture = revisionBeforeCapture,
            revisionAfterCapture = revisionAfterCapture,
            revisionAuthority = "document",
            content = content,
        )
    }

    private fun captureVfs(
        virtualFile: VirtualFile,
        maxContentLength: Int,
        vfsTextLoader: (VirtualFile, Int) -> String,
    ): DependentMapperSourceCaptureResult {
        val locatorBeforeCapture = virtualFile.url
        val revisionBeforeCapture = virtualFile.modificationStamp
        val content = try {
            vfsTextLoader(virtualFile, maxContentLength + 1)
        } catch (_: IOException) {
            return DependentMapperSourceCaptureResult.UnreadableSource
        }

        if (content.length > maxContentLength) {
            return DependentMapperSourceCaptureResult.ContentTooLarge(maxContentLength)
        }

        return capturedIfStable(
            virtualFile = virtualFile,
            locatorBeforeCapture = locatorBeforeCapture,
            revisionBeforeCapture = revisionBeforeCapture,
            revisionAfterCapture = virtualFile.modificationStamp,
            revisionAuthority = "vfs",
            content = content,
        )
    }

    private fun capturedIfStable(
        virtualFile: VirtualFile,
        locatorBeforeCapture: String,
        revisionBeforeCapture: Long,
        revisionAfterCapture: Long,
        revisionAuthority: String,
        content: String,
    ): DependentMapperSourceCaptureResult {
        val locatorAfterCapture = virtualFile.url
        if (
            !virtualFile.isValid ||
            virtualFile.isDirectory ||
            virtualFile.fileType.isBinary ||
            locatorBeforeCapture != locatorAfterCapture ||
            revisionBeforeCapture != revisionAfterCapture
        ) {
            return DependentMapperSourceCaptureResult.SourceChangedDuringCapture
        }

        return DependentMapperSourceCaptureResult.Captured(
            SourceSnapshot(
                fileId = SourceFileId("vfs:$locatorAfterCapture"),
                revision = SourceRevision("$revisionAuthority:$revisionAfterCapture"),
                content = content,
            ),
        )
    }
}
