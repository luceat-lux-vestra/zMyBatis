package com.algorist.zMyBatis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParameterExtractorBoundaryTest {

    @Test
    fun `nested foreach collection requires structured root input`() {
        val xml = """
            <foreach collection="request.ids" item="id" open="(" separator="," close=")">
                #{id}
            </foreach>
        """.trimIndent()

        val result = ParameterExtractor.extractResult(xml)

        assertEquals(listOf("request"), result.params)
        assertTrue(result.objectParams.contains("request"))
        assertFalse(result.params.contains("id"))
    }

    @Test
    fun `indexed hash placeholder resolves root rather than bracket expression`() {
        val result = ParameterExtractor.extractResult("SELECT #{items[0].id}")

        assertEquals(listOf("items"), result.params)
        assertEquals(setOf("items"), result.objectParams)
    }

    @Test
    fun `indexed dollar placeholder resolves structured root`() {
        val result = ParameterExtractor.extractResult("SELECT ${'$'}{items[0].column}")

        assertEquals(listOf("items"), result.params)
        assertEquals(setOf("items"), result.objectParams)
    }

    @Test
    fun `numeric indexed ognl path stays one structured root`() {
        val result = ParameterExtractor.extractResult(
            """<if test="items[0].active">AND active = 1</if>"""
        )

        assertEquals(listOf("items"), result.params)
        assertEquals(setOf("items"), result.objectParams)
    }

    @Test
    fun `placeholder options do not become parameter names`() {
        val result = ParameterExtractor.extractResult(
            "SELECT * FROM users WHERE id = #{user.id,jdbcType=BIGINT}"
        )

        assertEquals(listOf("user"), result.params)
        assertEquals(setOf("user"), result.objectParams)
    }
}
