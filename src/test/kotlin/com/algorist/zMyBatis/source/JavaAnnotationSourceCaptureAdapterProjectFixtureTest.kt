package com.algorist.zMyBatis.source

import com.algorist.zMyBatis.core.source.JavaAnnotationStatementCapture
import com.algorist.zMyBatis.core.source.JavaStatementId
import com.algorist.zMyBatis.core.source.StatementKind
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.util.PathUtil
import org.apache.ibatis.annotations.Select
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class JavaAnnotationSourceCaptureAdapterProjectFixtureTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor() = JAVA_21

    override fun setUp() {
        super.setUp()
        val myBatisJar = File(PathUtil.getJarPathForClass(Select::class.java))
        PsiTestUtil.addLibrary(module, "mybatis-3.5.19", myBatisJar.parent, myBatisJar.name)
    }

    override fun getTempDirFixture(): TempDirTestFixture =
        IdeaTestFixtureFactory.getFixtureFactory().createTempDirTestFixture()

    fun testSavedCapturePreservesCanonicalOverloadParameterMetadataAndOrderedSegments() {
        myFixture.addClass(
            """
            package fixture;

            public final class SqlConstants {
                public static final String HEAD = "SELECT id, name FROM users";
                public static final String ACTIVE = " AND active = 1";
                private SqlConstants() {}
            }
            """.trimIndent(),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val mapperFile = myFixture.configureByText(
            JavaFileType.INSTANCE,
            """
            package fixture;

            import org.apache.ibatis.annotations.Param;
            import org.apache.ibatis.annotations.Select;

            interface UserMapper {
                @Select({SqlConstants.HEAD, " WHERE id = #{id}", SqlConstants.ACTIVE})
                Object find(@Param("userId") int id, String[] tags);

                @Select("SELECT id FROM users WHERE name = #{name}")
                Object find(String name);
            }
            """.trimIndent(),
        )
        val firstMarker = "find(@Param(\"userId\") int id"
        moveCaretTo(mapperFile.text, firstMarker, "find")

        val capture = captured(JavaAnnotationSourceCaptureAdapter.capture(project, myFixture.editor))
        val statement = capture.sourceGraph.rootStatement
        val statementId = statement.id as JavaStatementId

        assertEquals("fixture.UserMapper", statementId.qualifiedMapperType)
        assertEquals("find(int,java.lang.String[])", statementId.methodSignature.toString())
        assertEquals(StatementKind.SELECT, statement.kind)
        assertEquals(
            listOf(
                "SELECT id, name FROM users",
                " WHERE id = #{id}",
                " AND active = 1",
            ),
            capture.sqlSegments,
        )
        assertEquals(2, capture.parameters.size)
        assertEquals(0, capture.parameters[0].index)
        assertEquals("id", capture.parameters[0].sourceName)
        assertEquals("int", capture.parameters[0].typeIdentity.value)
        assertEquals("userId", capture.parameters[0].myBatisParamAlias)
        assertEquals(1, capture.parameters[1].index)
        assertEquals("tags", capture.parameters[1].sourceName)
        assertEquals("java.lang.String[]", capture.parameters[1].typeIdentity.value)
        assertNull(capture.parameters[1].myBatisParamAlias)

        assertEquals(
            "cross-file constants must be captured as source dependencies",
            2,
            capture.sourceGraph.sourceSnapshots.size,
        )
        assertEquals(2, capture.sourceGraph.dependencies.size)
        assertTrue(
            capture.sourceGraph.dependencies.all {
                it.dependentFileId == statementId.sourceFileId &&
                    it.requiredFileId != statementId.sourceFileId
            },
        )
        assertTrue(
            "repeated references into the same constants file must remain occurrence-aware",
            capture.sourceGraph.dependencies.map { it.referenceRange }.toSet().size == 2,
        )

        val rootSnapshot = capture.sourceGraph.sourceSnapshots.single { it.fileId == statementId.sourceFileId }
        val methodText = rootSnapshot.content.substring(
            statement.sourceRange.startOffset,
            statement.sourceRange.endOffsetExclusive,
        )
        assertTrue(methodText.contains("@Select"))
        assertTrue(methodText.contains("Object find(@Param(\"userId\") int id, String[] tags)"))
    }

    fun testUnsavedActiveDocumentIsSemanticAuthorityWithoutDiskSave() {
        val savedSql = "SELECT saved"
        val draftSql = "SELECT draft"
        val createdMapper = myFixture.addFileToProject(
            "fixture/UnsavedMapper.java",
            """
            package fixture;

            import org.apache.ibatis.annotations.Select;

            interface UnsavedMapper {
                @Select("$savedSql")
                Object find(int id);
            }
            """.trimIndent(),
        )
        val virtualFile = createdMapper.virtualFile
        val backingPath = Path.of(virtualFile.path)
        assertTrue(Files.readString(backingPath).contains(savedSql))

        myFixture.configureFromExistingVirtualFile(virtualFile)
        val editor = myFixture.editor
        val document = editor.document
        val documentManager = PsiDocumentManager.getInstance(project)
        val fileDocumentManager = FileDocumentManager.getInstance()
        val sqlOffset = document.text.indexOf(savedSql)
        assertTrue(sqlOffset >= 0)

        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(sqlOffset, sqlOffset + savedSql.length, draftSql)
        }
        moveCaretTo(document.text, "find(int id)", "find")

        assertFalse(documentManager.isCommitted(document))
        assertTrue(fileDocumentManager.isDocumentUnsaved(document))
        assertTrue(Files.readString(backingPath).contains(savedSql))
        assertFalse(Files.readString(backingPath).contains(draftSql))

        val capture = captured(JavaAnnotationSourceCaptureAdapter.capture(project, editor))

        assertEquals(listOf(draftSql), capture.sqlSegments)
        assertTrue(
            "semantic capture must synchronize PSI to the authoritative active Document",
            documentManager.isCommitted(document),
        )
        assertTrue(
            "PSI synchronization must not save the edited file",
            fileDocumentManager.isDocumentUnsaved(document),
        )
        assertTrue(Files.readString(backingPath).contains(savedSql))
        assertFalse(Files.readString(backingPath).contains(draftSql))
        assertTrue(capture.sourceGraph.sourceSnapshots.single().content.contains(draftSql))
    }

    fun testConstantChainCapturesSameFileAndCrossFileDependencies() {
        myFixture.addClass(
            """
            package fixture;

            public final class SharedSql {
                public static final String BASE = "SELECT";
                private SharedSql() {}
            }
            """.trimIndent(),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val mapperFile = myFixture.configureByText(
            JavaFileType.INSTANCE,
            """
            package fixture;

            import org.apache.ibatis.annotations.Select;

            interface ChainedMapper {
                String SQL = SharedSql.BASE + " 1";

                @Select(SQL)
                Object find();
            }
            """.trimIndent(),
        )
        moveCaretTo(mapperFile.text, "find()", "find")

        val capture = captured(JavaAnnotationSourceCaptureAdapter.capture(project, myFixture.editor))
        val rootFileId = capture.sourceGraph.rootStatement.id.sourceFileId

        assertEquals(listOf("SELECT 1"), capture.sqlSegments)
        assertEquals(2, capture.sourceGraph.sourceSnapshots.size)
        assertEquals(2, capture.sourceGraph.dependencies.size)
        assertTrue(
            "the method annotation must retain its same-file SQL constant dependency",
            capture.sourceGraph.dependencies.any {
                it.dependentFileId == rootFileId && it.requiredFileId == rootFileId
            },
        )
        assertTrue(
            "the same-file SQL constant must retain its cross-file dependency",
            capture.sourceGraph.dependencies.any {
                it.dependentFileId == rootFileId && it.requiredFileId != rootFileId
            },
        )
    }

    fun testProviderAmbiguousLangAndUnresolvedValuesFailClosed() {
        myFixture.addClass(
            """
            package fixture;

            public final class Provider {
                public static String sql() { return "SELECT provider"; }
            }
            """.trimIndent(),
        )

        val mapperFile = myFixture.configureByText(
            JavaFileType.INSTANCE,
            """
            package fixture;

            import org.apache.ibatis.annotations.Lang;
            import org.apache.ibatis.annotations.Select;
            import org.apache.ibatis.annotations.SelectProvider;
            import org.apache.ibatis.annotations.Update;
            import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;

            interface FailureMapper {
                @SelectProvider(type = Provider.class, method = "sql")
                Object provider();

                @Select("SELECT 1")
                @Update("UPDATE users SET active = 1")
                Object ambiguous();

                @Lang(XMLLanguageDriver.class)
                @Select("SELECT 1")
                Object customLang();

                @Select(MissingSql.VALUE)
                Object unresolved();
            }
            """.trimIndent(),
        )

        assertFailure(
            mapperFile.text,
            "provider()",
            JavaAnnotationSourceCaptureFailure.PROVIDER_ANNOTATION,
        )
        assertFailure(
            mapperFile.text,
            "ambiguous()",
            JavaAnnotationSourceCaptureFailure.AMBIGUOUS_STATEMENT_ANNOTATION,
        )
        assertFailure(
            mapperFile.text,
            "customLang()",
            JavaAnnotationSourceCaptureFailure.UNSUPPORTED_LANGUAGE_DRIVER,
        )
        assertFailure(
            mapperFile.text,
            "unresolved()",
            JavaAnnotationSourceCaptureFailure.UNRESOLVED_ANNOTATION_VALUE,
        )
    }

    fun testCapturedModelRetainsNoIntellijPlatformObjects() {
        val captureFieldTypes = JavaAnnotationStatementCapture::class.java.declaredFields.map { it.type.name }
        val parameterFieldTypes = com.algorist.zMyBatis.core.source.JavaMethodParameterMetadata::class.java
            .declaredFields
            .map { it.type.name }

        assertTrue(captureFieldTypes.none { it.startsWith("com.intellij.") })
        assertTrue(parameterFieldTypes.none { it.startsWith("com.intellij.") })
    }

    private fun assertFailure(
        fileText: String,
        marker: String,
        expected: JavaAnnotationSourceCaptureFailure,
    ) {
        moveCaretTo(fileText, marker, marker.substringBefore('('))
        assertEquals(
            JavaAnnotationSourceCaptureResult.Failed(expected),
            JavaAnnotationSourceCaptureAdapter.capture(project, myFixture.editor),
        )
    }

    private fun captured(result: JavaAnnotationSourceCaptureResult): JavaAnnotationStatementCapture =
        when (result) {
            is JavaAnnotationSourceCaptureResult.Captured -> result.capture
            is JavaAnnotationSourceCaptureResult.Failed -> throw AssertionError("expected capture but got ${result.failure}")
        }

    private fun moveCaretTo(fileText: String, marker: String, token: String) {
        val offset = fileText.indexOf(marker)
        assertTrue("marker '$marker' must exist", offset >= 0)
        val tokenOffset = marker.indexOf(token)
        assertTrue("token '$token' must exist in marker '$marker'", tokenOffset >= 0)
        myFixture.editor.caretModel.moveToOffset(offset + tokenOffset)
    }
}
