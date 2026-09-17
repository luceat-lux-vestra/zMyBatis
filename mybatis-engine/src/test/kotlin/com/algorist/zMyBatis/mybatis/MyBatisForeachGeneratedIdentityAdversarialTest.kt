package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.InternalBindingKind
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MyBatisForeachGeneratedIdentityAdversarialTest {
    @Test
    fun generatedRootOutsideAdmittedLocalSetIsNeverClaimed() {
        val result = IsolatedDynamicMyBatisPreparation.prepare(
            script = """
                <script>
                  select
                  <foreach collection="ids" item="item" separator=",">#{item}</foreach>
                </script>
            """.trimIndent(),
            parameterType = MyBatisParameterType.Multi,
            parameterValues = mapOf("ids" to listOf(41L)),
            admittedForeachLocals = mapOf(
                "differentLocal" to InternalBindingKind.FOREACH_ITEM,
            ),
        )

        assertTrue(result is IsolatedDynamicMyBatisPreparation.Result.Ready)
        result as IsolatedDynamicMyBatisPreparation.Result.Ready
        val mapping = result.boundSql.mappings.single()

        assertTrue(mapping.additionalParameter)
        assertTrue(mapping.property?.startsWith("__frch_item_") == true)
        assertNull(mapping.generatedLocal)
    }
}
