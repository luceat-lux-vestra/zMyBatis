package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.execution.ExecutionTargetDescriptor
import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.ExplicitSchemaIdentity
import com.algorist.zMyBatis.core.execution.SourceTargetAssociation
import com.algorist.zMyBatis.core.execution.StableDataSourceId
import com.algorist.zMyBatis.core.execution.TargetResolutionFailure
import com.algorist.zMyBatis.core.execution.TargetResolutionFailureKind
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.services.PersistedExecutionTargetSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredExecutionTargetBridgeTest {

    @Test
    fun `missing selection does not invoke live target resolver`() {
        val source = SourceFileId("vfs:file:///project/Mapper.xml")
        var resolveCalls = 0
        val bridge = StoredExecutionTargetBridge.forTest(
            loadSelection = { null },
            saveSelection = { error("unexpected save") },
            removeSelection = { error("unexpected remove") },
            resolveDescriptor = {
                resolveCalls++
                error("unexpected resolve")
            },
        )

        assertEquals(StoredExecutionTargetResolution.Missing, bridge.resolve(source))
        assertEquals(0, resolveCalls)
    }

    @Test
    fun `failed exact target resolution invalidates stored selection`() {
        val source = SourceFileId("vfs:file:///project/Mapper.xml")
        val selection = selection(source, "ds-1", "public", "orders")
        var removed: SourceFileId? = null
        val bridge = StoredExecutionTargetBridge.forTest(
            loadSelection = { selection },
            saveSelection = { error("unexpected save") },
            removeSelection = { removed = it },
            resolveDescriptor = {
                DatabaseToolsTargetResolution.Failed(
                    TargetResolutionFailure(
                        TargetResolutionFailureKind.DATA_SOURCE_MISSING,
                        "target-resolution-data-source-missing",
                    ),
                )
            },
        )

        val result = bridge.resolve(source)

        assertTrue(result is StoredExecutionTargetResolution.Invalid)
        assertEquals(source, removed)
    }

    @Test
    fun `remember identity persists stable datasource and explicit schema only`() {
        val source = SourceFileId("vfs:file:///project/Mapper.xml")
        var saved: PersistedExecutionTargetSelection? = null
        var removed: SourceFileId? = null
        val bridge = StoredExecutionTargetBridge.forTest(
            loadSelection = { null },
            saveSelection = { saved = it },
            removeSelection = { removed = it },
            resolveDescriptor = { error("unexpected resolve") },
        )

        assertTrue(
            bridge.rememberIdentity(
                sourceFileId = source,
                stableDataSourceId = " ds-1 ",
                dataSourceDisplayName = "orders",
                schemaName = " public ",
            ),
        )

        val persisted = requireNotNull(saved)
        assertEquals(source, persisted.association.sourceFileId)
        assertEquals("ds-1", persisted.descriptor.targetId.dataSourceId.value)
        assertEquals(" public ", persisted.descriptor.targetId.schema.value)
        assertEquals("orders", persisted.descriptor.dataSourceDisplayName)
        assertNull(removed)
    }

    @Test
    fun `default schema or missing stable id clears prior persisted selection`() {
        val source = SourceFileId("vfs:file:///project/Mapper.xml")
        val removed = mutableListOf<SourceFileId>()
        var saveCalls = 0
        val bridge = StoredExecutionTargetBridge.forTest(
            loadSelection = { null },
            saveSelection = { saveCalls++ },
            removeSelection = { removed += it },
            resolveDescriptor = { error("unexpected resolve") },
        )

        assertFalse(
            bridge.rememberIdentity(
                sourceFileId = source,
                stableDataSourceId = "ds-1",
                dataSourceDisplayName = "orders",
                schemaName = null,
            ),
        )
        assertFalse(
            bridge.rememberIdentity(
                sourceFileId = source,
                stableDataSourceId = null,
                dataSourceDisplayName = "orders",
                schemaName = "public",
            ),
        )

        assertEquals(0, saveCalls)
        assertEquals(listOf(source, source), removed)
    }

    private fun selection(
        source: SourceFileId,
        dataSourceId: String,
        schema: String,
        displayName: String,
    ): PersistedExecutionTargetSelection {
        val targetId = ExecutionTargetId(
            StableDataSourceId(dataSourceId),
            ExplicitSchemaIdentity(schema),
        )
        return PersistedExecutionTargetSelection(
            SourceTargetAssociation(source, targetId),
            ExecutionTargetDescriptor(targetId, displayName),
        )
    }
}
