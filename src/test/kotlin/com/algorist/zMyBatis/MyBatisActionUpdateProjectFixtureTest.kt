package com.algorist.zMyBatis

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class MyBatisActionUpdateProjectFixtureTest : LightJavaCodeInsightFixtureTestCase() {

    fun testXmlStatementContextIsVisibleButNonStatementXmlIsHidden() {
        val mapper = myFixture.configureByText(
            XmlFileType.INSTANCE,
            """
            <mapper namespace="fixture.Mapper">
              <select id="find">SELECT * FROM users</select>
              <sql id="fragment">id, name</sql>
            </mapper>
            """.trimIndent()
        )

        moveCaretTo(mapper, myFixture.editor, "SELECT * FROM users")
        assertApplicable(updatePresentation(myFixture.editor, mapper))

        moveCaretTo(mapper, myFixture.editor, "id, name")
        assertNotApplicable(updatePresentation(myFixture.editor, mapper))
    }

    fun testDirectJavaStatementAnnotationIsVisible() {
        addStatementAnnotation("Select")
        val mapper = myFixture.configureByText(
            JavaFileType.INSTANCE,
            """
            package fixture;

            import org.apache.ibatis.annotations.Select;

            class UserMapper {
                @Select("SELECT 1")
                Object find() { return null; }
            }
            """.trimIndent()
        )

        moveCaretTo(mapper, myFixture.editor, "find()")
        assertApplicable(updatePresentation(myFixture.editor, mapper))
    }

    fun testProviderAnnotationRemainsVisibleForExplicitUnsupportedNotice() {
        myFixture.addClass(
            """
            package org.apache.ibatis.annotations;

            public @interface SelectProvider {
                Class<?> type();
                String method();
            }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package fixture;

            public class SqlProvider {
                public static String sql() { return "SELECT 1"; }
            }
            """.trimIndent()
        )
        val mapper = myFixture.configureByText(
            JavaFileType.INSTANCE,
            """
            package fixture;

            import org.apache.ibatis.annotations.SelectProvider;

            class ProviderMapper {
                @SelectProvider(type = SqlProvider.class, method = "sql")
                Object find() { return null; }
            }
            """.trimIndent()
        )

        moveCaretTo(mapper, myFixture.editor, "find()")
        assertApplicable(updatePresentation(myFixture.editor, mapper))
    }

    fun testPlainJavaAndMissingEditorAreHidden() {
        val plain = myFixture.configureByText(
            JavaFileType.INSTANCE,
            """
            package fixture;

            class PlainJava {
                Object find() { return null; }
            }
            """.trimIndent()
        )

        moveCaretTo(plain, myFixture.editor, "find()")
        assertNotApplicable(updatePresentation(myFixture.editor, plain))

        val action = MyBatisExecuteProxyAction()
        val dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.PSI_FILE, plain)
            .build()
        val event = AnActionEvent.createEvent(
            dataContext,
            action.templatePresentation.clone(),
            ActionPlaces.UNKNOWN,
            ActionUiKind.TOOLBAR,
            null,
        )
        action.update(event)

        assertNotApplicable(event.presentation)
    }

    fun testCaretAtFileEndFailsClosedInsteadOfThrowing() {
        val plain = myFixture.configureByText(
            JavaFileType.INSTANCE,
            "package fixture; class PlainJava {}"
        )
        myFixture.editor.caretModel.moveToOffset(plain.textLength)

        assertNotApplicable(updatePresentation(myFixture.editor, plain))
    }

    private fun addStatementAnnotation(name: String) {
        myFixture.addClass(
            """
            package org.apache.ibatis.annotations;

            public @interface $name {
                String[] value();
            }
            """.trimIndent()
        )
    }

    private fun updatePresentation(editor: Editor, psiFile: PsiFile): Presentation {
        val action = MyBatisExecuteProxyAction()
        val dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, editor)
            .add(CommonDataKeys.PSI_FILE, psiFile)
            .build()
        val event = AnActionEvent.createEvent(
            dataContext,
            action.templatePresentation.clone(),
            ActionPlaces.UNKNOWN,
            ActionUiKind.TOOLBAR,
            null,
        )
        action.update(event)
        return event.presentation
    }

    private fun moveCaretTo(psiFile: PsiFile, editor: Editor, marker: String) {
        val offset = psiFile.text.indexOf(marker)
        assertTrue("marker '$marker' must exist", offset >= 0)
        editor.caretModel.moveToOffset(offset)
    }

    private fun assertApplicable(presentation: Presentation) {
        assertTrue("action must be visible in applicable context", presentation.isVisible)
        assertTrue("action must be enabled in applicable context", presentation.isEnabled)
    }

    private fun assertNotApplicable(presentation: Presentation) {
        assertFalse("action must be hidden outside applicable context", presentation.isVisible)
        assertFalse("action must be disabled outside applicable context", presentation.isEnabled)
    }
}
