package com.algorist.zMyBatis.source

import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.XmlStatementId
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

internal data class XmlActionSourceCapture(
    val snapshot: SourceSnapshot,
    val discovery: XmlMapperDocumentDiscovery,
    val statementId: XmlStatementId,
)

internal sealed interface XmlActionSourceCaptureResult {
    data class Captured(val capture: XmlActionSourceCapture) : XmlActionSourceCaptureResult
    data class Failed(val failure: XmlActionSourceCaptureFailure) : XmlActionSourceCaptureResult
}

internal sealed interface XmlActionSourceCaptureFailure {
    data object MissingVirtualFile : XmlActionSourceCaptureFailure
    data object SourceChangedDuringCapture : XmlActionSourceCaptureFailure
    data object InvalidCaretOffset : XmlActionSourceCaptureFailure
    data object UnsupportedSourceLanguage : XmlActionSourceCaptureFailure
    data object SourcePsiMismatch : XmlActionSourceCaptureFailure
    data object StatementUnavailable : XmlActionSourceCaptureFailure
    data object MapperNamespaceUnavailable : XmlActionSourceCaptureFailure
    data class DiscoveryFailed(
        val failure: XmlMapperDiscoveryFailure,
    ) : XmlActionSourceCaptureFailure
}

/**
 * Real-editor XML admission adapter for the registered Leap execution path.
 *
 * The active editor Document remains source authority. PSI is synchronized only to identify the
 * statement at the caret and is checked back against the immutable snapshot/discovery result.
 */
internal object XmlActionSourceCaptureAdapter {
    fun capture(
        project: Project,
        editor: Editor,
    ): XmlActionSourceCaptureResult {
        val before = when (val capture = ActiveEditorSourceSnapshotAdapter.capture(editor)) {
            is ActiveEditorSourceCaptureResult.Captured -> capture.capture
            ActiveEditorSourceCaptureResult.MissingVirtualFile ->
                return failed(XmlActionSourceCaptureFailure.MissingVirtualFile)
            ActiveEditorSourceCaptureResult.SourceChangedDuringCapture ->
                return failed(XmlActionSourceCaptureFailure.SourceChangedDuringCapture)
            is ActiveEditorSourceCaptureResult.InvalidCaretOffset ->
                return failed(XmlActionSourceCaptureFailure.InvalidCaretOffset)
        }

        val documentManager = PsiDocumentManager.getInstance(project)
        documentManager.commitDocument(editor.document)

        val after = when (val capture = ActiveEditorSourceSnapshotAdapter.capture(editor)) {
            is ActiveEditorSourceCaptureResult.Captured -> capture.capture
            ActiveEditorSourceCaptureResult.MissingVirtualFile ->
                return failed(XmlActionSourceCaptureFailure.MissingVirtualFile)
            ActiveEditorSourceCaptureResult.SourceChangedDuringCapture ->
                return failed(XmlActionSourceCaptureFailure.SourceChangedDuringCapture)
            is ActiveEditorSourceCaptureResult.InvalidCaretOffset ->
                return failed(XmlActionSourceCaptureFailure.InvalidCaretOffset)
        }
        if (before != after) {
            return failed(XmlActionSourceCaptureFailure.SourceChangedDuringCapture)
        }

        val psiFile = documentManager.getPsiFile(editor.document) as? XmlFile
            ?: return failed(XmlActionSourceCaptureFailure.UnsupportedSourceLanguage)
        if (psiFile.text != after.snapshot.content) {
            return failed(XmlActionSourceCaptureFailure.SourcePsiMismatch)
        }

        val statementTag = statementTagAtCaret(psiFile, after.caretOffset)
            ?: return failed(XmlActionSourceCaptureFailure.StatementUnavailable)
        val mapperTag = statementTag.parentTag
            ?.takeIf { it.name == "mapper" }
            ?: return failed(XmlActionSourceCaptureFailure.StatementUnavailable)
        val namespace = mapperTag.getAttributeValue("namespace")
            ?.takeIf(String::isNotBlank)
            ?: return failed(XmlActionSourceCaptureFailure.MapperNamespaceUnavailable)
        val statementId = statementTag.getAttributeValue("id")
            ?.takeIf(String::isNotBlank)
            ?: return failed(XmlActionSourceCaptureFailure.StatementUnavailable)

        val discovery = when (val result = XmlMapperSourceDiscovery.discover(after.snapshot)) {
            is XmlMapperDiscoveryResult.Discovered -> result.mapper
            is XmlMapperDiscoveryResult.Failed ->
                return failed(XmlActionSourceCaptureFailure.DiscoveryFailed(result.reason))
        }
        if (discovery.namespace != namespace) {
            return failed(XmlActionSourceCaptureFailure.SourcePsiMismatch)
        }

        val declaration = discovery.statements.singleOrNull { declaration ->
            declaration.id == statementId &&
                declaration.sourceRange.startOffset == statementTag.textRange.startOffset
        } ?: return failed(XmlActionSourceCaptureFailure.SourcePsiMismatch)

        if (declaration.sourceRange.endOffsetExclusive > after.snapshot.content.length) {
            return failed(XmlActionSourceCaptureFailure.SourcePsiMismatch)
        }

        return XmlActionSourceCaptureResult.Captured(
            XmlActionSourceCapture(
                snapshot = after.snapshot,
                discovery = discovery,
                statementId = XmlStatementId(
                    sourceFileId = after.snapshot.fileId,
                    namespace = namespace,
                    statementId = statementId,
                ),
            ),
        )
    }

    private fun statementTagAtCaret(
        file: XmlFile,
        caretOffset: Int,
    ): XmlTag? {
        if (file.textLength == 0) return null
        val effectiveOffset = if (caretOffset == file.textLength) caretOffset - 1 else caretOffset
        if (effectiveOffset !in 0 until file.textLength) return null

        val element = file.findElementAt(effectiveOffset) ?: return null
        var tag = PsiTreeUtil.getParentOfType(element, XmlTag::class.java, false)
        while (tag != null) {
            if (tag.name in statementTagNames && tag.parentTag?.name == "mapper") {
                return tag
            }
            tag = tag.parentTag
        }
        return null
    }

    private fun failed(failure: XmlActionSourceCaptureFailure): XmlActionSourceCaptureResult =
        XmlActionSourceCaptureResult.Failed(failure)

    private val statementTagNames = setOf("select", "insert", "update", "delete")
}
