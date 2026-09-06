package com.algorist.zMyBatis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MyBatisEvaluatorDynamicTagBaselineTest {

    @Test
    fun `choose selects matching when and otherwise fallback`() {
        val xml = """
            <script>
            SELECT
            <choose>
                <when test='kind == "A"'>'A'</when>
                <when test='kind == "B"'>'B'</when>
                <otherwise>'OTHER'</otherwise>
            </choose>
            AS kind_value
            </script>
        """.trimIndent()

        assertEquals(
            "SELECT 'B' AS kind_value",
            MyBatisEvaluator.evaluate(xml, mapOf("kind" to "B"))
        )
        assertEquals(
            "SELECT 'OTHER' AS kind_value",
            MyBatisEvaluator.evaluate(xml, mapOf("kind" to "C"))
        )
    }

    @Test
    fun `set trims trailing comma from dynamic assignments`() {
        val xml = """
            <update>
            UPDATE users
            <set>
                <if test="name != null">name = #{name},</if>
                <if test="active != null">active = #{active},</if>
            </set>
            WHERE id = #{id}
            </update>
        """.trimIndent()

        assertEquals(
            "UPDATE users SET name = 'Alice', active = 1 WHERE id = 7",
            MyBatisEvaluator.evaluate(xml, mapOf("name" to "Alice", "active" to true, "id" to 7))
        )
    }

    @Test
    fun `trim applies prefix and removes leading override`() {
        val xml = """
            <script>
            SELECT * FROM users
            <trim prefix="WHERE" prefixOverrides="AND |OR ">
                OR id = #{id}
            </trim>
            </script>
        """.trimIndent()

        assertEquals(
            "SELECT * FROM users WHERE id = 7",
            MyBatisEvaluator.evaluate(xml, mapOf("id" to 7))
        )
    }

    @Test
    fun `bind creates additional parameter consumed by bound mapping`() {
        val xml = """
            <script>
            <bind name="pattern" value="'%' + name + '%'" />
            SELECT * FROM users WHERE name LIKE #{pattern}
            </script>
        """.trimIndent()

        assertEquals(
            "SELECT * FROM users WHERE name LIKE '%Alice%'",
            MyBatisEvaluator.evaluate(xml, mapOf("name" to "Alice"))
        )
    }

    @Test
    fun `unresolved include dependency returns current evaluator error baseline`() {
        val xml = """
            <select id="findUser">
            SELECT <include refid="Base_Column_List" /> FROM users
            </select>
        """.trimIndent()

        val result = MyBatisEvaluator.evaluate(xml, emptyMap())

        assertTrue("actual result: <$result>", result.startsWith("-- [MyBatis Plugin Error]"))
        assertTrue("actual result: <$result>", result.contains("<include"))
    }
}
