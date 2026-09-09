package com.algorist.zMyBatis.source

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.CaretModel
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.util.ThrowableRunnable
import java.lang.reflect.Proxy
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

    fun testCaptureFailsClosedWhenSourceChangesDuringCapture() {
        val createdMapper = myFixture.addFileToProject(
            "fixture/RacingMapper.java",
            """
            package fixture;

            class RacingMapper {
                String statement = "SELECT 1";
            }
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(createdMapper.virtualFile)

        val editor = myFixture.editor
        val document = editor.document
        var mutatedDuringCapture = false
        val racingEditor = editorWithCaretOffset(editor) {
            if (!mutatedDuringCapture) {
                mutatedDuringCapture = true
                WriteCommandAction.runWriteCommandAction(project) {
                    document.insertString(document.textLength, " ")
                }
            }
            editor.caretModel.offset
        }

        val result = ActiveEditorSourceSnapshotAdapter.capture(racingEditor)

        assertTrue("test double must mutate the source during capture", mutatedDuringCapture)
        assertSame(ActiveEditorSourceCaptureResult.SourceChangedDuringCapture, result)
    }

    fun testCaptureFailsClosedWhenCaretFallsOutsideCapturedContent() {
        val createdMapper = myFixture.addFileToProject(
            "fixture/InvalidCaretMapper.java",
            """
            package fixture;

            class InvalidCaretMapper {
                String statement = "SELECT 1";
            }
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(createdMapper.virtualFile)

        val editor = myFixture.editor
        val contentLength = editor.document.textLength
        val invalidOffset = contentLength + 1
        val invalidCaretEditor = editorWithCaretOffset(editor) { invalidOffset }

        val result = ActiveEditorSourceSnapshotAdapter.capture(invalidCaretEditor)

        assertEquals(
            ActiveEditorSourceCaptureResult.InvalidCaretOffset(invalidOffset, contentLength),
            result,
        )
    }

    fun testCapturedValueRetainsNoIntellijPlatformObjects() {
        val retainedFieldTypes = ActiveEditorSourceCapture::class.java.declaredFields.map { it.type.name }

        assertTrue(
            "host capture may retain only immutable core/JDK values",
            retainedFieldTypes.none { it.startsWith("com.intellij.") },
        )
    }

    private fun editorWithCaretOffset(delegate: Editor, offsetProvider: () -> Int): Editor {
        val caretModel = Proxy.newProxyInstance(
            CaretModel::class.java.classLoader,
            arrayOf(CaretModel::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "getOffset" -> offsetProvider()
                "toString" -> "CaretModelTestDouble"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> throw AssertionError("unexpected CaretModel method: ${method.name}")
            }
        } as CaretModel

        return Proxy.newProxyInstance(
            Editor::class.java.classLoader,
            arrayOf(Editor::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "getDocument" -> delegate.document
                "getCaretModel" -> caretModel
                "toString" -> "EditorTestDouble"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> throw AssertionError("unexpected Editor method: ${method.name}")
            }
        } as Editor
    }

    private fun captured(result: ActiveEditorSourceCaptureResult): ActiveEditorSourceCapture =
        when (result) {
            is ActiveEditorSourceCaptureResult.Captured -> result.capture
            else -> throw AssertionError("expected captured source but got $result")
        }
}
