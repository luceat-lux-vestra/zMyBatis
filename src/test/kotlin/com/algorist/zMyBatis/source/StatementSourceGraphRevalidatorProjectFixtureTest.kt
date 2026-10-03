package com.algorist.zMyBatis.source

import com.algorist.zMyBatis.core.source.CapturedStatement
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.StatementSourceGraph
import com.algorist.zMyBatis.core.source.XmlStatementId
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture
import java.nio.file.Files
import java.nio.file.Path

class StatementSourceGraphRevalidatorProjectFixtureTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getTempDirFixture(): TempDirTestFixture =
        IdeaTestFixtureFactory.getFixtureFactory().createTempDirTestFixture()

    fun testUnchangedUnsavedDocumentRevalidatesWithoutPsiCommitOrDiskSave() {
        val savedSql = "SELECT saved"
        val draftSql = "SELECT draft"
        val mapper = myFixture.addFileToProject(
            "fixture/ActiveMapper.xml",
            """
            <mapper namespace="fixture.ActiveMapper">
                <select id="find">$savedSql</select>
            </mapper>
            """.trimIndent(),
        )
        val virtualFile = mapper.virtualFile
        val backingPath = Path.of(virtualFile.path)

        myFixture.configureFromExistingVirtualFile(virtualFile)
        val document = myFixture.editor.document
        val fileDocumentManager = FileDocumentManager.getInstance()
        val psiDocumentManager = PsiDocumentManager.getInstance(project)
        val offset = document.text.indexOf(savedSql)
        assertTrue(offset >= 0)

        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(offset, offset + savedSql.length, draftSql)
        }

        assertTrue(fileDocumentManager.isDocumentUnsaved(document))
        assertFalse(psiDocumentManager.isCommitted(document))
        val snapshot = activeSnapshot()

        assertSame(
            SourceGraphRevalidationResult.Current,
            StatementSourceGraphRevalidator.revalidate(graph(snapshot)),
        )
        assertTrue(fileDocumentManager.isDocumentUnsaved(document))
        assertFalse(psiDocumentManager.isCommitted(document))
        assertTrue(Files.readString(backingPath).contains(savedSql))
        assertFalse(Files.readString(backingPath).contains(draftSql))
    }

    fun testDocumentEditThenRevertRemainsStaleByRevision() {
        val mapper = myFixture.addFileToProject(
            "fixture/RevertedMapper.xml",
            "<mapper namespace=\"fixture.RevertedMapper\"><select id=\"find\">SELECT 1</select></mapper>",
        )
        myFixture.configureFromExistingVirtualFile(mapper.virtualFile)
        val document = myFixture.editor.document
        val original = document.text
        val snapshot = activeSnapshot()

        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(0, " ")
            document.deleteString(0, 1)
        }

        assertEquals(original, document.text)
        val failure = failed(StatementSourceGraphRevalidator.revalidate(graph(snapshot)))
        assertTrue(failure is SourceGraphRevalidationFailure.SourceChanged)
        failure as SourceGraphRevalidationFailure.SourceChanged
        assertEquals(snapshot.revision, failure.expectedRevision)
        assertTrue(failure.actualRevision.value.startsWith("document:"))
        assertFalse(snapshot.revision == failure.actualRevision)
    }

    fun testVfsSnapshotSurvivesSavedDocumentLoadButUnsavedEditIsStale() {
        val content = "<mapper namespace=\"fixture.VfsMapper\"><select id=\"find\">SELECT 1</select></mapper>"
        val virtualFile = myFixture.tempDirFixture.createFile("fixture/VfsMapper.xml", content)
        val fileDocumentManager = FileDocumentManager.getInstance()
        assertNull(fileDocumentManager.getCachedDocument(virtualFile))

        val snapshot = dependentSnapshot(virtualFile)
        assertTrue(snapshot.revision.value.startsWith("vfs:"))
        assertSame(
            SourceGraphRevalidationResult.Current,
            StatementSourceGraphRevalidator.revalidate(graph(snapshot)),
        )
        assertNull(
            "revalidation must not load a document for a VFS-authority snapshot",
            fileDocumentManager.getCachedDocument(virtualFile),
        )

        val document = fileDocumentManager.getDocument(virtualFile)
        assertNotNull(document)
        assertFalse(fileDocumentManager.isDocumentUnsaved(document!!))
        assertSame(
            SourceGraphRevalidationResult.Current,
            StatementSourceGraphRevalidator.revalidate(graph(snapshot)),
        )

        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(document.textLength, " ")
        }
        assertTrue(fileDocumentManager.isDocumentUnsaved(document))

        val failure = failed(StatementSourceGraphRevalidator.revalidate(graph(snapshot)))
        assertTrue(failure is SourceGraphRevalidationFailure.SourceChanged)
        failure as SourceGraphRevalidationFailure.SourceChanged
        assertEquals(snapshot.revision, failure.expectedRevision)
        assertTrue(failure.actualRevision.value.startsWith("document:"))
    }

    fun testRenamedCapturedSourceFailsClosedByOldIdentity() {
        val content = "<mapper namespace=\"fixture.RenameMapper\"><select id=\"find\">SELECT 1</select></mapper>"
        val virtualFile = myFixture.tempDirFixture.createFile("fixture/RenameMapper.xml", content)
        val snapshot = dependentSnapshot(virtualFile)
        val oldFileId = snapshot.fileId

        WriteCommandAction.runWriteCommandAction(project) {
            virtualFile.rename(this, "RenamedMapper.xml")
        }

        assertFalse(oldFileId.value == "vfs:" + virtualFile.url)
        val failure = failed(StatementSourceGraphRevalidator.revalidate(graph(snapshot)))
        assertEquals(oldFileId, failure.sourceFileId)
        assertTrue(
            failure is SourceGraphRevalidationFailure.SourceMissing ||
                failure is SourceGraphRevalidationFailure.InvalidSource,
        )
    }

    fun testUnsupportedIdentityAndRevisionFailTyped() {
        val unsupportedIdentity = SourceSnapshot(
            fileId = SourceFileId("memory:mapper"),
            revision = SourceRevision("document:1"),
            content = "SELECT 1",
        )
        assertTrue(
            failed(StatementSourceGraphRevalidator.revalidate(graph(unsupportedIdentity))) is
                SourceGraphRevalidationFailure.UnsupportedSourceIdentity,
        )

        val virtualFile = myFixture.tempDirFixture.createFile(
            "fixture/UnsupportedRevisionMapper.xml",
            "<mapper namespace=\"fixture.UnsupportedRevisionMapper\" />",
        )
        val captured = dependentSnapshot(virtualFile)
        val unsupportedRevision = captured.copy(revision = SourceRevision("hash:abc"))

        val failure = failed(StatementSourceGraphRevalidator.revalidate(graph(unsupportedRevision)))
        assertTrue(failure is SourceGraphRevalidationFailure.UnsupportedRevision)
        assertEquals(unsupportedRevision.fileId, failure.sourceFileId)
    }

    fun testMultiSourceFailureOrderingIsDeterministicBySourceIdentity() {
        val first = SourceSnapshot(
            fileId = SourceFileId("memory:a"),
            revision = SourceRevision("document:1"),
            content = "A",
        )
        val root = SourceSnapshot(
            fileId = SourceFileId("memory:z"),
            revision = SourceRevision("document:1"),
            content = "Z",
        )
        val graph = StatementSourceGraph(
            rootStatement = CapturedStatement(
                id = XmlStatementId(root.fileId, "fixture.Mapper", "find"),
                kind = StatementKind.SELECT,
                sourceRange = SourceRange(0, 1),
            ),
            sourceSnapshots = listOf(root, first),
            dependencies = emptyList(),
        )

        val failure = failed(StatementSourceGraphRevalidator.revalidate(graph))
        assertTrue(failure is SourceGraphRevalidationFailure.UnsupportedSourceIdentity)
        assertEquals(first.fileId, failure.sourceFileId)
    }

    private fun activeSnapshot(): SourceSnapshot =
        when (val result = ActiveEditorSourceSnapshotAdapter.capture(myFixture.editor)) {
            is ActiveEditorSourceCaptureResult.Captured -> result.capture.snapshot
            else -> throw AssertionError("expected active source capture but got $result")
        }

    private fun dependentSnapshot(virtualFile: com.intellij.openapi.vfs.VirtualFile): SourceSnapshot =
        when (
            val result = DependentMapperSourceSnapshotAdapter.capture(
                virtualFile = virtualFile,
                maxContentLength = 4096,
            )
        ) {
            is DependentMapperSourceCaptureResult.Captured -> result.snapshot
            else -> throw AssertionError("expected dependent source capture but got $result")
        }

    private fun graph(snapshot: SourceSnapshot): StatementSourceGraph =
        StatementSourceGraph(
            rootStatement = CapturedStatement(
                id = XmlStatementId(snapshot.fileId, "fixture.Mapper", "find"),
                kind = StatementKind.SELECT,
                sourceRange = SourceRange(0, snapshot.content.length),
            ),
            sourceSnapshots = listOf(snapshot),
            dependencies = emptyList(),
        )

    private fun failed(result: SourceGraphRevalidationResult): SourceGraphRevalidationFailure =
        when (result) {
            is SourceGraphRevalidationResult.Failed -> result.failure
            SourceGraphRevalidationResult.Current -> throw AssertionError("expected revalidation failure")
        }
}
