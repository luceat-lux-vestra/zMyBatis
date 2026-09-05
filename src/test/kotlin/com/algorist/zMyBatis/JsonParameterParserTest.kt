package com.algorist.zMyBatis

import com.google.gson.JsonSyntaxException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JsonParameterParserTest {

    @Test
    fun `parse preserves nested object array null and escaped string values`() {
        val parsed = JsonParameterParser.parse(
            """{"user":{"id":1,"name":"O'Reilly\nAdmin"},"items":[true,null,2.5]}"""
        )
        val user = parsed["user"] as Map<*, *>
        assertEquals(1L, user["id"])
        assertEquals("O'Reilly\nAdmin", user["name"])
        val items = parsed["items"] as List<*>
        assertEquals(true, items[0])
        assertNull(items[1])
        assertEquals(2.5, items[2] as Double, 0.0)
    }

    @Test
    fun `parseValue preserves integral precision above double safe integer`() {
        assertEquals(9_007_199_254_740_993L, JsonParameterParser.parseValue("9007199254740993"))
    }

    @Test
    fun `parseValue keeps whole exponent as Long and fractional number as Double`() {
        assertEquals(1_000L, JsonParameterParser.parseValue("1e3"))
        assertEquals(1.25, JsonParameterParser.parseValue("1.25") as Double, 0.0)
    }

    @Test
    fun `parseValue accepts every JSON value kind`() {
        assertEquals("text", JsonParameterParser.parseValue("\"text\""))
        assertEquals(false, JsonParameterParser.parseValue("false"))
        assertNull(JsonParameterParser.parseValue("null"))
        assertEquals(listOf(1L, 2L), JsonParameterParser.parseValue("[1,2]"))
        assertEquals(mapOf("id" to 7L), JsonParameterParser.parseValue("{\"id\":7}"))
        assertNull(JsonParameterParser.parseValue("   "))
    }

    @Test
    fun `flattenValue emits deterministic object and array paths`() {
        val flattened = JsonParameterParser.flattenValue(
            "payload",
            """{"user":{"id":7},"tags":["a","b"]}"""
        )
        assertEquals(
            linkedMapOf(
                "payload.user.id" to 7L,
                "payload.tags[0]" to "a",
                "payload.tags[1]" to "b"
            ),
            flattened
        )
    }

    @Test
    fun `malformed JSON fails instead of inventing a value`() {
        expectThrows<JsonSyntaxException> {
            JsonParameterParser.parseValue("{\"id\":")
        }
    }

    @Test
    fun `parse rejects a non object top level`() {
        val error = expectThrows<IllegalArgumentException> {
            JsonParameterParser.parse("[1,2,3]")
        }
        assertTrue(error.message.orEmpty().contains("top-level object"))
    }

    private inline fun <reified T : Throwable> expectThrows(block: () -> Unit): T {
        try {
            block()
            fail("expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
        error("unreachable")
    }
}
