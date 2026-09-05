package com.algorist.zMyBatis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MyBatisEvaluatorTest {

    @Test
    fun `bound parameter is SQL quoted while dollar parameter remains raw`() {
        val sql = "SELECT * FROM users WHERE name = #{name} ORDER BY ${'$'}{orderBy}"
        val result = MyBatisEvaluator.evaluate(
            sql,
            mapOf("name" to "O'Reilly", "orderBy" to "name DESC")
        )
        assertEquals("SELECT * FROM users WHERE name = 'O''Reilly' ORDER BY name DESC", result)
    }

    @Test
    fun `null and boolean bound parameters have deterministic literals`() {
        val result = MyBatisEvaluator.evaluate(
            "SELECT * FROM users WHERE active = #{active} AND deleted_at = #{deletedAt}",
            mapOf("active" to true, "deletedAt" to null)
        )
        assertEquals("SELECT * FROM users WHERE active = 1 AND deleted_at = NULL", result)
    }

    @Test
    fun `dynamic if uses MyBatis evaluation and produces exact SQL`() {
        val xml = """
            <script>
            SELECT * FROM users
            <where>
                <if test="id != null">
                    id = #{id}
                </if>
            </where>
            </script>
        """.trimIndent()
        assertEquals(
            "SELECT * FROM users WHERE id = 1",
            MyBatisEvaluator.evaluate(xml, mapOf("id" to 1))
        )
    }

    @Test
    fun `foreach expands collection values in order`() {
        val xml = """
            <script>
            SELECT * FROM users WHERE id IN
            <foreach item="item" collection="list" open="(" separator="," close=")">
                #{item}
            </foreach>
            </script>
        """.trimIndent()
        assertEquals(
            "SELECT * FROM users WHERE id IN ( 1 , 2 , 3 )",
            MyBatisEvaluator.evaluate(xml, mapOf("list" to listOf(1, 2, 3)))
        )
    }

    @Test
    fun `mixed text and dynamic element without wrapper keeps leading SQL`() {
        val xml = """
            SELECT * FROM users
            <where>
                <if test="id != null">
                    id = #{id}
                </if>
            </where>
        """.trimIndent()
        assertEquals(
            "SELECT * FROM users WHERE id = 1",
            MyBatisEvaluator.evaluate(xml, mapOf("id" to 1))
        )
    }

    @Test
    fun `list and object cannot be silently rendered as scalar literals`() {
        val listResult = MyBatisEvaluator.evaluate("SELECT #{items}", mapOf("items" to listOf(1, 2)))
        val mapResult = MyBatisEvaluator.evaluate("SELECT #{user}", mapOf("user" to mapOf("id" to 1)))
        assertEquals("SELECT /*[ERROR: List — use <foreach>]*/NULL", listResult)
        assertEquals("SELECT /*[ERROR: Object — use dot notation e.g. #{user.name}]*/NULL", mapResult)
    }

    @Test
    fun `missing foreach collection fails closed as plugin error SQL`() {
        val xml = """
            <script>
            SELECT * FROM users WHERE id IN
            <foreach item="item" collection="list" open="(" separator="," close=")">
                #{item}
            </foreach>
            </script>
        """.trimIndent()
        val result = MyBatisEvaluator.evaluate(xml, emptyMap())
        assertTrue(result.startsWith("-- [MyBatis Plugin Error]"))
    }

    @Test
    fun `malformed OGNL does not produce plausible SQL`() {
        val xml = """
            <script>
            SELECT * FROM users
            <if test="id +">WHERE id = #{id}</if>
            </script>
        """.trimIndent()
        val result = MyBatisEvaluator.evaluate(xml, mapOf("id" to 1))
        assertTrue(result.startsWith("-- [MyBatis Plugin Error]"))
        assertFalse(result == "SELECT * FROM users WHERE id = 1")
    }
}
