package com.algorist.zMyBatis

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture
import java.nio.file.Files
import java.nio.file.Path

class JavaActionContextDiskFixtureTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getTempDirFixture(): TempDirTestFixture =
        IdeaTestFixtureFactory.getFixtureFactory().createTempDirTestFixture()

    fun testPsiCommitCanLeadUnsavedBackingFileUntilExplicitDocumentSave() {
        val savedSql = "SELECT saved"
        val draftSql = "SELECT draft"
        val createdMapper = myFixture.addFileToProject(
            "fixture/UserMapper.java",
            """
            package fixture;

            class UserMapper {
                String statement = "$savedSql";
            }
            """.trimIndent()
        ) as PsiJavaFile
        val virtualFile = createdMapper.virtualFile
        val backingPath = Path.of(virtualFile.path)

        assertEquals(
            "fixture must use the local filesystem rather than the default in-memory light VFS",
            "file",
            virtualFile.fileSystem.protocol
        )
        assertTrue("mapper must have a real backing file", Files.isRegularFile(backingPath))
        assertTrue("backing file must initially contain saved SQL", Files.readString(backingPath).contains(savedSql))

        myFixture.configureFromExistingVirtualFile(virtualFile)
        val mapperFile = myFixture.file as PsiJavaFile
        val document = myFixture.editor.document
        val documentManager = PsiDocumentManager.getInstance(project)
        val fileDocumentManager = FileDocumentManager.getInstance()

        assertSame(
            "editor Document must map back to the disk-backed mapper VirtualFile",
            virtualFile,
            fileDocumentManager.getFile(document)
        )
        assertFalse("fresh disk-backed mapper must start saved", fileDocumentManager.isDocumentUnsaved(document))
        assertTrue("fixture must start from committed PSI", documentManager.isCommitted(document))
        assertTrue("committed PSI must initially contain saved SQL", mapperFile.text.contains(savedSql))

        val sqlOffset = document.text.indexOf(savedSql)
        assertTrue("saved SQL must exist in the editor document", sqlOffset >= 0)
        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(sqlOffset, sqlOffset + savedSql.length, draftSql)
        }

        assertTrue("editor edit must mark the disk-backed Document unsaved", fileDocumentManager.isDocumentUnsaved(document))
        assertFalse("editor edit must create an uncommitted Document/PSI boundary", documentManager.isCommitted(document))
        assertTrue("editor Document must contain draft SQL", document.text.contains(draftSql))
        assertTrue(
            "last-committed PSI snapshot must still contain saved SQL before explicit commit",
            documentManager.getLastCommittedText(document).contains(savedSql)
        )
        assertTrue(
            "physical backing file must remain on saved SQL before PSI commit",
            Files.readString(backingPath).contains(savedSql)
        )

        documentManager.commitDocument(document)

        assertTrue("explicit PSI commit must synchronize the Document into PSI", documentManager.isCommitted(document))
        assertTrue("committed PSI must now contain draft SQL", mapperFile.text.contains(draftSql))
        assertTrue(
            "PSI commit must not be mistaken for persistence to the backing file",
            fileDocumentManager.isDocumentUnsaved(document)
        )
        assertTrue(
            "physical backing file must still contain saved SQL after PSI commit but before document save",
            Files.readString(backingPath).contains(savedSql)
        )
        assertFalse(
            "physical backing file must not contain draft SQL before explicit document save",
            Files.readString(backingPath).contains(draftSql)
        )

        fileDocumentManager.saveDocument(document)

        assertFalse("explicit document save must clear the unsaved state", fileDocumentManager.isDocumentUnsaved(document))
        val persistedText = Files.readString(backingPath)
        assertTrue("explicit document save must persist draft SQL to the physical file", persistedText.contains(draftSql))
        assertFalse("physical file must no longer contain the replaced saved SQL", persistedText.contains(savedSql))
        assertTrue("saving must leave the already-committed PSI on draft SQL", mapperFile.text.contains(draftSql))
    }
}
