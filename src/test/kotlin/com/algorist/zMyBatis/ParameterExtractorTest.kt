package com.algorist.zMyBatis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests that [ParameterExtractor] correctly identifies only root parameter names,
 * never sub-properties from dot-separated OGNL paths.
 */
class ParameterExtractorTest {

    // ── #{param} extraction ───────────────────────────────────────────────────

    @Test
    fun `simple param`() {
        val result = ParameterExtractor.extract("SELECT * FROM users WHERE id = #{id}")
        assertEquals(listOf("id"), result)
    }

    @Test
    fun `dot-path extracts root only from sql param`() {
        val result = ParameterExtractor.extract("SELECT * FROM users WHERE id = #{user.id} AND name = #{user.name}")
        assertEquals(listOf("user"), result)
    }

    @Test
    fun `deep dot-path extracts root only`() {
        val result = ParameterExtractor.extract("SELECT * FROM t WHERE city = #{user.address.city}")
        assertEquals(listOf("user"), result)
    }

    // ── OGNL test="" extraction ───────────────────────────────────────────────

    @Test
    fun `ognl simple identifier`() {
        val xml = """<if test="status != null">status = #{status}</if>"""
        val result = ParameterExtractor.extract(xml)
        assertEquals(listOf("status"), result)
    }

    @Test
    fun `ognl dot-path extracts root only - not sub property`() {
        val xml = """<if test="user.name != null">name = #{user.name}</if>"""
        val result = ParameterExtractor.extract(xml)
        // "name" must NOT appear — it's a sub-property of "user", not a root parameter
        assertEquals(listOf("user"), result)
        assertFalse("Sub-property 'name' should not be extracted", result.contains("name"))
    }

    @Test
    fun `ognl deep dot-path extracts root only`() {
        val xml = """<if test="user.address.city != null">city = #{user.address.city}</if>"""
        val result = ParameterExtractor.extract(xml)
        assertEquals(listOf("user"), result)
        assertFalse(result.contains("address"))
        assertFalse(result.contains("city"))
    }

    @Test
    fun `ognl method call on path extracts root only`() {
        // user.getName() — "getName" should not appear as a parameter
        val xml = """<if test="user.getName() != null">name = #{user.name}</if>"""
        val result = ParameterExtractor.extract(xml)
        assertEquals(listOf("user"), result)
    }

    @Test
    fun `ognl size method not extracted`() {
        val xml = """<if test="items.size > 0">ok</if>"""
        val result = ParameterExtractor.extract(xml)
        assertEquals(listOf("items"), result)
    }

    @Test
    fun `multiple independent roots from ognl`() {
        val xml = """
            <if test="user.name != null and status != null">
                name = #{user.name} AND status = #{status}
            </if>
        """.trimIndent()
        val result = ParameterExtractor.extract(xml)
        assertEquals(listOf("status", "user"), result)  // sorted
    }

    // ── foreach bound variables excluded ──────────────────────────────────────

    @Test
    fun `foreach item and index excluded`() {
        val xml = """
            <foreach item="item" index="idx" collection="list" open="(" separator="," close=")">
                #{item.name}
            </foreach>
        """.trimIndent()
        val result = ParameterExtractor.extract(xml)
        // "item" and "idx" are loop variables, not caller-supplied
        assertEquals(listOf("list"), result)
        assertFalse(result.contains("item"))
        assertFalse(result.contains("idx"))
        assertFalse(result.contains("name"))
    }

    // ── bind variables excluded ───────────────────────────────────────────────

    @Test
    fun `bind variable excluded`() {
        val xml = """
            <bind name="pattern" value="'%' + keyword + '%'" />
            SELECT * FROM t WHERE name LIKE #{pattern}
        """.trimIndent()
        val result = ParameterExtractor.extract(xml)
        assertTrue(result.contains("keyword"))
        assertFalse("bind variable 'pattern' should be excluded", result.contains("pattern"))
    }

    // ── Mixed scenario ────────────────────────────────────────────────────────

    @Test
    fun `complex mixed scenario - only roots extracted`() {
        val xml = """
            <script>
            SELECT * FROM orders
            <where>
                <if test="user.name != null">
                    owner_name = #{user.name}
                </if>
                <if test="status != null">
                    AND status = #{status}
                </if>
                AND id IN
                <foreach collection="ids" item="id" open="(" separator="," close=")">
                    #{id}
                </foreach>
            </where>
            </script>
        """.trimIndent()
        val result = ParameterExtractor.extract(xml)
        // Expected roots: ids, status, user (sorted)
        // NOT expected: name, id (sub-property / foreach variable)
        assertEquals(listOf("ids", "status", "user"), result)
    }

    // ── objectParams detection ────────────────────────────────────────────────

    @Test
    fun `extractResult - OGNL-only dot-path fills objectParams`() {
        // No #{user.name} in SQL, but OGNL test has dot-notation → user must be in objectParams
        val xml = """<if test="user.name != null">AND 1=1</if>"""
        val result = ParameterExtractor.extractResult(xml)
        assertEquals(listOf("user"), result.params)
        assertTrue("user must be in objectParams when accessed via dot in OGNL",
            result.objectParams.contains("user"))
    }

    @Test
    fun `extractResult - simple scalar not in objectParams`() {
        val xml = """<if test="status != null">status = #{status}</if>"""
        val result = ParameterExtractor.extractResult(xml)
        assertTrue(result.params.contains("status"))
        assertFalse("Plain scalar 'status' should NOT be in objectParams",
            result.objectParams.contains("status"))
    }

    @Test
    fun `extractResult - dot-path from sql param fills objectParams`() {
        val xml = "SELECT * FROM users WHERE id = #{user.id} AND name = #{user.name}"
        val result = ParameterExtractor.extractResult(xml)
        assertEquals(listOf("user"), result.params)
        assertTrue(result.objectParams.contains("user"))
    }

    @Test
    fun `extractResult mixed object and scalar params classified correctly`() {
        val xml = """
            <if test="user.name != null">
                owner = #{user.name}
            </if>
            AND status = #{status}
        """.trimIndent()
        val result = ParameterExtractor.extractResult(xml)
        assertEquals(listOf("status", "user"), result.params)
        assertTrue(result.objectParams.contains("user"))
        assertFalse(result.objectParams.contains("status"))
    }
}

