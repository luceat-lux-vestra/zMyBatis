package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class P2OgnlSafetyAdmissionTest {
    @Test
    fun standardPropertyExpressionsRemainStockOwned() {
        val script = """
            <script>
                <bind name="pattern" value="'%' + name + '%'"/>
                SELECT id FROM products
                <where>
                    <if test="user.id != null and ids.size > 0">
                        AND owner_id = #{user.id}
                    </if>
                    <foreach collection="ids" item="id" open=" AND id IN (" separator="," close=")">
                        #{id}
                    </foreach>
                </where>
            </script>
        """.trimIndent()

        assertNull(DynamicOgnlSafetyAdmission.failureForScript(script))
    }

    @Test
    fun executableOgnlCapabilitiesRemainRejectedBeforeEvaluation() {
        val expressions = listOf(
            "@java.lang.System@getProperty('user.home')",
            "new java.lang.String('x')",
            "name.trim()",
            "name.class",
        )

        expressions.forEach { expression ->
            val failure = DynamicOgnlSafetyAdmission.failureForExpressions(listOf(expression))
            requireNotNull(failure)
            assertEquals(PreparationFailureKind.UNSUPPORTED_SEMANTIC, failure.kind)
            assertEquals("mybatis-ognl-runtime-capability-unsupported", failure.code)
            assertTrue(failure.bindingProperty?.isNotBlank() == true)
        }
    }

    @Test
    fun malformedOgnlRemainsTypedWithoutCallerPropertyAllowlist() {
        val failure = DynamicOgnlSafetyAdmission.failureForExpressions(listOf("("))
        requireNotNull(failure)
        assertEquals(PreparationFailureKind.OGNL, failure.kind)
        assertEquals("mybatis-ognl-expression-malformed", failure.code)
    }

    @Test
    fun reservedBindContextMutationRemainsRejected() {
        val failure = DynamicOgnlSafetyAdmission.failureForScript(
            """
                <script>
                    <bind name="_parameter" value="name"/>
                    SELECT 1
                </script>
            """.trimIndent(),
        )
        requireNotNull(failure)
        assertEquals(PreparationFailureKind.UNSUPPORTED_SEMANTIC, failure.kind)
        assertEquals("mybatis-ognl-runtime-capability-unsupported", failure.code)
        assertEquals("Bind[reserved-context]", failure.bindingProperty)
    }
}
