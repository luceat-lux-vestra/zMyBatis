package com.algorist.zMyBatis.source

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class DependentMapperSourceSnapshotAdapterProjectFixtureTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getTempDirFixture(): TempDirTestFixture =
        IdeaTestFixtureFactory.getFixtureFactory().createTempDirTestFixture()

    fun testLoadedUnsavedDependentDocumentWinsOverVfsWithoutPsiCommitOrDiskSave() {
        val savedSql = "SELECT saved"
        val draftSql = "SELECT draft"
        val createdMapper = myFixture.addFileToProject(
            "fixture/DependentMapper.xml",
            """
            <mapper namespace="fixture.DependentMapper">
                <sql id="fragment">$savedSql</sql>
            </mapper>
            """.trimIndent(),
        )
        val virtualFile = createdMapper.virtualFile
        val backingPath = Path.of(virtualFile.path)
        val fileDocumentManager = FileDocumentManager.getInstance()

        assertTrue(Files.isRegularFile(backingPath))
        assertTrue(Files.readString(backingPath).contains(savedSql))

        myFixture.configureFromExistingVirtualFile(virtualFile)
        val document = myFixture.editor.document
        val psiDocumentManager = com.intellij.psi.PsiDocumentManager.getInstance(project)

        assertSame(virtualFile, fileDocumentManager.getFile(document))
        assertFalse(fileDocumentManager.isDocumentUnsaved(document))
        assertTrue(psiDocumentManager.isCommitted(document))

        val sqlOffset = document.text.indexOf(savedSql)
        assertTrue(sqlOffset >= 0)
        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(sqlOffset, sqlOffset + savedSql.length, draftSql)
        }

        assertTrue(fileDocumentManager.isDocumentUnsaved(document))
        assertFalse(psiDocumentManager.isCommitted(document))
        assertTrue(Files.readString(backingPath).contains(savedSql))
        assertFalse(Files.readString(backingPath).contains(draftSql))

        val snapshot = captured(
            DependentMapperSourceSnapshotAdapter.capture(
                virtualFile = virtualFile,
                maxContentLength = 4096,
            ),
        )

        assertEquals("vfs:${virtualFile.url}", snapshot.fileId.value)
        assertTrue(snapshot.revision.value.startsWith("document:"))
        assertTrue(snapshot.content.contains(draftSql))
        assertFalse(snapshot.content.contains(savedSql))
        assertTrue(fileDocumentManager.isDocumentUnsaved(document))
        assertFalse(
            "dependent capture must not commit PSI",
            psiDocumentManager.isCommitted(document),
        )
        assertTrue(
            "dependent capture must not save the physical file",
            Files.readString(backingPath).contains(savedSql),
        )
        assertFalse(Files.readString(backingPath).contains(draftSql))
    }

    fun testUnloadedVfsFallbackIsBoundedAndSharesIdentityWithLaterDocumentCapture() {
        val content = "<mapper namespace=\"fixture.DependentMapper\"><sql id=\"fragment\">SELECT 1</sql></mapper>"
        val virtualFile = LightVirtualFile("DependentMapper.xml", content)
        val fileDocumentManager = FileDocumentManager.getInstance()

        assertNull(fileDocumentManager.getCachedDocument(virtualFile))

        val vfsSnapshot = captured(
            DependentMapperSourceSnapshotAdapter.capture(
                virtualFile = virtualFile,
                maxContentLength = content.length,
            ),
        )

        assertEquals(content, vfsSnapshot.content)
        assertEquals("vfs:${virtualFile.url}", vfsSnapshot.fileId.value)
        assertTrue(vfsSnapshot.revision.value.startsWith("vfs:"))

        val document = fileDocumentManager.getDocument(virtualFile)
        assertNotNull(document)
        assertSame(document, fileDocumentManager.getCachedDocument(virtualFile))

        val documentSnapshot = captured(
            DependentMapperSourceSnapshotAdapter.capture(
                virtualFile = virtualFile,
                maxContentLength = content.length,
            ),
        )

        assertEquals(vfsSnapshot.fileId, documentSnapshot.fileId)
        assertEquals(content, documentSnapshot.content)
        assertTrue(documentSnapshot.revision.value.startsWith("document:"))
    }

    fun testContentBeyondExplicitBoundFailsClosedForVfsAndLoadedDocument() {
        val content = "0123456789"
        val virtualFile = LightVirtualFile("LargeDependentMapper.xml", content)
        val fileDocumentManager = FileDocumentManager.getInstance()

        assertNull(fileDocumentManager.getCachedDocument(virtualFile))
        assertEquals(
            DependentMapperSourceCaptureResult.ContentTooLarge(5),
            DependentMapperSourceSnapshotAdapter.capture(virtualFile, 5),
        )

        assertNotNull(fileDocumentManager.getDocument(virtualFile))
        assertEquals(
            DependentMapperSourceCaptureResult.ContentTooLarge(5),
            DependentMapperSourceSnapshotAdapter.capture(virtualFile, 5),
        )
    }

    fun testDirectoryAndBinaryInputsFailClosedAsInvalidSource() {
        val createdMapper = myFixture.addFileToProject(
            "fixture/InvalidSourceMapper.xml",
            "<mapper namespace=\"fixture.InvalidSourceMapper\" />",
        )
        val directory = createdMapper.virtualFile.parent
        val binaryFile = LightVirtualFile("CompiledMapper.class", "not-real-bytecode")

        assertTrue(directory.isDirectory)
        assertSame(
            DependentMapperSourceCaptureResult.InvalidSource,
            DependentMapperSourceSnapshotAdapter.capture(directory, 1024),
        )
        assertTrue(".class fixture must resolve to a binary IntelliJ file type", binaryFile.fileType.isBinary)
        assertSame(
            DependentMapperSourceCaptureResult.InvalidSource,
            DependentMapperSourceSnapshotAdapter.capture(binaryFile, 1024),
        )
    }

    fun testUnreadableVfsFallbackFailsClosed() {
        val virtualFile = LightVirtualFile("UnreadableDependentMapper.xml", "<mapper />")
        val fileDocumentManager = FileDocumentManager.getInstance()

        assertNull(fileDocumentManager.getCachedDocument(virtualFile))
        val result = DependentMapperSourceSnapshotAdapter.capture(
            virtualFile = virtualFile,
            maxContentLength = 1024,
            fileDocumentManager = fileDocumentManager,
            vfsTextLoader = { _, _ -> throw IOException("synthetic unreadable source") },
        )

        assertSame(DependentMapperSourceCaptureResult.UnreadableSource, result)
    }

    fun testVfsRevisionChangeDuringCaptureFailsClosed() {
        val initialContent = "<mapper><sql id=\"fragment\">SELECT 1</sql></mapper>"
        val changedContent = "<mapper><sql id=\"fragment\">SELECT 2</sql></mapper>"
        val virtualFile = LightVirtualFile("RacingDependentMapper.xml", initialContent)
        val fileDocumentManager = FileDocumentManager.getInstance()

        assertNull(fileDocumentManager.getCachedDocument(virtualFile))
        val result = DependentMapperSourceSnapshotAdapter.capture(
            virtualFile = virtualFile,
            maxContentLength = 4096,
            fileDocumentManager = fileDocumentManager,
            vfsTextLoader = { candidate, limit ->
                val capturedText = VfsUtilCore.loadText(candidate, limit)
                val nextModificationStamp = candidate.modificationStamp + 1
                WriteCommandAction.runWriteCommandAction(project) {
                    candidate.setBinaryContent(
                        changedContent.toByteArray(candidate.charset),
                        nextModificationStamp,
                        -1,
                    )
                }
                capturedText
            },
        )

        assertSame(DependentMapperSourceCaptureResult.SourceChangedDuringCapture, result)
    }

    fun testCapturedResultRetainsNoIntellijPlatformObjects() {
        val retainedFieldTypes = DependentMapperSourceCaptureResult.Captured::class.java.declaredFields.map { it.type.name }

        assertTrue(
            "dependent capture may retain only immutable core/JDK values",
            retainedFieldTypes.none { it.startsWith("com.intellij.") },
        )
    }

    private fun captured(result: DependentMapperSourceCaptureResult) =
        when (result) {
            is DependentMapperSourceCaptureResult.Captured -> result.snapshot
            else -> throw AssertionError("expected captured dependent source but got $result")
        }
}
