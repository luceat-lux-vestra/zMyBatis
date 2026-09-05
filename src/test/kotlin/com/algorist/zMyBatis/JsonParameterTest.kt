package com.algorist.zMyBatis

import com.google.gson.JsonSyntaxException
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JsonParameterTest {

    @Test
    fun `parseValue preserves nested object and array types`() {
        val value = JsonParameterParser.parseValue(
            """{"user":{"id":7,"active":true},"tags":["a",null,2]}"""
        ) as Map<*, *>

        val user = value["user"] as Map<*, *>
        assertEquals(7L, user["id"])
        assertEquals(true, user["active"])
        assertEquals(listOf("a", null, 2L), value["tags"])
    }

    @Test
    fun `parseValue preserves integer above double exactness boundary`() {
        assertEquals(9_007_199_254_740_993L, JsonParameterParser.parseValue("9007199254740993"))
    }

    @Test
    fun `parseValue preserves integer beyond long range as BigInteger`() {
        val value = JsonParameterParser.parseValue("9223372036854775808")
        assertTrue(value is BigInteger)
        assertEquals(BigInteger("9223372036854775808"), value)
    }

    @Test
    fun `whole decimal and scientific notation preserve existing Long semantics`() {
        assertEquals(1L, JsonParameterParser.parseValue("1.0"))
        assertEquals(1_000L, JsonParameterParser.parseValue("1e3"))
    }

    @Test
    fun `parseValue keeps fractional decimal input numeric`() {
        assertEquals(1.25, JsonParameterParser.parseValue("1.25"))
    }

    @Test
    fun `parseValue treats blank input as null`() {
        assertNull(JsonParameterParser.parseValue("   "))
    }

    @Test
    fun `parse rejects non object top level`() {
        try {
            JsonParameterParser.parse("[1,2]")
            fail("top-level array must not satisfy object-only parse contract")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `malformed json fails instead of guessing`() {
        try {
            JsonParameterParser.parseValue("{]")
            fail("malformed JSON must fail")
        } catch (_: JsonSyntaxException) {
            // expected
        }
    }
}
