package com.algorist.zMyBatis

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class AnnotationSqlExtractorProjectFixtureTest : LightJavaCodeInsightFixtureTestCase() {

    fun testCrossFileConstantReferencesResolveThroughRealJavaPsiAndProjectIndex() {
        myFixture.addClass(
            """
            package org.apache.ibatis.annotations;

            public @interface Select {
                String[] value();
            }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package fixture;

            public final class SqlConstants {
                public static final String BY_ID = "SELECT * FROM users WHERE id = #{id}";
                public static final String SELECT_HEAD = "SELECT id, name";
                public static final String ACTIVE_FROM = "FROM users WHERE active = 1";

                private SqlConstants() {}
            }
            """.trimIndent()
        )

        val indexedConstants = JavaPsiFacade.getInstance(project).findClass(
            "fixture.SqlConstants",
            GlobalSearchScope.projectScope(project)
        )
        assertNotNull("fixture.SqlConstants must be visible through the project Java index", indexedConstants)

        val mapperFile = myFixture.configureByText(
            JavaFileType.INSTANCE,
            """
            package fixture;

            import org.apache.ibatis.annotations.Select;

            class UserMapper {
                @Select(SqlConstants.BY_ID)
                Object findById() { return null; }

                @Select({SqlConstants.SELECT_HEAD, SqlConstants.ACTIVE_FROM})
                Object findActive() { return null; }
            }
            """.trimIndent()
        ) as PsiJavaFile

        val mapperClass = mapperFile.classes.single()
        val findById = mapperClass.findMethodsByName("findById", false).single()
        val byIdAnnotation = findById.getAnnotation("org.apache.ibatis.annotations.Select")
        assertNotNull(byIdAnnotation)

        val directValue = byIdAnnotation!!.findAttributeValue("value")
        assertTrue("direct constant must parse as a Java reference", directValue is PsiReferenceExpression)
        val directResolved = (directValue as PsiReferenceExpression).resolve()
        assertTrue("direct constant must resolve to the project field", directResolved is PsiField)
        val directField = directResolved as PsiField
        assertEquals("fixture.SqlConstants", directField.containingClass?.qualifiedName)
        assertEquals("BY_ID", directField.name)
        assertEquals(
            "SELECT * FROM users WHERE id = #{id}",
            AnnotationSqlExtractor.extract(byIdAnnotation)
        )

        val findActive = mapperClass.findMethodsByName("findActive", false).single()
        val activeAnnotation = findActive.getAnnotation("org.apache.ibatis.annotations.Select")
        assertNotNull(activeAnnotation)

        val arrayValue = activeAnnotation!!.findAttributeValue("value")
        assertTrue("array annotation value must use real Java PSI", arrayValue is PsiArrayInitializerMemberValue)
        val references = (arrayValue as PsiArrayInitializerMemberValue).initializers
        assertEquals(2, references.size)
        references.forEach { value ->
            assertTrue(value is PsiReferenceExpression)
            val resolved = (value as PsiReferenceExpression).resolve()
            assertTrue(resolved is PsiField)
            val field = resolved as PsiField
            assertEquals("fixture.SqlConstants", field.containingClass?.qualifiedName)
        }
        assertEquals(
            "SELECT id, name FROM users WHERE active = 1",
            AnnotationSqlExtractor.extract(activeAnnotation)
        )
    }
}
