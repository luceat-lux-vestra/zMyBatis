package com.algorist.zMyBatis

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiReferenceExpression
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class AnnotationSqlExtractorTest {

    @Test
    fun `literal annotation value is returned unchanged`() {
        val sql = "SELECT * FROM users WHERE id = #{id}"
        assertEquals(sql, AnnotationSqlExtractor.extract(annotation(literal(sql))))
    }

    @Test
    fun `array annotation value preserves statement order`() {
        val value = psiProxy<PsiArrayInitializerMemberValue> { method, _ ->
            when (method.name) {
                "getInitializers" -> arrayOf<PsiAnnotationMemberValue>(
                    literal("SELECT id,"),
                    literal("name"),
                    literal("FROM users")
                )
                else -> null
            }
        }
        assertEquals("SELECT id, name FROM users", AnnotationSqlExtractor.extract(annotation(value)))
    }

    @Test
    fun `constant reference resolves through field initializer`() {
        val initializer = literal("SELECT * FROM users WHERE active = 1")
        val field = psiProxy<PsiField> { method, _ ->
            when (method.name) {
                "hasInitializer" -> true
                "getInitializer" -> initializer
                else -> null
            }
        }
        val reference = psiProxy<PsiReferenceExpression> { method, _ ->
            when (method.name) {
                "resolve" -> field
                else -> null
            }
        }
        assertEquals(
            "SELECT * FROM users WHERE active = 1",
            AnnotationSqlExtractor.extract(annotation(reference))
        )
    }

    @Test
    fun `null annotation fails closed`() {
        assertNull(AnnotationSqlExtractor.extract(null))
    }

    private fun annotation(value: PsiAnnotationMemberValue): PsiAnnotation =
        psiProxy { method, args ->
            when (method.name) {
                "findAttributeValue" -> if (args?.firstOrNull() == "value") value else null
                else -> null
            }
        }

    private fun literal(value: String): PsiLiteralExpression =
        psiProxy { method, _ ->
            when (method.name) {
                "getValue" -> value
                "getText" -> "\"$value\""
                else -> null
            }
        }

    private inline fun <reified T> psiProxy(
        crossinline answer: (Method, Array<out Any?>?) -> Any?
    ): T where T : Any {
        val type = T::class.java
        return type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                when (method.name) {
                    "toString" -> "${type.simpleName}Proxy"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> answer(method, args) ?: defaultValue(method.returnType)
                }
            }
        )
    }

    companion object {
        private fun defaultValue(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0F
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> '\u0000'
            else -> null
        }
    }
}
