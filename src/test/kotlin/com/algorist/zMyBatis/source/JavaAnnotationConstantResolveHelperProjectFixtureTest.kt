package com.algorist.zMyBatis.source

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiReferenceExpression
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.util.PathUtil
import org.apache.ibatis.annotations.Select
import java.io.File

class JavaAnnotationConstantResolveHelperProjectFixtureTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor() = JAVA_21

    override fun setUp() {
        super.setUp()
        val myBatisJar = File(PathUtil.getJarPathForClass(Select::class.java))
        PsiTestUtil.addLibrary(module, "mybatis-3.5.19", myBatisJar.parent, myBatisJar.name)
    }

    fun testResolveHelperResolvesRealMyBatisAnnotationConstants() {
        myFixture.addClass(
            """
            package fixture;

            public final class ResolutionConstants {
                public static final String SQL = "SELECT helper";
                public static final String A = ResolutionConstants.B;
                public static final String B = ResolutionConstants.A;
                private ResolutionConstants() {}
            }
            """.trimIndent(),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val mapperFile = myFixture.configureByText(
            JavaFileType.INSTANCE,
            """
            package fixture;

            import static fixture.ResolutionConstants.SQL;
            import org.apache.ibatis.annotations.Select;

            interface ResolveHelperMapper {
                @Select(ResolutionConstants.SQL)
                Object qualified();

                @Select(SQL)
                Object staticImported();

                @Select(ResolutionConstants.A)
                Object cyclic();
            }
            """.trimIndent(),
        ) as PsiJavaFile

        val qualified = annotationReference(mapperFile, "qualified")
        val qualifiedField = resolveField(qualified)
        assertEquals("fixture.ResolutionConstants", qualifiedField.containingClass?.qualifiedName)
        assertEquals("SQL", qualifiedField.name)
        assertEquals("SELECT helper", qualifiedField.computeConstantValue())

        val staticImported = annotationReference(mapperFile, "staticImported")
        val staticImportedField = resolveField(staticImported)
        assertEquals("fixture.ResolutionConstants", staticImportedField.containingClass?.qualifiedName)
        assertEquals("SQL", staticImportedField.name)

        val cyclic = annotationReference(mapperFile, "cyclic")
        val fieldA = resolveField(cyclic)
        assertEquals("A", fieldA.name)
        val referenceToB = fieldA.initializer as? PsiReferenceExpression
            ?: throw AssertionError("field A initializer must remain a reference expression")
        val fieldB = resolveField(referenceToB)
        assertEquals("B", fieldB.name)
        val referenceBackToA = fieldB.initializer as? PsiReferenceExpression
            ?: throw AssertionError("field B initializer must remain a reference expression")
        assertEquals("A", resolveField(referenceBackToA).name)
    }

    private fun annotationReference(file: PsiJavaFile, methodName: String): PsiReferenceExpression {
        val method = file.classes.single().findMethodsByName(methodName, false).single()
        val annotation = method.getAnnotation("org.apache.ibatis.annotations.Select")
            ?: throw AssertionError("real MyBatis @Select must resolve")
        return annotation.findAttributeValue("value") as? PsiReferenceExpression
            ?: throw AssertionError("@Select value for $methodName must remain a reference expression")
    }

    private fun resolveField(reference: PsiReferenceExpression): PsiField {
        val direct = reference.resolve() as? PsiField
        if (direct != null) return direct
        return JavaPsiFacade.getInstance(project)
            .resolveHelper
            .resolveReferencedVariable(reference.text, reference) as? PsiField
            ?: throw AssertionError("resolve helper could not resolve '${reference.text}' to a field")
    }
}
