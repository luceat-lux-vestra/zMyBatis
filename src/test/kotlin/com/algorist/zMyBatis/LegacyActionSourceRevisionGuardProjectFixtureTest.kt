package com.algorist.zMyBatis

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture

class LegacyActionSourceRevisionGuardProjectFixtureTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getTempDirFixture(): TempDirTestFixture =
        IdeaTestFixtureFactory.getFixtureFactory().createTempDirTestFixture()

    fun testCommittedSourceRemainsCurrentUntilDocumentRevisionChanges() {
        val created = myFixture.addFileToProject(
            "fixture/Mapper.java",
            """
            class Mapper {
                String sql = "SELECT 1";
            }
            """.trimIndent(),
        ) as PsiJavaFile
        myFixture.configureFromExistingVirtualFile(created.virtualFile)
        val psiFile = myFixture.file as PsiJavaFile
        val editor = myFixture.editor
        val document = editor.document
        val documentManager = PsiDocumentManager.getInstance(project)

        assertTrue(documentManager.isCommitted(document))
        val capture = LegacyActionSourceRevisionGuard.capture(project, editor, psiFile)
        assertTrue(capture is LegacyActionSourceRevisionCaptureResult.Captured)
        val revision = (capture as LegacyActionSourceRevisionCaptureResult.Captured).revision
        assertTrue(LegacyActionSourceRevisionGuard.isCurrent(project, revision))

        val original = document.text
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(original.replace("SELECT 1", "SELECT 2"))
        }

        assertFalse(documentManager.isCommitted(document))
        assertFalse(LegacyActionSourceRevisionGuard.isCurrent(project, revision))

        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(original)
        }

        assertEquals("edit-then-revert must restore text for the fixture", original, document.text)
        assertFalse(
            "revision authority must remain stale even when the visible content is reverted",
            LegacyActionSourceRevisionGuard.isCurrent(project, revision),
        )
    }

    fun testUncommittedLegacyPsiSourceFailsClosedUntilExplicitCommit() {
        val created = myFixture.addFileToProject(
            "fixture/Mapper.java",
            """
            class Mapper {
                String sql = "SELECT saved";
            }
            """.trimIndent(),
        ) as PsiJavaFile
        myFixture.configureFromExistingVirtualFile(created.virtualFile)
        val psiFile = myFixture.file as PsiJavaFile
        val editor = myFixture.editor
        val document = editor.document
        val documentManager = PsiDocumentManager.getInstance(project)

        WriteCommandAction.runWriteCommandAction(project) {
            val start = document.text.indexOf("SELECT saved")
            document.replaceString(start, start + "SELECT saved".length, "SELECT draft")
        }

        assertFalse(documentManager.isCommitted(document))
        assertTrue(
            LegacyActionSourceRevisionGuard.capture(project, editor, psiFile) ===
                LegacyActionSourceRevisionCaptureResult.UncommittedSource,
        )

        documentManager.commitDocument(document)

        val recaptured = LegacyActionSourceRevisionGuard.capture(project, editor, psiFile)
        assertTrue(recaptured is LegacyActionSourceRevisionCaptureResult.Captured)
        assertTrue(
            LegacyActionSourceRevisionGuard.isCurrent(
                project,
                (recaptured as LegacyActionSourceRevisionCaptureResult.Captured).revision,
            ),
        )
    }
}
