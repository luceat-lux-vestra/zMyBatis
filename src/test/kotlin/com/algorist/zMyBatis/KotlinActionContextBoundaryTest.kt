package com.algorist.zMyBatis

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class KotlinActionContextBoundaryTest : LightJavaCodeInsightFixtureTestCase() {

    fun testKotlinSelectAnnotationIsRejectedAtProductionContextBoundary() {
        myFixture.addFileToProject(
            "org/apache/ibatis/annotations/Select.kt",
            """
            package org.apache.ibatis.annotations

            annotation class Select(val value: String)
            """.trimIndent()
        )

        val mapperFile = myFixture.configureByText(
            "UserMapper.kt",
            """
            package fixture

            import org.apache.ibatis.annotations.Select

            interface UserMapper {
                @Select("SELECT * FROM users WHERE id = #{id}")
                fun find(id: Int): Any?
            }
            """.trimIndent()
        )

        assertEquals(
            "fixture must be parsed by the real bundled Kotlin plugin rather than plain-text fallback",
            "Kotlin",
            mapperFile.fileType.name
        )
        assertEquals(
            "fixture must be a real Kotlin PSI file",
            "org.jetbrains.kotlin.psi.KtFile",
            mapperFile.javaClass.name
        )
        assertFalse(
            "Kotlin PSI must not be mistaken for the Java-only production context path",
            mapperFile is PsiJavaFile
        )

        val marker = "fun find"
        val offset = mapperFile.text.indexOf(marker)
        assertTrue("Kotlin mapper function marker must exist", offset >= 0)
        myFixture.editor.caretModel.moveToOffset(offset + marker.indexOf("find"))
        assertNotNull(
            "caret must resolve to a real Kotlin PSI element",
            mapperFile.findElementAt(myFixture.editor.caretModel.offset)
        )

        val action = MyBatisExecuteProxyAction()
        assertEquals(
            "current production analyzer is PsiJavaFile-gated and must not claim Kotlin annotation support",
            MyBatisContextAnalyzer.ContextType.NONE,
            MyBatisContextAnalyzer.analyze(actionEvent(action, mapperFile))
        )
    }

    private fun actionEvent(action: MyBatisExecuteProxyAction, psiFile: PsiFile): AnActionEvent {
        val dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, myFixture.editor)
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
}
