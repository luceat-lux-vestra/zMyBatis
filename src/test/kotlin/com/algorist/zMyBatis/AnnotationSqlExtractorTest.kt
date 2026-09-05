package com.algorist.zMyBatis

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.psi.PsiJavaFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AnnotationSqlExtractorTest : BasePlatformTestCase() {

    fun testLiteralAnnotationValue() {
        val annotation = selectAnnotation(
            """
            import org.apache.ibatis.annotations.Select;
            interface Mapper {
                @Select("SELECT * FROM users WHERE id = #{id}")
                void find();
            }
            """.trimIndent()
        )
        assertEquals("SELECT * FROM users WHERE id = #{id}", AnnotationSqlExtractor.extract(annotation))
    }

    fun testArrayAnnotationValuePreservesStatementOrder() {
        val annotation = selectAnnotation(
            """
            import org.apache.ibatis.annotations.Select;
            interface Mapper {
                @Select({"SELECT id,", "name", "FROM users"})
                void find();
            }
            """.trimIndent()
        )
        assertEquals("SELECT id, name FROM users", AnnotationSqlExtractor.extract(annotation))
    }

    fun testConstantReferenceIsResolvedToLiteralSql() {
        val annotation = selectAnnotation(
            """
            import org.apache.ibatis.annotations.Select;
            interface Mapper {
                String FIND_SQL = "SELECT * FROM users WHERE active = 1";
                @Select(FIND_SQL)
                void find();
            }
            """.trimIndent()
        )
        assertEquals("SELECT * FROM users WHERE active = 1", AnnotationSqlExtractor.extract(annotation))
    }

    fun testNullAnnotationFailsClosed() {
        assertNull(AnnotationSqlExtractor.extract(null))
    }

    private fun selectAnnotation(source: String) =
        (myFixture.configureByText(JavaFileType.INSTANCE, source) as PsiJavaFile)
            .classes.single()
            .methods.single { it.name == "find" }
            .modifierList
            .annotations
            .single()
}
