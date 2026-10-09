package com.algorist.zMyBatis.source

import com.algorist.zMyBatis.core.source.ConventionalMyBatisDatabaseIds
import com.algorist.zMyBatis.core.source.JavaAnnotationStatementCapture
import com.algorist.zMyBatis.core.source.JavaAnnotationStatementSelectionResult
import com.algorist.zMyBatis.core.source.JavaAnnotationStatementVariantsCapture
import com.algorist.zMyBatis.core.source.MyBatisDatabaseId
import com.algorist.zMyBatis.core.source.selectJavaAnnotationStatementVariant
import com.algorist.zMyBatis.core.source.StatementKind
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
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

class JavaAnnotationSourceCaptureAdapterAdversarialProjectFixtureTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor() = JAVA_21

    override fun setUp() {
        super.setUp()
        val tempRoot = myFixture.tempDirFixture.getFile("")
            ?: throw AssertionError("physical temp fixture root must exist")
        PsiTestUtil.addContentRoot(module, tempRoot)
        PsiTestUtil.addSourceRoot(module, tempRoot)

        val myBatisJar = File(PathUtil.getJarPathForClass(Select::class.java))
        VfsRootAccess.allowRootAccess(testRootDisposable, myBatisJar.absolutePath)
        PsiTestUtil.addLibrary(module, "mybatis-3.5.19", myBatisJar.parent, myBatisJar.name)
    }

    override fun getTempDirFixture(): TempDirTestFixture =
        IdeaTestFixtureFactory.getFixtureFactory().createTempDirTestFixture()

    fun testEverySupportedDirectAnnotationMapsToExactStatementKind() {
        val mapperFile = myFixture.configureByText(
            JavaFileType.INSTANCE,
            """
            package fixture;

            import org.apache.ibatis.annotations.Delete;
            import org.apache.ibatis.annotations.Insert;
            import org.apache.ibatis.annotations.Select;
            import org.apache.ibatis.annotations.Update;

            interface KindMapper {
                @Select("SELECT 1") Object selectOne();
                @Insert("INSERT INTO t(id) VALUES (1)") Object insertOne();
                @Update("UPDATE t SET id = 2") Object updateOne();
                @Delete("DELETE FROM t") Object deleteOne();
            }
            """.trimIndent(),
        )

        mapOf(
            "selectOne()" to StatementKind.SELECT,
            "insertOne()" to StatementKind.INSERT,
            "updateOne()" to StatementKind.UPDATE,
            "deleteOne()" to StatementKind.DELETE,
        ).forEach { (marker, expectedKind) ->
            moveCaretTo(mapperFile.text, marker)
            val capture = captured(JavaAnnotationSourceCaptureAdapter.capture(project, myFixture.editor))
            assertEquals(expectedKind, capture.sourceGraph.rootStatement.kind)
        }
    }

    fun testDatabaseIdVariantsAreCapturedBeforeTargetAwareSelection() {
        val mapperFile = myFixture.configureByText(
            JavaFileType.INSTANCE,
            """
            package fixture;

            import org.apache.ibatis.annotations.Select;
            import org.apache.ibatis.annotations.Update;

            interface SemanticBoundaryMapper {
                @Select(value = "SELECT oracle", databaseId = "Oracle")
                Object databaseSpecific();

                @Select(value = "DELETE FROM jobs WHERE id = 1 RETURNING id", affectData = true)
                Object mutatingSelect();

                @Select(value = "SELECT oracle", databaseId = "Oracle")
                @Select(value = "SELECT postgres", databaseId = "PostgreSQL")
                Object repeated();

                @Select(value = "SELECT postgres mixed", databaseId = "PostgreSQL")
                @Update(value = "UPDATE jobs SET active = 1", databaseId = "Oracle")
                Object mixedKinds();

                @Select.List({
                    @Select(value = "SELECT oracle container", databaseId = "Oracle"),
                    @Select(value = "SELECT postgres container", databaseId = "PostgreSQL")
                })
                Object explicitContainer();

                @Select("SELECT default")
                @Select(value = "SELECT conventional", databaseId = "PostgreSQL")
                @Select(value = "SELECT custom", databaseId = "pg")
                Object customMapping();

                @Select(value = "SELECT explicit default", databaseId = "")
                Object explicitEmptyDatabaseId();

                @Select(value = "SELECT padded", databaseId = " PostgreSQL ")
                Object paddedDatabaseId();
            }
            """.trimIndent(),
        )

        moveCaretTo(mapperFile.text, "databaseSpecific()")
        val databaseSpecific = captured(JavaAnnotationSourceCaptureAdapter.capture(project, myFixture.editor))
        assertEquals(listOf("Oracle"), databaseSpecific.variants.map { it.databaseId })
        assertEquals(
            JavaAnnotationStatementSelectionResult.AuthorityUnavailable,
            selectJavaAnnotationStatementVariant(databaseSpecific, null),
        )
        val oracleSpecific = selected(
            databaseSpecific,
            MyBatisDatabaseId(ConventionalMyBatisDatabaseIds.ORACLE),
        )
        assertEquals(listOf("SELECT oracle"), oracleSpecific.sqlSegments)
        assertEquals(ConventionalMyBatisDatabaseIds.ORACLE, oracleSpecific.effectiveDatabaseId?.value)

        moveCaretTo(mapperFile.text, "repeated()")
        val repeated = captured(JavaAnnotationSourceCaptureAdapter.capture(project, myFixture.editor))
        assertEquals(listOf("Oracle", "PostgreSQL"), repeated.variants.map { it.databaseId })
        val postgres = selected(
            repeated,
            MyBatisDatabaseId(ConventionalMyBatisDatabaseIds.POSTGRESQL),
        )
        assertEquals(listOf("SELECT postgres"), postgres.sqlSegments)
        assertEquals(ConventionalMyBatisDatabaseIds.POSTGRESQL, postgres.effectiveDatabaseId?.value)

        moveCaretTo(mapperFile.text, "mixedKinds()")
        val mixedKinds = captured(JavaAnnotationSourceCaptureAdapter.capture(project, myFixture.editor))
        assertEquals(
            listOf(StatementKind.SELECT, StatementKind.UPDATE),
            mixedKinds.variants.map { it.statementKind },
        )
        val mixedPostgres = selected(
            mixedKinds,
            MyBatisDatabaseId(ConventionalMyBatisDatabaseIds.POSTGRESQL),
        )
        val mixedOracle = selected(
            mixedKinds,
            MyBatisDatabaseId(ConventionalMyBatisDatabaseIds.ORACLE),
        )
        assertEquals(StatementKind.SELECT, mixedPostgres.sourceGraph.rootStatement.kind)
        assertEquals(listOf("SELECT postgres mixed"), mixedPostgres.sqlSegments)
        assertEquals(StatementKind.UPDATE, mixedOracle.sourceGraph.rootStatement.kind)
        assertEquals(listOf("UPDATE jobs SET active = 1"), mixedOracle.sqlSegments)

        moveCaretTo(mapperFile.text, "explicitContainer()")
        val container = captured(JavaAnnotationSourceCaptureAdapter.capture(project, myFixture.editor))
        val oracleContainer = selected(
            container,
            MyBatisDatabaseId(ConventionalMyBatisDatabaseIds.ORACLE),
        )
        assertEquals(listOf("SELECT oracle container"), oracleContainer.sqlSegments)

        assertFailure(
            mapperFile.text,
            "mutatingSelect()",
            JavaAnnotationSourceCaptureFailure.UNSUPPORTED_AFFECT_DATA,
        )

        moveCaretTo(mapperFile.text, "customMapping()")
        val custom = captured(JavaAnnotationSourceCaptureAdapter.capture(project, myFixture.editor))
        val customSelection = selectJavaAnnotationStatementVariant(
            custom,
            MyBatisDatabaseId(ConventionalMyBatisDatabaseIds.POSTGRESQL),
        )
        assertTrue(customSelection is JavaAnnotationStatementSelectionResult.MappingUnproven)
        assertEquals(
            listOf("pg"),
            (customSelection as JavaAnnotationStatementSelectionResult.MappingUnproven).declaredDatabaseIds,
        )

        moveCaretTo(mapperFile.text, "explicitEmptyDatabaseId()")
        val explicitEmpty = captured(JavaAnnotationSourceCaptureAdapter.capture(project, myFixture.editor))
        assertEquals(listOf<String?>(null), explicitEmpty.variants.map { it.databaseId })
        assertEquals(
            listOf("SELECT explicit default"),
            selected(explicitEmpty, MyBatisDatabaseId(ConventionalMyBatisDatabaseIds.POSTGRESQL)).sqlSegments,
        )

        moveCaretTo(mapperFile.text, "paddedDatabaseId()")
        val padded = captured(JavaAnnotationSourceCaptureAdapter.capture(project, myFixture.editor))
        assertEquals(listOf(" PostgreSQL "), padded.variants.map { it.databaseId })
        val paddedSelection = selectJavaAnnotationStatementVariant(
            padded,
            MyBatisDatabaseId(ConventionalMyBatisDatabaseIds.POSTGRESQL),
        )
        assertTrue(paddedSelection is JavaAnnotationStatementSelectionResult.MappingUnproven)
        assertEquals(
            listOf(" PostgreSQL "),
            (paddedSelection as JavaAnnotationStatementSelectionResult.MappingUnproven).declaredDatabaseIds,
        )
    }

    fun testDefaultAnnotatedMethodFailsUnsupportedMethodForm() {
        val mapperFile = myFixture.configureByText(
            JavaFileType.INSTANCE,
            """
            package fixture;

            import org.apache.ibatis.annotations.Select;

            interface DefaultMapper {
                @Select("SELECT ignored")
                default Object ignoredByMyBatis() { return null; }
            }
            """.trimIndent(),
        )

        assertFailure(
            mapperFile.text,
            "ignoredByMyBatis()",
            JavaAnnotationSourceCaptureFailure.UNSUPPORTED_METHOD_FORM,
        )
    }

    fun testConstantDependencyCycleFailsTyped() {
        myFixture.addClass(
            """
            package fixture;

            public final class CyclicSql {
                public static final String A = CyclicSql.B;
                public static final String B = CyclicSql.A;
                private CyclicSql() {}
            }
            """.trimIndent(),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val mapperFile = myFixture.configureByText(
            JavaFileType.INSTANCE,
            """
            package fixture;

            import org.apache.ibatis.annotations.Select;

            interface CyclicMapper {
                @Select(CyclicSql.A)
                Object find();
            }
            """.trimIndent(),
        )

        assertFailure(
            mapperFile.text,
            "find()",
            JavaAnnotationSourceCaptureFailure.CONSTANT_DEPENDENCY_CYCLE,
        )
    }

    fun testMissingMethodAndMissingDirectAnnotationFailTyped() {
        val mapperFile = myFixture.configureByText(
            JavaFileType.INSTANCE,
            """
            package fixture;

            interface MissingMapper {
                Object plain(int id);
            }
            """.trimIndent(),
        )

        val packageOffset = mapperFile.text.indexOf("fixture")
        assertTrue(packageOffset >= 0)
        myFixture.editor.caretModel.moveToOffset(packageOffset)
        assertEquals(
            JavaAnnotationSourceCaptureResult.Failed(JavaAnnotationSourceCaptureFailure.MISSING_METHOD),
            JavaAnnotationSourceCaptureAdapter.capture(project, myFixture.editor),
        )

        moveCaretTo(mapperFile.text, "plain(int id)")
        assertEquals(
            JavaAnnotationSourceCaptureResult.Failed(
                JavaAnnotationSourceCaptureFailure.MISSING_STATEMENT_ANNOTATION,
            ),
            JavaAnnotationSourceCaptureAdapter.capture(project, myFixture.editor),
        )
    }

    fun testUnresolvedParamAliasParameterTypeAndMapperTypeFailClosed() {
        val mapperFile = myFixture.configureByText(
            JavaFileType.INSTANCE,
            """
            package fixture;

            import org.apache.ibatis.annotations.Param;
            import org.apache.ibatis.annotations.Select;

            interface FailureMapper {
                @Select("SELECT 1")
                Object unresolvedAlias(@Param(MissingAlias.VALUE) int id);

                @Select("SELECT 1")
                Object unresolvedType(MissingType input);
            }

            class Holder {
                Object mapper = new Object() {
                    @Select("SELECT 1")
                    Object anonymousMapperMethod() { return null; }
                };
            }
            """.trimIndent(),
        )

        assertFailure(
            mapperFile.text,
            "unresolvedAlias(",
            JavaAnnotationSourceCaptureFailure.UNRESOLVED_PARAM_ALIAS,
        )
        assertFailure(
            mapperFile.text,
            "unresolvedType(",
            JavaAnnotationSourceCaptureFailure.UNRESOLVED_PARAMETER_TYPE,
        )
        assertFailure(
            mapperFile.text,
            "anonymousMapperMethod()",
            JavaAnnotationSourceCaptureFailure.UNRESOLVED_MAPPER_TYPE,
        )
    }

    fun testUnsavedDependentConstantDocumentIsAuthoritativeWithoutDiskSave() {
        val savedSql = "SELECT saved"
        val draftSql = "SELECT draft"
        val constantsFile = myFixture.addFileToProject(
            "fixture/SqlConstants.java",
            """
            package fixture;

            public final class SqlConstants {
                public static final String SQL = "$savedSql";
                private SqlConstants() {}
            }
            """.trimIndent(),
        )
        val mapperFile = myFixture.addFileToProject(
            "fixture/DependentMapper.java",
            """
            package fixture;

            import org.apache.ibatis.annotations.Select;

            interface DependentMapper {
                @Select(SqlConstants.SQL)
                Object find();
            }
            """.trimIndent(),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val constantsVirtualFile = constantsFile.virtualFile
        val backingPath = Path.of(constantsVirtualFile.path)
        assertTrue(Files.readString(backingPath).contains(savedSql))
        val constantsDocument = FileDocumentManager.getInstance().getDocument(constantsVirtualFile)
        assertNotNull(constantsDocument)
        val savedOffset = constantsDocument!!.text.indexOf(savedSql)
        assertTrue(savedOffset >= 0)
        WriteCommandAction.runWriteCommandAction(project) {
            constantsDocument.replaceString(savedOffset, savedOffset + savedSql.length, draftSql)
        }
        assertFalse(PsiDocumentManager.getInstance(project).isCommitted(constantsDocument))
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(constantsDocument))

        myFixture.configureFromExistingVirtualFile(mapperFile.virtualFile)
        moveCaretTo(myFixture.file.text, "find()")
        val capture = captured(JavaAnnotationSourceCaptureAdapter.capture(project, myFixture.editor))

        assertEquals(listOf(draftSql), capture.variants.single().sqlSegments)
        assertTrue(PsiDocumentManager.getInstance(project).isCommitted(constantsDocument))
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(constantsDocument))
        assertTrue(Files.readString(backingPath).contains(savedSql))
        assertFalse(Files.readString(backingPath).contains(draftSql))
        val dependentSnapshot = capture.sourceGraph.sourceSnapshots.single {
            it.fileId.value == "vfs:${constantsVirtualFile.url}"
        }
        assertTrue(dependentSnapshot.content.contains(draftSql))
    }

    private fun assertFailure(
        fileText: String,
        marker: String,
        expected: JavaAnnotationSourceCaptureFailure,
    ) {
        moveCaretTo(fileText, marker)
        assertEquals(
            JavaAnnotationSourceCaptureResult.Failed(expected),
            JavaAnnotationSourceCaptureAdapter.capture(project, myFixture.editor),
        )
    }

    private fun captured(result: JavaAnnotationSourceCaptureResult): JavaAnnotationStatementVariantsCapture =
        when (result) {
            is JavaAnnotationSourceCaptureResult.Captured -> result.capture
            is JavaAnnotationSourceCaptureResult.Failed -> throw AssertionError("expected capture but got ${result.failure}")
        }

    private fun selected(
        capture: JavaAnnotationStatementVariantsCapture,
        databaseId: MyBatisDatabaseId,
    ): JavaAnnotationStatementCapture =
        when (val result = selectJavaAnnotationStatementVariant(capture, databaseId)) {
            is JavaAnnotationStatementSelectionResult.Selected -> result.capture
            else -> throw AssertionError("expected selected Java annotation variant but got $result")
        }

    private fun moveCaretTo(fileText: String, marker: String) {
        val offset = fileText.indexOf(marker)
        assertTrue("marker '$marker' must exist", offset >= 0)
        myFixture.editor.caretModel.moveToOffset(offset)
    }
}
