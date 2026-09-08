package com.algorist.zMyBatis.source

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.util.ThrowableRunnable
import java.nio.file.Files
import java.nio.file.Path

class ActiveEditorSourceSnapshotAdapterProjectFixtureTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getTempDirFixture(): TempDirTestFixture =
        IdeaTestFixtureFactory.getFixtureFactory().createTempDirTestFixture()

    fun testUnsavedDocumentIsAuthoritativeWithoutPsiCommitOrDiskSave() {
        val savedSql = "SELECT saved"
        val draftSql = "SELECT draft"
        val createdMapper = myFixture.addFileToProject(
            "fixture/UserMapper.java",
            """
            package fixture;

            class UserMapper {
                String statement = "$savedSql";
            }
            """.trimIndent(),
        )
        val virtualFile = createdMapper.virtualFile
        val backingPath = Path.of(virtualFile.path)

        assertEquals("file", virtualFile.fileSystem.protocol)
        assertTrue(Files.isRegularFile(backingPath))
        assertTrue(Files.readString(backingPath).contains(savedSql))

        myFixture.configureFromExistingVirtualFile(virtualFile)
        val editor = myFixture.editor
        val document = editor.document
        val psiDocumentManager = PsiDocumentManager.getInstance(project)
        val fileDocumentManager = FileDocumentManager.getInstance()

        assertSame(virtualFile, fileDocumentManager.getFile(document))
        assertFalse(fileDocumentManager.isDocumentUnsaved(document))
        assertTrue(psiDocumentManager.isCommitted(document))

        val savedCapture = captured(ActiveEditorSourceSnapshotAdapter.capture(editor))
        assertEquals("vfs:${virtualFile.url}", savedCapture.snapshot.fileId.value)
        assertEquals(document.text, savedCapture.snapshot.content)
        assertEquals(editor.caretModel.offset, savedCapture.caretOffset)

        val sqlOffset = document.text.indexOf(savedSql)
        assertTrue(sqlOffset >= 0)
        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(sqlOffset, sqlOffset + savedSql.length, draftSql)
        }
        editor.caretModel.moveToOffset(sqlOffset + draftSql.length)

        assertTrue(fileDocumentManager.isDocumentUnsaved(document))
        assertFalse(psiDocumentManager.isCommitted(document))
        assertTrue(document.text.contains(draftSql))
        assertTrue(psiDocumentManager.getLastCommittedText(document).contains(savedSql))
        assertTrue(Files.readString(backingPath).contains(savedSql))
        assertFalse(Files.readString(backingPath).contains(draftSql))

        val draftCapture = captured(ActiveEditorSourceSnapshotAdapter.capture(editor))

        assertEquals(savedCapture.snapshot.fileId, draftCapture.snapshot.fileId)
        assertFalse(
            "unsaved Document mutation must change revision evidence",
            savedCapture.snapshot.revision == draftCapture.snapshot.revision,
        )
        assertEquals(document.text, draftCapture.snapshot.content)
        assertTrue(draftCapture.snapshot.content.contains(draftSql))
        assertEquals(editor.caretModel.offset, draftCapture.caretOffset)

        assertFalse(
            "snapshot capture must not commit PSI when source text capture needs no PSI",
            psiDocumentManager.isCommitted(document),
        )
        assertTrue(
            "snapshot capture must leave the edited Document unsaved",
            fileDocumentManager.isDocumentUnsaved(document),
        )
        assertTrue(
            "snapshot capture must not persist draft text to the physical file",
            Files.readString(backingPath).contains(savedSql),
        )
        assertFalse(
            "snapshot capture must not write draft text to the physical file",
            Files.readString(backingPath).contains(draftSql),
        )
        assertTrue(
            "last committed PSI text must remain on saved text after snapshot capture",
            psiDocumentManager.getLastCommittedText(document).contains(savedSql),
        )
    }

    fun testCaptureFailsClosedWhenDocumentHasNoVirtualFile() {
        var result: ActiveEditorSourceCaptureResult? = null

        EdtTestUtil.runInEdtAndWait(
            ThrowableRunnable<Throwable> {
                val editorFactory = EditorFactory.getInstance()
                val editor = editorFactory.createEditor(editorFactory.createDocument("SELECT 1"), project)
                try {
                    result = ActiveEditorSourceSnapshotAdapter.capture(editor)
                } finally {
                    editorFactory.releaseEditor(editor)
                }
            },
        )

        assertSame(ActiveEditorSourceCaptureResult.MissingVirtualFile, result)
    }

    fun testCapturedValueRetainsNoIntellijPlatformObjects() {
        val retainedFieldTypes = ActiveEditorSourceCapture::class.java.declaredFields.map { it.type.name }

        assertTrue(
            "host capture may retain only immutable core/JDK values",
            retainedFieldTypes.none { it.startsWith("com.intellij.") },
        )
    }

    private fun captured(result: ActiveEditorSourceCaptureResult): ActiveEditorSourceCapture =
        when (result) {
            is ActiveEditorSourceCaptureResult.Captured -> result.capture
            else -> throw AssertionError("expected captured source but got $result")
        }
}
