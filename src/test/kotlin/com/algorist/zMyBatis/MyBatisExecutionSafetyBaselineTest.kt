package com.algorist.zMyBatis

import org.junit.Assert.assertEquals
import org.junit.Test

class MyBatisExecutionSafetyBaselineTest {

    @Test
    fun `evaluator returns common read mutation ddl and unknown families as ordinary sql strings`() {
        val cases = listOf(
            "SELECT 1",
            "INSERT INTO audit_log(id) VALUES (1)",
            "UPDATE audit_log SET id = 2 WHERE id = 1",
            "DELETE FROM audit_log WHERE id = 1",
            "CREATE TABLE safety_probe (id INTEGER)",
            "CALL maintenance_proc()"
        )

        cases.forEach { sql ->
            assertEquals(sql, MyBatisEvaluator.evaluate(sql, emptyMap()))
        }
    }

    @Test
    fun `mapper statement wrapper does not constrain evaluated sql semantics`() {
        assertEquals(
            "DELETE FROM audit_log WHERE id = 7",
            MyBatisEvaluator.evaluate(
                "<select>DELETE FROM audit_log WHERE id = #{id}</select>",
                mapOf("id" to 7)
            )
        )
        assertEquals(
            "SELECT * FROM audit_log WHERE id = 7",
            MyBatisEvaluator.evaluate(
                "<delete>SELECT * FROM audit_log WHERE id = #{id}</delete>",
                mapOf("id" to 7)
            )
        )
    }

    @Test
    fun `raw interpolation preserves statement shaped text without a typed safety boundary`() {
        val sql = "SELECT * FROM users WHERE ${'$'}{predicate}"
        val raw = "1 = 1; DELETE FROM audit_log; --"

        assertEquals(
            "SELECT * FROM users WHERE 1 = 1; DELETE FROM audit_log; --",
            MyBatisEvaluator.evaluate(sql, mapOf("predicate" to raw))
        )
    }
}
