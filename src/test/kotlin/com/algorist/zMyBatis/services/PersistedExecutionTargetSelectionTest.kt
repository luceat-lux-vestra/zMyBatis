package com.algorist.zMyBatis.services

import com.algorist.zMyBatis.core.execution.ExecutionTargetDescriptor
import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.ExplicitSchemaIdentity
import com.algorist.zMyBatis.core.execution.SourceTargetAssociation
import com.algorist.zMyBatis.core.execution.StableDataSourceId
import com.algorist.zMyBatis.core.source.SourceFileId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PersistedExecutionTargetSelectionTest {

    @Test
    fun `roundtrip preserves canonical source target and optional presentation metadata`() {
        val targetId = target("550e8400-e29b-41d4-a716-446655440000", "스키마|A")
        val selection = PersistedExecutionTargetSelection(
            association = SourceTargetAssociation(SourceFileId("vfs:file:///프로젝트/Mapper.xml"), targetId),
            descriptor = ExecutionTargetDescriptor(targetId, "운영|DB"),
        )

        val restored = requireNotNull(
            ExecutionTargetSelectionPersistenceFormat.decode(
                ExecutionTargetSelectionPersistenceFormat.encode(selection),
            ),
        )

        assertEquals(selection.association, restored.association)
        assertEquals(selection.descriptor.targetId, restored.descriptor.targetId)
        assertEquals("운영|DB", restored.descriptor.dataSourceDisplayName)
    }

    @Test
    fun `null display name roundtrips without entering target identity`() {
        val targetId = target("ds-1", "public")
        val selection = PersistedExecutionTargetSelection(
            SourceTargetAssociation(SourceFileId("vfs:file:///project/Mapper.xml"), targetId),
            ExecutionTargetDescriptor(targetId, null),
        )

        val restored = requireNotNull(
            ExecutionTargetSelectionPersistenceFormat.decode(
                ExecutionTargetSelectionPersistenceFormat.encode(selection),
            ),
        )

        assertNull(restored.descriptor.dataSourceDisplayName)
        assertEquals(selection.descriptor, restored.descriptor)
    }

    @Test
    fun `selection id is canonical lowercase sha256 of source identity`() {
        val first = ExecutionTargetSelectionPersistenceFormat.selectionId(
            SourceFileId("vfs:file:///project/A.xml"),
        )
        val second = ExecutionTargetSelectionPersistenceFormat.selectionId(
            SourceFileId("vfs:file:///project/B.xml"),
        )

        assertEquals(64, first.length)
        assertTrue(ExecutionTargetSelectionPersistenceFormat.isValidSelectionId(first))
        assertNotEquals(first, second)
    }

    @Test
    fun `mismatched source association and descriptor target is rejected`() {
        expectIllegalArgument {
            PersistedExecutionTargetSelection(
                association = SourceTargetAssociation(
                    SourceFileId("vfs:file:///project/Mapper.xml"),
                    target("ds-1", "public"),
                ),
                descriptor = ExecutionTargetDescriptor(target("ds-2", "public"), "other"),
            )
        }
    }

    @Test
    fun `malformed version base64 and blank authoritative fields fail closed`() {
        assertNull(ExecutionTargetSelectionPersistenceFormat.decode("v2|YQ|Yg|Yw|ZA"))
        assertNull(ExecutionTargetSelectionPersistenceFormat.decode("v3|***|Yg|Yw|ZA"))
        assertNull(ExecutionTargetSelectionPersistenceFormat.decode("v3||ZHMtMQ|cHVibGlj|~"))
        assertNull(ExecutionTargetSelectionPersistenceFormat.decode("v3|c291cmNl||cHVibGlj|~"))
        assertNull(ExecutionTargetSelectionPersistenceFormat.decode("v3|c291cmNl|ZHMtMQ||~"))
    }

    @Test
    fun `legacy v2 conversion requires exact externally proven canonical source identity`() {
        val session = PersistedConsoleSession(
            mapperKey = "file:///project/Mapper.xml",
            dataSourceId = "ds-1",
            dataSourceName = "orders",
            schemaName = "public",
        )
        val canonical = SourceFileId("vfs:file:///project/Mapper.xml")

        val migrated = requireNotNull(
            ExecutionTargetSelectionPersistenceFormat.fromLegacyV2(session, canonical),
        )

        assertEquals(canonical, migrated.association.sourceFileId)
        assertEquals("ds-1", migrated.descriptor.targetId.dataSourceId.value)
        assertEquals("public", migrated.descriptor.targetId.schema.value)
        assertEquals("orders", migrated.descriptor.dataSourceDisplayName)

        assertNull(ExecutionTargetSelectionPersistenceFormat.fromLegacyV2(session, null))
        assertNull(
            ExecutionTargetSelectionPersistenceFormat.fromLegacyV2(
                session,
                SourceFileId("vfs:file:///project/Other.xml"),
            ),
        )
    }

    @Test
    fun `selection id validator rejects uppercase wrong length and non hex`() {
        val valid = "a".repeat(64)
        assertTrue(ExecutionTargetSelectionPersistenceFormat.isValidSelectionId(valid))
        assertFalse(ExecutionTargetSelectionPersistenceFormat.isValidSelectionId("a".repeat(63)))
        assertFalse(ExecutionTargetSelectionPersistenceFormat.isValidSelectionId("A" + "a".repeat(63)))
        assertFalse(ExecutionTargetSelectionPersistenceFormat.isValidSelectionId("g" + "a".repeat(63)))
    }

    private fun target(dataSourceId: String, schema: String): ExecutionTargetId =
        ExecutionTargetId(
            dataSourceId = StableDataSourceId(dataSourceId),
            schema = ExplicitSchemaIdentity(schema),
        )

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
