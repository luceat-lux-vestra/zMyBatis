package com.algorist.zMyBatis.source

import com.algorist.zMyBatis.core.source.JavaAnnotationStatementCapture
import com.algorist.zMyBatis.core.source.JavaStatementId
import com.algorist.zMyBatis.core.source.StatementKind
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiReferenceExpression
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.util.PathUtil
import org.apache.ibatis.annotations.Select
import org.junit.Assert.assertNotEquals
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class JavaAnnotationSourceCaptureAdapterProjectFixtureTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor() = JAVA_21

    override fun setUp() {
        super.setUp()
        val tempRoot = myFixture.tempDirFixture.getFile("")
            ?: throw AssertionError("physical temp fixture root must exist")
        PsiTestUtil.addContentRoot(module, tempRoot)
        PsiTestUtil.addSourceRoot(module, tempRoot)

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

        moveCaretTo(mapperFile.text, "find(String name)", "find")
        val overloadCapture = captured(JavaAnnotationSourceCaptureAdapter.capture(project, myFixture.editor))
        val overloadStatementId = overloadCapture.sourceGraph.rootStatement.id as JavaStatementId
        assertEquals("find(java.lang.String)", overloadStatementId.methodSignature.toString())
        assertNotEquals(
            "overloads with different ordered parameter types must have distinct canonical statement ids",
            statementId,
            overloadStatementId,
        )
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

    fun testCompileTimeStringConstantCapturesPrimitiveConstantDependency() {
        myFixture.addClass(
            """
            package fixture;

            public final class ConstantExpressions {
                public static final int LIMIT = 2 + 3;
                public static final String SQL = "SELECT " + (1 + 1) + " LIMIT " + LIMIT;
                private ConstantExpressions() {}
            }
            """.trimIndent(),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val mapperFile = myFixture.configureByText(
            JavaFileType.INSTANCE,
            """
            package fixture;

            import org.apache.ibatis.annotations.Select;

            interface ConstantExpressionMapper {
                @Select(ConstantExpressions.SQL)
                Object find();
            }
            """.trimIndent(),
        )
        moveCaretTo(mapperFile.text, "find()", "find")

        val capture = captured(JavaAnnotationSourceCaptureAdapter.capture(project, myFixture.editor))
        val rootFileId = capture.sourceGraph.rootStatement.id.sourceFileId
        val constantsFileId = capture.sourceGraph.sourceSnapshots.single { it.fileId != rootFileId }.fileId

        assertEquals(listOf("SELECT 2 LIMIT 5"), capture.sqlSegments)
        assertEquals(2, capture.sourceGraph.sourceSnapshots.size)
        assertEquals(2, capture.sourceGraph.dependencies.size)
        assertTrue(
            "annotation reference must retain the cross-file String constant dependency",
            capture.sourceGraph.dependencies.any {
                it.dependentFileId == rootFileId && it.requiredFileId == constantsFileId
            },
        )
        assertTrue(
            "String constant evaluation must retain its primitive constant dependency",
            capture.sourceGraph.dependencies.any {
                it.dependentFileId == constantsFileId && it.requiredFileId == constantsFileId
            },
        )
    }

    fun testRealMyBatisCrossFileConstantPsiExposesCompilerValueAndExactSourceRanges() {
        myFixture.addClass(
            """
            package fixture;

            public final class PsiSqlConstants {
                public static final String SQL = "SELECT psi";
                private PsiSqlConstants() {}
            }
            """.trimIndent(),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val mapperFile = myFixture.configureByText(
            JavaFileType.INSTANCE,
            """
            package fixture;

            import org.apache.ibatis.annotations.Select;

            interface PsiMapper {
                @Select(PsiSqlConstants.SQL)
                Object find();
            }
            """.trimIndent(),
        ) as PsiJavaFile

        val method = mapperFile.classes.single().findMethodsByName("find", false).single()
        val annotation = method.getAnnotation("org.apache.ibatis.annotations.Select")
        assertNotNull("real MyBatis @Select must resolve", annotation)
        val member = annotation!!.findAttributeValue("value")
        assertTrue("single @Select constant must remain a reference expression", member is PsiReferenceExpression)
        val reference = member as PsiReferenceExpression
        val field = reference.resolve() as? PsiField
            ?: throw AssertionError("real MyBatis constant reference must resolve to a project field")

        assertEquals("fixture.PsiSqlConstants", field.containingClass?.qualifiedName)
        assertEquals("SQL", field.name)
        assertTrue(field.hasModifierProperty(PsiModifier.STATIC))
        assertTrue(field.hasModifierProperty(PsiModifier.FINAL))
        assertEquals("java.lang.String", field.type.canonicalText)
        val initializer = field.initializer
            ?: throw AssertionError("resolved compile-time constant field must expose its initializer")
        assertEquals("\"SELECT psi\"", initializer.text)
        assertEquals("SELECT psi", field.computeConstantValue())

        val dependentSnapshot = when (
            val result = DependentMapperSourceSnapshotAdapter.capture(
                virtualFile = field.containingFile.virtualFile,
                maxContentLength = 2 * 1024 * 1024,
            )
        ) {
            is DependentMapperSourceCaptureResult.Captured -> result.snapshot
            else -> throw AssertionError("resolved constant source must be capturable, got $result")
        }
        val fieldRange = field.textRange
        assertEquals(
            field.text,
            dependentSnapshot.content.substring(fieldRange.startOffset, fieldRange.endOffset),
        )

        val activeSnapshot = when (val result = ActiveEditorSourceSnapshotAdapter.capture(myFixture.editor)) {
            is ActiveEditorSourceCaptureResult.Captured -> result.capture.snapshot
            else -> throw AssertionError("active mapper source must be capturable, got $result")
        }
        val referenceRange = reference.textRange
        assertEquals(
            reference.text,
            activeSnapshot.content.substring(referenceRange.startOffset, referenceRange.endOffset),
        )
    }

    fun testRealMyBatisCyclicConstantPsiReferencesResolveReciprocally() {
        myFixture.addClass(
            """
            package fixture;

            public final class PsiCyclicSql {
                public static final String A = PsiCyclicSql.B;
                public static final String B = PsiCyclicSql.A;
                private PsiCyclicSql() {}
            }
            """.trimIndent(),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val mapperFile = myFixture.configureByText(
            JavaFileType.INSTANCE,
            """
            package fixture;

            import org.apache.ibatis.annotations.Select;

            interface PsiCycleMapper {
                @Select(PsiCyclicSql.A)
                Object find();
            }
            """.trimIndent(),
        ) as PsiJavaFile

        val method = mapperFile.classes.single().findMethodsByName("find", false).single()
        val annotation = method.getAnnotation("org.apache.ibatis.annotations.Select")
        assertNotNull("real MyBatis @Select must resolve", annotation)
        val rootReference = annotation!!.findAttributeValue("value") as? PsiReferenceExpression
            ?: throw AssertionError("cyclic @Select constant must remain a reference expression")
        val fieldA = rootReference.resolve() as? PsiField
            ?: throw AssertionError("cyclic root reference must resolve to field A")
        assertEquals("A", fieldA.name)

        val referenceToB = fieldA.initializer as? PsiReferenceExpression
            ?: throw AssertionError("field A initializer must remain a reference expression")
        val fieldB = referenceToB.resolve() as? PsiField
            ?: throw AssertionError("field A initializer must resolve to field B")
        assertEquals("B", fieldB.name)

        val referenceBackToA = fieldB.initializer as? PsiReferenceExpression
            ?: throw AssertionError("field B initializer must remain a reference expression")
        val resolvedBackToA = referenceBackToA.resolve() as? PsiField
            ?: throw AssertionError("field B initializer must resolve back to field A")
        assertEquals("A", resolvedBackToA.name)
        assertEquals("fixture.PsiCyclicSql", resolvedBackToA.containingClass?.qualifiedName)
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

                @Select(1)
                Object nonString();
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
        assertFailure(
            mapperFile.text,
            "nonString()",
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
