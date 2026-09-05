package com.algorist.zMyBatis.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PersistedConsoleSessionTest {

    @Test
    fun `roundtrip preserves unicode delimiters and explicit schema`() {
        val session = PersistedConsoleSession(
            mapperKey = "file:///프로젝트/a|b/Mapper.xml\nfragment",
            dataSourceId = "550e8400-e29b-41d4-a716-446655440000",
            dataSourceName = "운영|DB\nrenamed",
            schemaName = "스키마|A\nB"
        )

        assertEquals(session, ConsoleSessionPersistenceFormat.decode(ConsoleSessionPersistenceFormat.encode(session)))
    }

    @Test
    fun `session id is canonical sha256 lowercase hex`() {
        val id = ConsoleSessionPersistenceFormat.sessionId("abc")

        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            id
        )
        assertTrue(ConsoleSessionPersistenceFormat.isValidSessionId(id))
    }

    @Test
    fun `different mapper identities do not share a session id`() {
        assertNotEquals(
            ConsoleSessionPersistenceFormat.sessionId("file:///project/A.xml"),
            ConsoleSessionPersistenceFormat.sessionId("file:///project/B.xml")
        )
    }

    @Test
    fun `encode rejects blank authoritative identity`() {
        expectIllegalArgument {
            ConsoleSessionPersistenceFormat.encode(
                PersistedConsoleSession("", "ds-1", "db", "public")
            )
        }
        expectIllegalArgument {
            ConsoleSessionPersistenceFormat.encode(
                PersistedConsoleSession("file:///Mapper.xml", " ", "db", "public")
            )
        }
        expectIllegalArgument {
            ConsoleSessionPersistenceFormat.encode(
                PersistedConsoleSession("file:///Mapper.xml", "ds-1", "db", "")
            )
        }
    }

    @Test
    fun `legacy name based session is deliberately not decoded`() {
        assertNull(ConsoleSessionPersistenceFormat.decode("orders-db|||public"))
    }

    @Test
    fun `wrong version malformed base64 and truncated records fail closed`() {
        assertNull(ConsoleSessionPersistenceFormat.decode("v1|YQ|Yg|Yw|ZA"))
        assertNull(ConsoleSessionPersistenceFormat.decode("v2|***|Yg|Yw|ZA"))
        assertNull(ConsoleSessionPersistenceFormat.decode("v2|YQ|Yg|Yw"))
    }

    @Test
    fun `decoded blank mapper datasource or schema identity is rejected`() {
        assertNull(ConsoleSessionPersistenceFormat.decode("v2||ZHMtMQ|ZGItMQ|cHVibGlj"))
        assertNull(ConsoleSessionPersistenceFormat.decode("v2|ZmlsZTovLy9NYXBwZXIueG1s||ZGItMQ|cHVibGlj"))
        assertNull(ConsoleSessionPersistenceFormat.decode("v2|ZmlsZTovLy9NYXBwZXIueG1s|ZHMtMQ|ZGItMQ|"))
    }

    @Test
    fun `session id validator rejects wrong length uppercase and non hex`() {
        val valid = "a".repeat(64)
        assertTrue(ConsoleSessionPersistenceFormat.isValidSessionId(valid))
        assertFalse(ConsoleSessionPersistenceFormat.isValidSessionId("a".repeat(63)))
        assertFalse(ConsoleSessionPersistenceFormat.isValidSessionId("A" + "a".repeat(63)))
        assertFalse(ConsoleSessionPersistenceFormat.isValidSessionId("g" + "a".repeat(63)))
    }

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
