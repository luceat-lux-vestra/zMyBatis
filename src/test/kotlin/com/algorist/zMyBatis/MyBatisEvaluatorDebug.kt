package com.algorist.zMyBatis

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull

class MyBatisEvaluatorDebug {

    @Test
    fun `test evaluate simple sql`() {
        val xml = "SELECT * FROM users WHERE id = #{id}"
        val params = mapOf("id" to 1)
        val result = MyBatisEvaluator.evaluate(xml, params)
        println("Result: $result")
        // Check if result contains error message
        assert(!result.contains("-- [MyBatis Plugin Error]"))
        assertEquals("SELECT * FROM users WHERE id = 1", result)
    }

    @Test
    fun `test evaluate dynamic sql`() {
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
        val params = mapOf("id" to 1)
        val result = MyBatisEvaluator.evaluate(xml, params)
        println("Result: $result")
        assert(!result.contains("-- [MyBatis Plugin Error]"))
        assertEquals("SELECT * FROM users WHERE id = 1", result)
    }

    @Test
    fun `test evaluate foreach`() {
        val xml = """
            <script>
            SELECT * FROM users WHERE id IN
            <foreach item="item" index="index" collection="list"
                open="(" separator="," close=")">
                #{item}
            </foreach>
            </script>
        """.trimIndent()
        val params = mapOf("list" to listOf(1, 2, 3))
        val result = MyBatisEvaluator.evaluate(xml, params)
        println("Result Foreach: $result")
        assertEquals("SELECT * FROM users WHERE id IN ( 1 , 2 , 3 )", result)
    }

    @Test
    fun `test evaluate foreach with null collection`() {
        // This usually throws exception in MyBatis if collection is null or not found
        val xml = """
            <script>
            SELECT * FROM users WHERE id IN
            <foreach item="item" collection="list" open="(" separator="," close=")">
                #{item}
            </foreach>
            </script>
        """.trimIndent()
        val params = mapOf<String, Any?>() // Empty params

        // This is expected to fail or return error message from evaluate() catch block
        val result = MyBatisEvaluator.evaluate(xml, params)
        println("Result Foreach Null: $result")
        assert(result.contains("-- [MyBatis Plugin Error]"))
    }

    @Test
    fun `test evaluate mixed text and dynamic element without wrapper tag`() {
        // Regression test for contextNode bug:
        // Input has leading SQL text + dynamic <where> element — no <script> or <select> wrapper.
        // Previously, the evaluator picked <where> as contextNode, discarding "SELECT * FROM users".
        val xml = """
            SELECT * FROM users
            <where>
                <if test="id != null">
                    id = #{id}
                </if>
            </where>
        """.trimIndent()
        val params = mapOf<String, Any?>("id" to 1)
        val result = MyBatisEvaluator.evaluate(xml, params)
        println("Result Mixed: $result")
        assert(!result.contains("-- [MyBatis Plugin Error]"))
        assertEquals("SELECT * FROM users WHERE id = 1", result)
    }
}
