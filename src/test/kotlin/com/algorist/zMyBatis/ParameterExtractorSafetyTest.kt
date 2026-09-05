package com.algorist.zMyBatis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParameterExtractorSafetyTest {

    @Test
    fun `dollar placeholder is caller supplied just like bound placeholder`() {
        val result = ParameterExtractor.extract(
            "SELECT * FROM users WHERE id = #{id} ORDER BY ${'$'}{orderBy}"
        )
        assertEquals(listOf("id", "orderBy"), result)
    }

    @Test
    fun `placeholder options keep only the root parameter identity`() {
        val result = ParameterExtractor.extractResult(
            "SELECT * FROM users WHERE id = #{user.id,jdbcType=BIGINT}"
        )
        assertEquals(listOf("user"), result.params)
        assertEquals(setOf("user"), result.objectParams)
    }

    @Test
    fun `empty and malformed placeholders do not invent parameters`() {
        val result = ParameterExtractor.extract(
            "SELECT '${'$'}{}' AS a, '#{   }' AS b, '#{' AS c"
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `identifiers inside OGNL string literals are not caller parameters`() {
        val xml = """<if test="status == 'user.name' and enabled == true">SELECT 1</if>"""
        assertEquals(listOf("enabled", "status"), ParameterExtractor.extract(xml))
    }

    @Test
    fun `static OGNL reference is excluded while real roots remain`() {
        val xml = """<if test="@java.lang.Math@PI > min and user.name != null">SELECT 1</if>"""
        val result = ParameterExtractor.extractResult(xml)
        assertEquals(listOf("min", "user"), result.params)
        assertTrue(result.objectParams.contains("user"))
        assertFalse(result.params.contains("java"))
        assertFalse(result.params.contains("PI"))
    }
}
