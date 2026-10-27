package com.algorist.zMyBatis.source

import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

internal data class XmlProjectMapperSourceCapture(
    val snapshots: List<SourceSnapshot>,
    val discoveries: List<XmlMapperDocumentDiscovery>,
)

internal sealed interface XmlProjectMapperSourceCaptureResult {
    data class Captured(
        val capture: XmlProjectMapperSourceCapture,
    ) : XmlProjectMapperSourceCaptureResult

    data object IndexUnavailable : XmlProjectMapperSourceCaptureResult
}

/**
 * Captures project mapper XML candidates without inventing a second mapper index.
 *
 * The active editor snapshot remains authoritative for the root mapper. Other project XML files are
 * bounded snapshots through #62's dependent-source adapter; only documents that successfully
 * discover as MyBatis mappers enter the candidate set. Unreadable/non-mapper candidates are omitted,
 * so a statement that actually depends on them still fails closed later as a missing dependency.
 */
internal object XmlProjectMapperSourceCaptureAdapter {
    private const val MAX_DEPENDENT_CONTENT_LENGTH = 2 * 1024 * 1024
    private const val VFS_ID_PREFIX = "vfs:"

    fun capture(
        project: Project,
        rootSnapshot: SourceSnapshot,
        rootDiscovery: XmlMapperDocumentDiscovery,
        checkCanceled: () -> Unit = {},
    ): XmlProjectMapperSourceCaptureResult {
        checkCanceled()
        val xmlFiles = try {
            ReadAction.compute<List<VirtualFile>, RuntimeException> {
                FilenameIndex.getAllFilesByExt(
                    project,
                    "xml",
                    GlobalSearchScope.projectScope(project),
                ).sortedBy { it.url }
            }
        } catch (_: IndexNotReadyException) {
            return XmlProjectMapperSourceCaptureResult.IndexUnavailable
        }
        checkCanceled()

        val snapshotsById = linkedMapOf(rootSnapshot.fileId to rootSnapshot)
        val discoveriesById = linkedMapOf(rootDiscovery.sourceFileId to rootDiscovery)
        val rootUrl = rootSnapshot.fileId.value
            .takeIf { it.startsWith(VFS_ID_PREFIX) }
            ?.removePrefix(VFS_ID_PREFIX)

        for (virtualFile in xmlFiles) {
            checkCanceled()
            if (virtualFile.url == rootUrl) continue

            val snapshotCapture = DependentMapperSourceSnapshotAdapter.capture(
                virtualFile = virtualFile,
                maxContentLength = MAX_DEPENDENT_CONTENT_LENGTH,
            )
            checkCanceled()
            val snapshot = when (snapshotCapture) {
                is DependentMapperSourceCaptureResult.Captured -> snapshotCapture.snapshot
                is DependentMapperSourceCaptureResult.ContentTooLarge,
                DependentMapperSourceCaptureResult.InvalidSource,
                DependentMapperSourceCaptureResult.SourceChangedDuringCapture,
                DependentMapperSourceCaptureResult.UnreadableSource,
                -> continue
            }

            val discoveryResult = XmlMapperSourceDiscovery.discover(snapshot)
            checkCanceled()
            val discovery = when (discoveryResult) {
                is XmlMapperDiscoveryResult.Discovered -> discoveryResult.mapper
                is XmlMapperDiscoveryResult.Failed -> continue
            }

            snapshotsById[snapshot.fileId] = snapshot
            discoveriesById[discovery.sourceFileId] = discovery
        }
        checkCanceled()

        return XmlProjectMapperSourceCaptureResult.Captured(
            XmlProjectMapperSourceCapture(
                snapshots = snapshotsById.values.sortedBy { it.fileId.value },
                discoveries = discoveriesById.values.sortedBy { it.sourceFileId.value },
            ),
        )
    }
}
