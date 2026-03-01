package com.algorist.zMyBatis

import org.junit.Test
import org.junit.Assert.assertEquals

class MyBatisEvaluatorExceptionDebug {

    @Test
    fun `test evaluate with if test expression syntax error`() {
        // The user provided <if test="#{test} == 1">.
        // In MyBatis, #{...} is for parameter substitution in SQL, NOT for OGNL expression in <if test="...">.
        // <if test="test == 1"> is correct.
        // If user writes <if test="#{test} == 1">, OGNL tries to evaluate "#{test} == 1".
        // It seems OGNL or MyBatis might be confused.
        // Let's reproduce the exception.

        val xml = """
            <select id="findById" parameterType="long" resultMap="CustomerResultMap">
                SELECT
                    <if test="#{test} == 1">
                        id
                    </if>
                    <if test="#{test} == 2">
                        name
                    </if>
                FROM customers c
                            WHERE id = #{test}
            </select>
        """.trimIndent()

        // params: test = 1
        val params = mapOf("test" to 1)

        val result = MyBatisEvaluator.evaluate(xml, params)
        println("Result: $result")

        // If it returns error message, we can see the exception.
        // The user says: java.lang.NumberFormatException: For input string: "{1=null}"
        // This looks like it tries to parse "{1=null}" as a number.
        // Where does "{1=null}" come from? It looks like a map toString()?
        // If params map is {test=1}, maybe usage of #{test} in expression causes weird parsing?
    }
}

