package com.algorist.zMyBatis.source

import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.StatementSourceGraph
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager

sealed interface SourceGraphRevalidationResult {
    data object Current : SourceGraphRevalidationResult

    data class Failed(
        val failure: SourceGraphRevalidationFailure,
    ) : SourceGraphRevalidationResult
}

sealed interface SourceGraphRevalidationFailure {
    val sourceFileId: SourceFileId

    data class UnsupportedSourceIdentity(
        override val sourceFileId: SourceFileId,
    ) : SourceGraphRevalidationFailure

    data class SourceMissing(
        override val sourceFileId: SourceFileId,
    ) : SourceGraphRevalidationFailure

    data class InvalidSource(
        override val sourceFileId: SourceFileId,
    ) : SourceGraphRevalidationFailure

    data class UnsupportedRevision(
        override val sourceFileId: SourceFileId,
        val expectedRevision: SourceRevision,
    ) : SourceGraphRevalidationFailure

    data class RevisionAuthorityUnavailable(
        override val sourceFileId: SourceFileId,
        val expectedRevision: SourceRevision,
    ) : SourceGraphRevalidationFailure

    data class SourceChanged(
        override val sourceFileId: SourceFileId,
        val expectedRevision: SourceRevision,
        val actualRevision: SourceRevision,
    ) : SourceGraphRevalidationFailure
}

/**
 * Side-effect-free source authority check for an already captured [StatementSourceGraph].
 *
 * This adapter deliberately does not refresh VFS state, load documents, commit PSI, save files, or
 * rediscover mapper semantics. #66 owns when this check is invoked around asynchronous execution
 * boundaries; this adapter only proves that #62's captured source authority is still current.
 */
object StatementSourceGraphRevalidator {
    private const val VFS_ID_PREFIX = "vfs:"
    private const val DOCUMENT_REVISION_PREFIX = "document:"
    private const val VFS_REVISION_PREFIX = "vfs:"

    fun revalidate(graph: StatementSourceGraph): SourceGraphRevalidationResult =
        revalidate(
            graph = graph,
            virtualFileLookup = VirtualFileManager.getInstance()::findFileByUrl,
            fileDocumentManager = FileDocumentManager.getInstance(),
        )

    internal fun revalidate(
        graph: StatementSourceGraph,
        virtualFileLookup: (String) -> VirtualFile?,
        fileDocumentManager: FileDocumentManager,
    ): SourceGraphRevalidationResult {
        for (snapshot in graph.sourceSnapshots) {
            revalidateSnapshot(snapshot, virtualFileLookup, fileDocumentManager)?.let {
                return SourceGraphRevalidationResult.Failed(it)
            }
        }
        return SourceGraphRevalidationResult.Current
    }

    private fun revalidateSnapshot(
        snapshot: SourceSnapshot,
        virtualFileLookup: (String) -> VirtualFile?,
        fileDocumentManager: FileDocumentManager,
    ): SourceGraphRevalidationFailure? {
        val url = snapshot.fileId.value
            .takeIf { it.startsWith(VFS_ID_PREFIX) }
            ?.removePrefix(VFS_ID_PREFIX)
            ?.takeIf(String::isNotBlank)
            ?: return SourceGraphRevalidationFailure.UnsupportedSourceIdentity(snapshot.fileId)

        val virtualFile = virtualFileLookup(url)
            ?: return SourceGraphRevalidationFailure.SourceMissing(snapshot.fileId)

        if (
            !virtualFile.isValid ||
            virtualFile.isDirectory ||
            virtualFile.fileType.isBinary ||
            virtualFile.url != url
        ) {
            return SourceGraphRevalidationFailure.InvalidSource(snapshot.fileId)
        }

        return when (val expected = parseRevision(snapshot.revision)) {
            is CapturedRevision.Document -> {
                val document = fileDocumentManager.getCachedDocument(virtualFile)
                    ?: return SourceGraphRevalidationFailure.RevisionAuthorityUnavailable(
                        sourceFileId = snapshot.fileId,
                        expectedRevision = snapshot.revision,
                    )
                val actual = SourceRevision(DOCUMENT_REVISION_PREFIX + document.modificationStamp)
                if (document.modificationStamp == expected.modificationStamp) {
                    null
                } else {
                    SourceGraphRevalidationFailure.SourceChanged(
                        sourceFileId = snapshot.fileId,
                        expectedRevision = snapshot.revision,
                        actualRevision = actual,
                    )
                }
            }

            is CapturedRevision.Vfs -> {
                val document = fileDocumentManager.getCachedDocument(virtualFile)
                if (document != null && fileDocumentManager.isDocumentUnsaved(document)) {
                    return SourceGraphRevalidationFailure.SourceChanged(
                        sourceFileId = snapshot.fileId,
                        expectedRevision = snapshot.revision,
                        actualRevision = SourceRevision(
                            DOCUMENT_REVISION_PREFIX + document.modificationStamp,
                        ),
                    )
                }

                val actual = SourceRevision(VFS_REVISION_PREFIX + virtualFile.modificationStamp)
                if (virtualFile.modificationStamp == expected.modificationStamp) {
                    null
                } else {
                    SourceGraphRevalidationFailure.SourceChanged(
                        sourceFileId = snapshot.fileId,
                        expectedRevision = snapshot.revision,
                        actualRevision = actual,
                    )
                }
            }

            null -> SourceGraphRevalidationFailure.UnsupportedRevision(
                sourceFileId = snapshot.fileId,
                expectedRevision = snapshot.revision,
            )
        }
    }

    private fun parseRevision(revision: SourceRevision): CapturedRevision? {
        val value = revision.value
        return when {
            value.startsWith(DOCUMENT_REVISION_PREFIX) ->
                value.removePrefix(DOCUMENT_REVISION_PREFIX)
                    .toLongOrNull()
                    ?.let(CapturedRevision::Document)

            value.startsWith(VFS_REVISION_PREFIX) ->
                value.removePrefix(VFS_REVISION_PREFIX)
                    .toLongOrNull()
                    ?.let(CapturedRevision::Vfs)

            else -> null
        }
    }

    private sealed interface CapturedRevision {
        val modificationStamp: Long

        data class Document(
            override val modificationStamp: Long,
        ) : CapturedRevision

        data class Vfs(
            override val modificationStamp: Long,
        ) : CapturedRevision
    }
}
