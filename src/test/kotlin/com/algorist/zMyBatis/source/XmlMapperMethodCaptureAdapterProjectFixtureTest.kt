package com.algorist.zMyBatis.source

import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.XmlStatementId
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.util.PathUtil
import org.apache.ibatis.annotations.Param
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class XmlMapperMethodCaptureAdapterProjectFixtureTest : LightJavaCodeInsightFixtureTestCase() {
    override fun getProjectDescriptor() = JAVA_21

    override fun setUp() {
        super.setUp()
        val tempRoot = myFixture.tempDirFixture.getFile("")
            ?: throw AssertionError("physical temp fixture root must exist")
        PsiTestUtil.addContentRoot(module, tempRoot)
        PsiTestUtil.addSourceRoot(module, tempRoot)

        val myBatisJar = File(PathUtil.getJarPathForClass(Param::class.java))
        PsiTestUtil.addLibrary(module, "mybatis-3.5.19", myBatisJar.parent, myBatisJar.name)
    }

    override fun getTempDirFixture(): TempDirTestFixture =
        IdeaTestFixtureFactory.getFixtureFactory().createTempDirTestFixture()

    fun testSavedInterfaceCapturePreservesExactMethodAndParameterMetadata() {
        myFixture.addFileToProject(
            "fixture/UserMapper.java",
            """
            package fixture;

            import org.apache.ibatis.annotations.Param;

            interface UserMapper {
                Object find(@Param("userId") int id, String name);
            }
            """.trimIndent(),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val capture = captured(
            XmlMapperMethodCaptureAdapter.capture(
                project,
                statementId("fixture.UserMapper", "find"),
            ),
        )

        assertEquals("fixture.UserMapper", capture.statementId.namespace)
        assertEquals("find", capture.statementId.statementId)
        assertTrue(capture.mapperSource.content.contains("interface UserMapper"))
        assertEquals(2, capture.parameters.size)
        assertEquals("id", capture.parameters[0].sourceName)
        assertEquals("int", capture.parameters[0].typeIdentity.value)
        assertEquals("userId", capture.parameters[0].myBatisParamAlias)
        assertEquals("name", capture.parameters[1].sourceName)
        assertEquals("java.lang.String", capture.parameters[1].typeIdentity.value)
        assertNull(capture.parameters[1].myBatisParamAlias)

        val methodText = capture.mapperSource.content.substring(
            capture.methodSourceRange.startOffset,
            capture.methodSourceRange.endOffsetExclusive,
        )
        assertEquals(
            "Object find(@Param(\"userId\") int id, String name);",
            methodText.trim(),
        )
    }

    fun testUnsavedLoadedDocumentIsAuthoritativeWithoutSavingJavaMapper() {
        val savedAlias = "savedId"
        val draftAlias = "draftId"
        val file = myFixture.addFileToProject(
            "fixture/UnsavedMapper.java",
            """
            package fixture;

            import org.apache.ibatis.annotations.Param;

            interface UnsavedMapper {
                Object find(@Param("$savedAlias") int id);
            }
            """.trimIndent(),
        )
        val virtualFile = file.virtualFile
        val backingPath = Path.of(virtualFile.path)
        myFixture.configureFromExistingVirtualFile(virtualFile)

        val document = myFixture.editor.document
        val aliasOffset = document.text.indexOf(savedAlias)
        assertTrue(aliasOffset >= 0)
        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(aliasOffset, aliasOffset + savedAlias.length, draftAlias)
        }

        val documentManager = PsiDocumentManager.getInstance(project)
        val fileDocumentManager = FileDocumentManager.getInstance()
        assertFalse(documentManager.isCommitted(document))
        assertTrue(fileDocumentManager.isDocumentUnsaved(document))
        assertTrue(Files.readString(backingPath).contains(savedAlias))

        val capture = captured(
            XmlMapperMethodCaptureAdapter.capture(
                project,
                statementId("fixture.UnsavedMapper", "find"),
            ),
        )

        assertEquals(draftAlias, capture.parameters.single().myBatisParamAlias)
        assertTrue(capture.mapperSource.content.contains(draftAlias))
        assertTrue(documentManager.isCommitted(document))
        assertTrue(fileDocumentManager.isDocumentUnsaved(document))
        assertTrue(Files.readString(backingPath).contains(savedAlias))
        assertFalse(Files.readString(backingPath).contains(draftAlias))
    }

    fun testOverloadedSameNameMethodsFailAmbiguousInsteadOfChoosingOne() {
        myFixture.addFileToProject(
            "fixture/OverloadedMapper.java",
            """
            package fixture;

            interface OverloadedMapper {
                Object find(int id);
                Object find(String id);
            }
            """.trimIndent(),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        assertFailure(
            XmlMapperMethodCaptureAdapter.capture(
                project,
                statementId("fixture.OverloadedMapper", "find"),
            ),
            XmlMapperMethodCaptureFailure.AMBIGUOUS_METHOD,
        )
    }

    fun testInheritedOnlyMethodDoesNotBecomeNamespaceAuthority() {
        myFixture.addFileToProject(
            "fixture/BaseMapper.java",
            """
            package fixture;

            interface BaseMapper {
                Object find(int id);
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "fixture/ChildMapper.java",
            """
            package fixture;

            interface ChildMapper extends BaseMapper {
            }
            """.trimIndent(),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        assertFailure(
            XmlMapperMethodCaptureAdapter.capture(
                project,
                statementId("fixture.ChildMapper", "find"),
            ),
            XmlMapperMethodCaptureFailure.MISSING_METHOD,
        )
    }

    fun testClassNamespaceAndDefaultMethodFailUnsupported() {
        myFixture.addFileToProject(
            "fixture/ClassMapper.java",
            """
            package fixture;

            class ClassMapper {
                Object find(int id) { return null; }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "fixture/DefaultMapper.java",
            """
            package fixture;

            interface DefaultMapper {
                default Object find(int id) { return null; }
            }
            """.trimIndent(),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        assertFailure(
            XmlMapperMethodCaptureAdapter.capture(
                project,
                statementId("fixture.ClassMapper", "find"),
            ),
            XmlMapperMethodCaptureFailure.UNSUPPORTED_MAPPER_TYPE,
        )
        assertFailure(
            XmlMapperMethodCaptureAdapter.capture(
                project,
                statementId("fixture.DefaultMapper", "find"),
            ),
            XmlMapperMethodCaptureFailure.UNSUPPORTED_METHOD_FORM,
        )
    }

    fun testMyBatisRowBoundsAndResultHandlerParametersFailClosed() {
        myFixture.addFileToProject(
            "fixture/SpecialMapper.java",
            """
            package fixture;

            import org.apache.ibatis.session.ResultHandler;
            import org.apache.ibatis.session.RowBounds;

            interface SpecialMapper {
                Object withBounds(int id, RowBounds bounds);
                void withHandler(int id, ResultHandler<Object> handler);
            }
            """.trimIndent(),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        assertFailure(
            XmlMapperMethodCaptureAdapter.capture(
                project,
                statementId("fixture.SpecialMapper", "withBounds"),
            ),
            XmlMapperMethodCaptureFailure.UNSUPPORTED_SPECIAL_PARAMETER,
        )
        assertFailure(
            XmlMapperMethodCaptureAdapter.capture(
                project,
                statementId("fixture.SpecialMapper", "withHandler"),
            ),
            XmlMapperMethodCaptureFailure.UNSUPPORTED_SPECIAL_PARAMETER,
        )
    }

    fun testSourceCaptureFailuresAndPsiMismatchFailClosed() {
        myFixture.addFileToProject(
            "fixture/SourceFailureMapper.java",
            """
            package fixture;

            interface SourceFailureMapper {
                Object find(int id);
            }
            """.trimIndent(),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val id = statementId("fixture.SourceFailureMapper", "find")

        assertFailure(
            XmlMapperMethodCaptureAdapter.capture(
                project = project,
                statementId = id,
                maxSourceLength = 8,
            ),
            XmlMapperMethodCaptureFailure.MAPPER_SOURCE_TOO_LARGE,
        )

        assertFailure(
            XmlMapperMethodCaptureAdapter.capture(
                project = project,
                statementId = id,
                maxSourceLength = 1024,
                sourceCapture = { _, _ -> DependentMapperSourceCaptureResult.InvalidSource },
            ),
            XmlMapperMethodCaptureFailure.MAPPER_SOURCE_UNAVAILABLE,
        )

        assertFailure(
            XmlMapperMethodCaptureAdapter.capture(
                project = project,
                statementId = id,
                maxSourceLength = 1024,
                sourceCapture = { _, _ -> DependentMapperSourceCaptureResult.SourceChangedDuringCapture },
            ),
            XmlMapperMethodCaptureFailure.SOURCE_CHANGED_DURING_CAPTURE,
        )

        assertFailure(
            XmlMapperMethodCaptureAdapter.capture(
                project = project,
                statementId = id,
                maxSourceLength = 1024,
                sourceCapture = { virtualFile, maxLength ->
                    when (
                        val result = DependentMapperSourceSnapshotAdapter.capture(
                            virtualFile,
                            maxLength,
                        )
                    ) {
                        is DependentMapperSourceCaptureResult.Captured ->
                            DependentMapperSourceCaptureResult.Captured(
                                result.snapshot.copy(
                                    content = result.snapshot.content + "\n// deterministic mismatch",
                                ),
                            )
                        else -> result
                    }
                },
            ),
            XmlMapperMethodCaptureFailure.SOURCE_PSI_MISMATCH,
        )
    }

    fun testDuplicateQualifiedMapperTypesFailAmbiguous() {
        myFixture.addFileToProject(
            "fixture/DuplicateMapperA.java",
            """
            package fixture;

            interface DuplicateMapper {
                Object find(int id);
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "fixture/DuplicateMapperB.java",
            """
            package fixture;

            interface DuplicateMapper {
                Object find(String id);
            }
            """.trimIndent(),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        assertFailure(
            XmlMapperMethodCaptureAdapter.capture(
                project,
                statementId("fixture.DuplicateMapper", "find"),
            ),
            XmlMapperMethodCaptureFailure.AMBIGUOUS_MAPPER_TYPE,
        )
    }

    fun testStaticPrivateAndDefaultMethodsFailUnsupported() {
        myFixture.addFileToProject(
            "fixture/UnsupportedMethodMapper.java",
            """
            package fixture;

            interface UnsupportedMethodMapper {
                static Object staticFind(int id) { return null; }
                private Object privateFind(int id) { return null; }
                default Object defaultFind(int id) { return null; }
            }
            """.trimIndent(),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        listOf("staticFind", "privateFind", "defaultFind").forEach { methodName ->
            assertFailure(
                XmlMapperMethodCaptureAdapter.capture(
                    project,
                    statementId("fixture.UnsupportedMethodMapper", methodName),
                ),
                XmlMapperMethodCaptureFailure.UNSUPPORTED_METHOD_FORM,
            )
        }
    }

    fun testBlankParamAliasFailsClosed() {
        myFixture.addFileToProject(
            "fixture/BlankAliasMapper.java",
            """
            package fixture;

            import org.apache.ibatis.annotations.Param;

            interface BlankAliasMapper {
                Object find(@Param("") int id);
            }
            """.trimIndent(),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        assertFailure(
            XmlMapperMethodCaptureAdapter.capture(
                project,
                statementId("fixture.BlankAliasMapper", "find"),
            ),
            XmlMapperMethodCaptureFailure.UNRESOLVED_PARAM_ALIAS,
        )
    }

    fun testNonLiteralParamAliasAndUnresolvedParameterTypeFailClosed() {
        myFixture.addFileToProject(
            "fixture/AliasMapper.java",
            """
            package fixture;

            import org.apache.ibatis.annotations.Param;

            interface AliasMapper {
                String ALIAS = "id";
                Object find(@Param(ALIAS) int id);
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "fixture/UnknownTypeMapper.java",
            """
            package fixture;

            interface UnknownTypeMapper {
                Object find(MissingType value);
            }
            """.trimIndent(),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        assertFailure(
            XmlMapperMethodCaptureAdapter.capture(
                project,
                statementId("fixture.AliasMapper", "find"),
            ),
            XmlMapperMethodCaptureFailure.UNRESOLVED_PARAM_ALIAS,
        )
        assertFailure(
            XmlMapperMethodCaptureAdapter.capture(
                project,
                statementId("fixture.UnknownTypeMapper", "find"),
            ),
            XmlMapperMethodCaptureFailure.UNRESOLVED_PARAMETER_TYPE,
        )
    }

    fun testMissingMapperAndMissingMethodRemainTyped() {
        myFixture.addFileToProject(
            "fixture/ExistingMapper.java",
            """
            package fixture;

            interface ExistingMapper {
                Object actual();
            }
            """.trimIndent(),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        assertFailure(
            XmlMapperMethodCaptureAdapter.capture(
                project,
                statementId("fixture.MissingMapper", "find"),
            ),
            XmlMapperMethodCaptureFailure.UNRESOLVED_MAPPER_TYPE,
        )
        assertFailure(
            XmlMapperMethodCaptureAdapter.capture(
                project,
                statementId("fixture.ExistingMapper", "find"),
            ),
            XmlMapperMethodCaptureFailure.MISSING_METHOD,
        )
    }

    private fun statementId(namespace: String, methodName: String) = XmlStatementId(
        SourceFileId("vfs:/fixture/Mapper.xml"),
        namespace,
        methodName,
    )

    private fun captured(result: XmlMapperMethodCaptureResult) = when (result) {
        is XmlMapperMethodCaptureResult.Captured -> result.capture
        is XmlMapperMethodCaptureResult.Failed -> throw AssertionError(
            "Expected XML mapper method capture success but got " + result.failure,
        )
    }

    private fun assertFailure(
        result: XmlMapperMethodCaptureResult,
        expected: XmlMapperMethodCaptureFailure,
    ) {
        val failure = result as? XmlMapperMethodCaptureResult.Failed
            ?: throw AssertionError("Expected failure " + expected + " but capture succeeded")
        assertEquals(expected, failure.failure)
    }
}
