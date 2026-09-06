package com.algorist.zMyBatis

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

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

    private fun moveCaretTo(psiFile: PsiFile, editor: Editor, marker: String) {
        val offset = psiFile.text.indexOf(marker)
        assertTrue("marker '$marker' must exist in the real Java file", offset >= 0)
        editor.caretModel.moveToOffset(offset + marker.indexOf("find"))
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
        psiFile: PsiFile
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
            MyBatisContextAnalyzer.ContextType.ANNOTATION,
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
