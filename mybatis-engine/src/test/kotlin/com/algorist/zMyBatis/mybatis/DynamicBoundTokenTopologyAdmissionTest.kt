package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicBoundTokenTopologyAdmissionTest {
    @Test
    fun completeSourcePlaceholdersAndOrdinaryStructuralAttributesRemainSupported() {
        val failure = DynamicBoundTokenTopologyAdmission.failureOrNull(
            """
                <script>
                  select * from users
                  <where>
                    <if test="id != null">id = #{id}</if>
                  </where>
                  and id in
                  <foreach collection="ids" item="item" open="(" separator="," close=")">
                    #{item}
                  </foreach>
                </script>
            """.trimIndent(),
        )

        assertNull(failure)
    }

    @Test
    fun ordinaryBraceSyntaxDoesNotBecomeBoundTokenMetasyntax() {
        assertNull(
            DynamicBoundTokenTopologyAdmission.failureOrNull(
                "<script>select '{json}' <if test=\"enabled\">from users</if></script>",
            ),
        )
    }

    @Test
    fun foreachOpenCannotSynthesizeABoundTokenWithBodyText() {
        assertTopologyFailure(
            """
                <script>
                  select
                  <foreach collection="ids" item="item" open="#{" close="">
                    item}
                  </foreach>
                </script>
            """.trimIndent(),
        )
    }

    @Test
    fun trimChildrenCannotStitchABoundTokenAcrossDynamicNodes() {
        assertTopologyFailure(
            """
                <script>
                  <trim>
                    <if test="enabled">#{</if>
                    id}
                  </trim>
                </script>
            """.trimIndent(),
        )
    }

    @Test
    fun escapedBoundTokenSyntaxIsRejectedInsteadOfChangingMappingTopologyLater() {
        assertTopologyFailure(
            "<script>select \\#{id}</script>",
        )
    }

    private fun assertTopologyFailure(script: String) {
        val failure = DynamicBoundTokenTopologyAdmission.failureOrNull(script)
        assertTrue(failure != null)
        failure!!
        assertEquals(PreparationFailureKind.UNSUPPORTED_SEMANTIC, failure.kind)
        assertEquals("java-annotation-dynamic-bound-token-topology-unsupported", failure.code)
        assertEquals(null, failure.bindingProperty)
    }
}
