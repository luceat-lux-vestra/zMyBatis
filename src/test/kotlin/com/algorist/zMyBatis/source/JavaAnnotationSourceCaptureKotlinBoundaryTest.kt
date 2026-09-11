package com.algorist.zMyBatis.source

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class JavaAnnotationSourceCaptureKotlinBoundaryTest : LightJavaCodeInsightFixtureTestCase() {

    fun testKotlinDirectAnnotationFailsWithExplicitUnsupportedOutcome() {
        myFixture.addFileToProject(
            "org/apache/ibatis/annotations/Select.kt",
            """
            package org.apache.ibatis.annotations

            annotation class Select(val value: String)
            """.trimIndent(),
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
            """.trimIndent(),
        )

        assertEquals("Kotlin", mapperFile.fileType.name)
        assertEquals("org.jetbrains.kotlin.psi.KtFile", mapperFile.javaClass.name)
        val marker = "fun find"
        val offset = mapperFile.text.indexOf(marker)
        assertTrue(offset >= 0)
        myFixture.editor.caretModel.moveToOffset(offset + marker.indexOf("find"))

        assertEquals(
            JavaAnnotationSourceCaptureResult.Failed(
                JavaAnnotationSourceCaptureFailure.UNSUPPORTED_KOTLIN_SOURCE,
            ),
            JavaAnnotationSourceCaptureAdapter.capture(project, myFixture.editor),
        )
    }
}
