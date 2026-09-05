package com.algorist.zMyBatis

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AnnotationSqlExtractorTest : BasePlatformTestCase() {

    fun testExtractsLiteralAnnotationSql() {
        val annotation = annotationFrom(
            """
            @interface Select { String[] value(); }
            class Mapper {
                @Select("SELECT * FROM users")
                void find() {}
            }
            """.trimIndent()
        )

        assertEquals("SELECT * FROM users", AnnotationSqlExtractor.extract(annotation))
    }

    fun testJoinsAnnotationStringArrayInDeclarationOrder() {
        val annotation = annotationFrom(
            """
            @interface Select { String[] value(); }
            class Mapper {
                @Select({"SELECT *", "FROM users", "WHERE id = 1"})
                void find() {}
            }
            """.trimIndent()
        )

        assertEquals("SELECT * FROM users WHERE id = 1", AnnotationSqlExtractor.extract(annotation))
    }

    fun testResolvesConstantFieldReference() {
        val annotation = annotationFrom(
            """
            @interface Select { String[] value(); }
            class Mapper {
                static final String SQL = "SELECT 1";
                @Select(SQL)
                void find() {}
            }
            """.trimIndent()
        )

        assertEquals("SELECT 1", AnnotationSqlExtractor.extract(annotation))
    }

    private fun annotationFrom(source: String): PsiAnnotation {
        val file = myFixture.configureByText("Mapper.java", source)
        return PsiTreeUtil.findChildOfType(file, PsiAnnotation::class.java)
            ?: error("test fixture did not contain an annotation")
    }
}
