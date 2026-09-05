package com.algorist.zMyBatis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MyBatisEvaluatorContractTest {

    @Test
    fun `simple bound parameter renders exact SQL`() {
        val result = MyBatisEvaluator.evaluate(
            "SELECT * FROM users WHERE id = #{id}",
            mapOf("id" to 1)
        )

        assertEquals("SELECT * FROM users WHERE id = 1", result)
    }

    @Test
    fun `dynamic where renders exact SQL`() {
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
    fun `mixed leading SQL and dynamic tag preserve the leading SQL`() {
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
    fun `foreach renders collection members in order`() {
        val xml = """
            <script>
            SELECT * FROM users WHERE id IN
            <foreach item="item" index="index" collection="list"
                open="(" separator="," close=")">
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
    fun `numeric indexed ognl evaluates nested list element`() {
        val xml = """
            <script>
            SELECT * FROM users
            <where>
                <if test="items[0].active">
                    active = 1
                </if>
            </where>
            </script>
        """.trimIndent()
        val params = mapOf<String, Any?>(
            "items" to listOf(mapOf("active" to true))
        )

        assertEquals(
            "SELECT * FROM users WHERE active = 1",
            MyBatisEvaluator.evaluate(xml, params)
        )
    }

    @Test
    fun `indexed bound path resolves nested list element`() {
        val params = mapOf<String, Any?>(
            "items" to listOf(mapOf("id" to 7L))
        )

        assertEquals(
            "SELECT 7",
            MyBatisEvaluator.evaluate("SELECT #{items[0].id}", params)
        )
    }

    @Test
    fun `flat indexed key retains precedence over nested navigation`() {
        val params = linkedMapOf<String, Any?>(
            "items[0].id" to 99L,
            "items" to listOf(mapOf("id" to 7L))
        )

        assertEquals(
            "SELECT 99",
            MyBatisEvaluator.evaluate("SELECT #{items[0].id}", params)
        )
    }

    @Test
    fun `nested literal bracket key retains precedence over index navigation`() {
        val user = linkedMapOf<String, Any?>(
            "items[0]" to mapOf("id" to 88L),
            "items" to listOf(mapOf("id" to 7L))
        )
        val params = mapOf<String, Any?>("user" to user)

        assertEquals(
            "SELECT 88",
            MyBatisEvaluator.evaluate("SELECT #{user.items[0].id}", params)
        )
    }

    @Test
    fun `bound string is quoted and escaped while dollar substitution stays raw`() {
        val xml = "SELECT #{value} AS bound_value, ${'$'}{raw} AS raw_value"
        val params = mapOf("value" to "O'Reilly", "raw" to "CURRENT_TIMESTAMP")

        assertEquals(
            "SELECT 'O''Reilly' AS bound_value, CURRENT_TIMESTAMP AS raw_value",
            MyBatisEvaluator.evaluate(xml, params)
        )
    }

    @Test
    fun `null and booleans render deterministic literals`() {
        val xml = "SELECT #{missing} AS missing_value, #{yes} AS yes_value, #{no} AS no_value"

        assertEquals(
            "SELECT NULL AS missing_value, 1 AS yes_value, 0 AS no_value",
            MyBatisEvaluator.evaluate(xml, mapOf("missing" to null, "yes" to true, "no" to false))
        )
    }

    @Test
    fun `direct list and map placeholders fail visibly instead of guessing`() {
        val listSql = MyBatisEvaluator.evaluate("SELECT #{items}", mapOf("items" to listOf(1, 2)))
        val mapSql = MyBatisEvaluator.evaluate("SELECT #{user}", mapOf("user" to mapOf("id" to 1)))

        assertTrue(listSql.contains("/*[ERROR: List — use <foreach>]*/NULL"))
        assertTrue(mapSql.contains("/*[ERROR: Object — use dot notation"))
    }

    @Test
    fun `null foreach collection returns explicit plugin error`() {
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
    fun `hash placeholder accidentally used inside test attribute is sanitized deterministically`() {
        val xml = """
            <select>
                SELECT
                <if test="#{test} == 1">id</if>
                FROM customers
            </select>
        """.trimIndent()

        assertEquals(
            "SELECT id FROM customers",
            MyBatisEvaluator.evaluate(xml, mapOf("test" to 1))
        )
    }
}
