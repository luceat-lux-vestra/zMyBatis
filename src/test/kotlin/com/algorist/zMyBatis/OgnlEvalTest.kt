package com.algorist.zMyBatis

import org.junit.Assert.*
import org.junit.Test

class OgnlEvalTest {

    private val xml = """
        <select id="findByIdJSON" resultMap="CustomerResultMap">
            SELECT
            <if test="user.id == 1">
                id,
            </if>
            <if test="cust.name == 'test'">
                name
            </if>
            FROM customers c
            WHERE id = #{test}
        </select>
    """.trimIndent()

    /** Builds params the same way ParameterInputDialog.getValues() does: nested Maps only. */
    private fun buildParams(
        userJson: String,
        custJson: String,
        testVal: Long
    ): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        result["test"] = testVal
        result["user"] = JsonParameterParser.parseValue(userJson)
        result["cust"] = JsonParameterParser.parseValue(custJson)
        return result
    }

    @Test
    fun `extractResult - params and objectParams for mixed ognl and sql params`() {
        val result = ParameterExtractor.extractResult(xml)
        assertTrue(result.params.contains("cust"))
        assertTrue(result.params.contains("user"))
        assertTrue(result.params.contains("test"))
        assertTrue(result.objectParams.contains("user"))
        assertTrue(result.objectParams.contains("cust"))
        assertFalse(result.objectParams.contains("test"))
    }

    @Test
    fun `evaluate - both if branches fire with nested params`() {
        val params = buildParams("""{"id": 1}""", """{"name": "test"}""", 42L)
        println("params: $params")
        val result = MyBatisEvaluator.evaluate(xml, params)
        println("SQL: $result")
        assertFalse(result.contains("-- [MyBatis Plugin Error]"))
        assertTrue("id, column expected", result.contains("id,"))
        assertTrue("name column expected", result.contains("name"))
        assertTrue("WHERE id = 42 expected", result.contains("42"))
    }

    @Test
    fun `evaluate - neither if branch fires`() {
        val params = buildParams("""{"id": 2}""", """{"name": "other"}""", 99L)
        val result = MyBatisEvaluator.evaluate(xml, params)
        println("SQL (no if): $result")
        assertFalse(result.contains("-- [MyBatis Plugin Error]"))
        assertFalse("id, should NOT appear", result.contains("id,"))
        assertTrue("WHERE id = 99 expected", result.contains("99"))
    }

    @Test
    fun `evaluate - missing objectParams - graceful`() {
        val params = mapOf<String, Any?>("test" to 42L)
        val result = MyBatisEvaluator.evaluate(xml, params)
        println("SQL (missing obj): $result")
        assertFalse(result.contains("-- [MyBatis Plugin Error]"))
    }

    @Test
    fun `evaluate - dot-notation hash-params resolve via nested Map`() {
        val paramXml = """
            <select>
                SELECT * FROM users WHERE id = #{user.id} AND name = #{user.name}
            </select>
        """.trimIndent()
        val params = mapOf<String, Any?>(
            "user" to JsonParameterParser.parseValue("""{"id": 1, "name": "Alice"}""")
        )
        println("params: $params")

        val result = MyBatisEvaluator.evaluate(paramXml, params)
        println("SQL: $result")
        assertFalse(result.contains("-- [MyBatis Plugin Error]"))
        assertTrue("id=1 expected", result.contains("= 1"))
        assertTrue("name='Alice' expected", result.contains("'Alice'"))
    }
}
