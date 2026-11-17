package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeachDynamicTopologyIntegrationTest {
    @Test
    fun foreachStructuralAttributeSynthesisFailsAtTheTopologyGate() {
        assertTopologyFailure(
            """
                <script>
                  <foreach collection="unproven" item="item" open="#{">
                    item}
                  </foreach>
                </script>
            """.trimIndent(),
        )
    }

    @Test
    fun nonForeachDynamicFragmentSynthesisFailsAtTheTopologyGate() {
        assertTopologyFailure(
            """
                <script>
                  <trim>
                    <if test="unproven">#{</if>
                    id}
                  </trim>
                </script>
            """.trimIndent(),
        )
    }

    private fun assertTopologyFailure(script: String) {
        val failure = DynamicBoundTokenTopologyAdmission.failureOrNull(script)
        assertTrue(failure != null)
        requireNotNull(failure)
        assertEquals(PreparationFailureKind.UNSUPPORTED_SEMANTIC, failure.kind)
        assertEquals("java-annotation-dynamic-bound-token-topology-unsupported", failure.code)
    }
}
