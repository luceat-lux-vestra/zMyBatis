package com.algorist.zMyBatis

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.util.ThrowableRunnable

class JavaActionContextProjectFixtureTest : LightJavaCodeInsightFixtureTestCase() {

    fun testSavedJavaCaretUsesDistinctOverloadsWhileCurrentStatementKeyCollides() {
        myFixture.addClass(
            """
            package org.apache.ibatis.annotations;

            public @interface Select {
                String[] value();
            }
            """.trimIndent()
        )

        val mapperFile = myFixture.configureByText(
            JavaFileType.INSTANCE,
            """
            package fixture;

            import org.apache.ibatis.annotations.Select;

            class UserMapper {
                @Select("SELECT * FROM users WHERE id = #{id}")
                Object find(int id) { return null; }

                @Select("SELECT * FROM users WHERE name = #{name}")
                Object find(String name) { return null; }
            }
            """.trimIndent()
        ) as PsiJavaFile

        val overloads = mapperFile.classes.single().findMethodsByName("find", false)
        assertEquals(2, overloads.size)
        val intMethod = overloads.single {
            it.parameterList.parameters.single().type.canonicalText == "int"
        }
        val stringMethod = overloads.single { it !== intMethod }
        val intParameterType = intMethod.parameterList.parameters.single().type.canonicalText
        val stringParameterType = stringMethod.parameterList.parameters.single().type.canonicalText
        assertFalse(
            "fixture must contain genuinely distinct mapper method signatures",
            intParameterType == stringParameterType
        )

        val action = MyBatisExecuteProxyAction()
        val editor = myFixture.editor
        val fileKey = "/fixture/UserMapper.java"

        moveCaretTo(mapperFile, editor, "find(int id)")
        assertEquals(
            MyBatisContextAnalyzer.ContextType.ANNOTATION,
            MyBatisContextAnalyzer.analyze(actionEvent(action, editor, mapperFile))
        )
        val intSql = invokeExtractSqlContent(action, editor, mapperFile)
        val intKey = invokeExtractStatementKey(action, editor, mapperFile, fileKey)

        moveCaretTo(mapperFile, editor, "find(String name)")
        assertEquals(
            MyBatisContextAnalyzer.ContextType.ANNOTATION,
            MyBatisContextAnalyzer.analyze(actionEvent(action, editor, mapperFile))
        )
        val stringSql = invokeExtractSqlContent(action, editor, mapperFile)
        val stringKey = invokeExtractStatementKey(action, editor, mapperFile, fileKey)

        assertEquals("SELECT * FROM users WHERE id = #{id}", intSql)
        assertEquals("SELECT * FROM users WHERE name = #{name}", stringSql)

        assertEquals(
            "current action key omits the method signature, so overloads collide",
            "$fileKey::UserMapper#find",
            intKey
        )
        assertEquals(
            "distinct overloads currently share one remembered-parameter statement key",
            intKey,
            stringKey
        )
    }

    fun testUnsavedJavaAnnotationUsesLastCommittedPsiUntilExplicitDocumentCommit() {
        myFixture.addClass(
            """
            package org.apache.ibatis.annotations;

            public @interface Select {
                String[] value();
            }
            """.trimIndent()
        )

        val mapperFile = myFixture.configureByText(
            JavaFileType.INSTANCE,
            """
            package fixture;

            import org.apache.ibatis.annotations.Select;

            class UserMapper {
                @Select("SELECT saved")
                Object find(int id) { return null; }
            }
            """.trimIndent()
        ) as PsiJavaFile

        val action = MyBatisExecuteProxyAction()
        val editor = myFixture.editor
        val document = editor.document
        val documentManager = PsiDocumentManager.getInstance(project)
        val savedSql = "SELECT saved"
        val draftSql = "SELECT draft"

        moveCaretTo(mapperFile, editor, "find(int id)")
        assertTrue("fixture must start from committed PSI", documentManager.isCommitted(document))
        assertEquals(savedSql, invokeExtractSqlContent(action, editor, mapperFile))

        val sqlOffset = document.text.indexOf(savedSql)
        assertTrue("saved annotation SQL must exist in the editor document", sqlOffset >= 0)
        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(sqlOffset, sqlOffset + savedSql.length, draftSql)
        }

        assertFalse(
            "editing the mapper Document must create an uncommitted Document/PSI boundary",
            documentManager.isCommitted(document)
        )
        assertTrue("editor Document must contain the unsaved SQL", document.text.contains(draftSql))
        assertTrue(
            "last committed PSI text must still represent the pre-edit SQL",
            documentManager.getLastCommittedText(document).contains(savedSql)
        )

        assertEquals(
            MyBatisContextAnalyzer.ContextType.ANNOTATION,
            MyBatisContextAnalyzer.analyze(actionEvent(action, editor, mapperFile))
        )
        assertEquals(
            "current production extraction reads the last committed PSI, not the uncommitted editor Document",
            savedSql,
            invokeExtractSqlContent(action, editor, mapperFile)
        )
        assertFalse(
            "production analysis/extraction must not be mistaken for an implicit mapper Document commit",
            documentManager.isCommitted(document)
        )

        documentManager.commitDocument(document)

        assertTrue("explicit Document commit must synchronize PSI", documentManager.isCommitted(document))
        assertEquals(
            MyBatisContextAnalyzer.ContextType.ANNOTATION,
            MyBatisContextAnalyzer.analyze(actionEvent(action, editor, mapperFile))
        )
        assertEquals(
            "after PSI synchronization production extraction must see the edited annotation SQL",
            draftSql,
            invokeExtractSqlContent(action, editor, mapperFile)
        )
    }

    fun testProviderAnnotationsStopAtUnsupportedActionBoundaryWithoutStatementFallback() {
        myFixture.addClass(
            """
            package org.apache.ibatis.annotations;

            public @interface Select {
                String[] value();
            }
            """.trimIndent()
        )
        listOf("SelectProvider", "InsertProvider", "UpdateProvider", "DeleteProvider").forEach { annotationName ->
            myFixture.addClass(
                """
                package org.apache.ibatis.annotations;

                public @interface $annotationName {
                    Class<?> type();
                    String method();
                }
                """.trimIndent()
            )
        }
        myFixture.addClass(
            """
            package fixture;

            public class SqlProvider {
                public static String sql() {
                    return "DELETE FROM users";
                }
            }
            """.trimIndent()
        )

        val mapperFile = myFixture.configureByText(
            JavaFileType.INSTANCE,
            """
            package fixture;

            import org.apache.ibatis.annotations.DeleteProvider;
            import org.apache.ibatis.annotations.InsertProvider;
            import org.apache.ibatis.annotations.Select;
            import org.apache.ibatis.annotations.SelectProvider;
            import org.apache.ibatis.annotations.UpdateProvider;

            class ProviderMapper {
                @Select("SELECT plausible_but_must_not_win")
                @SelectProvider(type = SqlProvider.class, method = "sql")
                Object selectViaProvider() { return null; }

                @InsertProvider(type = SqlProvider.class, method = "sql")
                Object insertViaProvider() { return null; }

                @UpdateProvider(type = SqlProvider.class, method = "sql")
                Object updateViaProvider() { return null; }

                @DeleteProvider(type = SqlProvider.class, method = "sql")
                Object deleteViaProvider() { return null; }
            }
            """.trimIndent()
        ) as PsiJavaFile

        val action = MyBatisExecuteProxyAction()
        val editor = myFixture.editor
        val notices = mutableListOf<String>()
        val previousDialog = TestDialogManager.setTestDialog(TestDialog { message ->
            notices += message
            0
        })

        try {
            listOf(
                "selectViaProvider()",
                "insertViaProvider()",
                "updateViaProvider()",
                "deleteViaProvider()",
            ).forEach { marker ->
                val methodName = marker.substringBefore('(')
                moveCaretTo(mapperFile, editor, marker, methodName)
                val event = actionEvent(action, editor, mapperFile)

                assertEquals(
                    "provider annotation must win over ordinary statement annotations at the action boundary",
                    MyBatisContextAnalyzer.ContextType.PROVIDER,
                    MyBatisContextAnalyzer.analyze(event)
                )
                assertNull(
                    "provider context must not expose plausible SQL to the ordinary extraction pipeline",
                    invokeExtractSqlContent(
                        action,
                        editor,
                        mapperFile,
                        MyBatisContextAnalyzer.ContextType.PROVIDER
                    )
                )

                val noticeCountBeforeAction = notices.size
                EdtTestUtil.runInEdtAndWait(
                    ThrowableRunnable<Throwable> {
                        action.actionPerformed(event)
                    }
                )
                assertEquals(
                    "provider action must stop at exactly one unsupported notice",
                    noticeCountBeforeAction + 1,
                    notices.size
                )
                assertTrue(
                    "unsupported notice must explain that provider SQL is not statically extracted",
                    notices.last().contains("zMyBatis cannot statically extract the SQL from a Provider class.")
                )
            }
        } finally {
            TestDialogManager.setTestDialog(previousDialog)
        }
    }

    private fun moveCaretTo(
        psiFile: PsiFile,
        editor: Editor,
        marker: String,
        caretToken: String = "find"
    ) {
        val offset = psiFile.text.indexOf(marker)
        assertTrue("marker '$marker' must exist in the real Java file", offset >= 0)
        val tokenOffset = marker.indexOf(caretToken)
        assertTrue("caret token '$caretToken' must exist in marker '$marker'", tokenOffset >= 0)
        editor.caretModel.moveToOffset(offset + tokenOffset)
    }

    private fun actionEvent(
        action: MyBatisExecuteProxyAction,
        editor: Editor,
        psiFile: PsiFile
    ): AnActionEvent {
        val dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, editor)
            .add(CommonDataKeys.PSI_FILE, psiFile)
            .build()
        return AnActionEvent.createEvent(
            dataContext,
            action.templatePresentation.clone(),
            ActionPlaces.UNKNOWN,
            ActionUiKind.TOOLBAR,
            null
        )
    }

    private fun invokeExtractSqlContent(
        action: MyBatisExecuteProxyAction,
        editor: Editor,
        psiFile: PsiFile,
        context: MyBatisContextAnalyzer.ContextType = MyBatisContextAnalyzer.ContextType.ANNOTATION
    ): String? {
        val method = MyBatisExecuteProxyAction::class.java.getDeclaredMethod(
            "extractSqlContent",
            MyBatisContextAnalyzer.ContextType::class.java,
            Editor::class.java,
            PsiFile::class.java
        )
        method.isAccessible = true
        return method.invoke(
            action,
            context,
            editor,
            psiFile
        ) as String?
    }

    private fun invokeExtractStatementKey(
        action: MyBatisExecuteProxyAction,
        editor: Editor,
        psiFile: PsiFile,
        fileKey: String
    ): String {
        val method = MyBatisExecuteProxyAction::class.java.getDeclaredMethod(
            "extractStatementKey",
            MyBatisContextAnalyzer.ContextType::class.java,
            Editor::class.java,
            PsiFile::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(
            action,
            MyBatisContextAnalyzer.ContextType.ANNOTATION,
            editor,
            psiFile,
            fileKey
        ) as String
    }
}
