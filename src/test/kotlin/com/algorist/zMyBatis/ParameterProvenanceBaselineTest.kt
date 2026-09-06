package com.algorist.zMyBatis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization evidence for the source-only parameter discovery boundary.
 *
 * These tests deliberately describe current heuristic behavior. They do not claim
 * MyBatis runtime parameter provenance or make naming conventions authoritative.
 */
class ParameterProvenanceBaselineTest {

    @Test
    fun `generated param aliases are excluded while arg aliases remain source heuristics`() {
        val result = ParameterExtractor.extractResult(
            "SELECT #{param1}, #{param2}, #{arg0}, #{named}"
        )

        assertEquals(listOf("arg0", "named"), result.params)
        assertTrue(result.objectParams.isEmpty())
    }

    @Test
    fun `collection aliases are indistinguishable from ordinary source roots`() {
        val xml = """
            SELECT #{list}, #{collection}, #{array}, #{request.list}
            <foreach collection="list" item="item">
                #{item}
            </foreach>
        """.trimIndent()

        val result = ParameterExtractor.extractResult(xml)

        assertEquals(listOf("array", "collection", "list", "request"), result.params)
        assertEquals(setOf("request"), result.objectParams)
    }

    @Test
    fun `hash and dollar placeholders share the current discovery path`() {
        val bound = ParameterExtractor.extractResult("SELECT #{value}")
        val raw = ParameterExtractor.extractResult("SELECT ${'$'}{value}")

        assertEquals(bound, raw)
    }

    @Test
    fun `quoted and commented placeholders remain lexical false positives`() {
        val xml = """
            SELECT '#{quotedFake}' AS sample
            -- ${'$'}{commentFake}
            /* #{blockFake} */
            FROM dual
        """.trimIndent()

        val result = ParameterExtractor.extractResult(xml)

        assertEquals(listOf("blockFake", "commentFake", "quotedFake"), result.params)
        assertTrue(result.objectParams.isEmpty())
    }

    @Test
    fun `malformed placeholders without a closing brace are not discovered`() {
        val result = ParameterExtractor.extractResult(
            "SELECT #{broken, ${'$'}{alsoBroken"
        )

        assertTrue(result.params.isEmpty())
        assertTrue(result.objectParams.isEmpty())
    }

    @Test
    fun `large repeated source fixture is deterministic and deduplicated`() {
        val xml = buildString {
            repeat(1_000) { index ->
                append("#{p${index % 100}.value} ")
            }
        }
        val expected = (0 until 100).map { "p$it" }.sorted()

        val first = ParameterExtractor.extractResult(xml)
        val second = ParameterExtractor.extractResult(xml)

        assertEquals(expected, first.params)
        assertEquals(expected.toSet(), first.objectParams)
        assertEquals(first, second)
    }
}
