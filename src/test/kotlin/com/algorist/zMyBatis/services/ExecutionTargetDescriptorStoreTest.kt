package com.algorist.zMyBatis.services

import com.algorist.zMyBatis.core.execution.ExecutionTargetDescriptor
import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.ExplicitSchemaIdentity
import com.algorist.zMyBatis.core.execution.SourceTargetAssociation
import com.algorist.zMyBatis.core.execution.StableDataSourceId
import com.algorist.zMyBatis.core.source.SourceFileId
import com.intellij.ide.util.PropertiesComponent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ExecutionTargetDescriptorStoreTest : BasePlatformTestCase() {

    fun testSaveLoadAndDisplayRenamePreserveTargetIdentity() {
        val store = ExecutionTargetDescriptorStore.getInstance(project)
        val source = SourceFileId("vfs:file:///project/Mapper.xml")
        val targetId = target("ds-1", "public")

        store.save(
            SourceTargetAssociation(source, targetId),
            ExecutionTargetDescriptor(targetId, "orders"),
        )
        val first = requireNotNull(store.load(source))
        assertEquals(targetId, first.descriptor.targetId)
        assertEquals("orders", first.descriptor.dataSourceDisplayName)

        store.save(
            SourceTargetAssociation(source, targetId),
            ExecutionTargetDescriptor(targetId, "orders-renamed"),
        )
        val renamed = requireNotNull(store.load(source))

        assertEquals(targetId, renamed.descriptor.targetId)
        assertEquals("orders-renamed", renamed.descriptor.dataSourceDisplayName)
        assertEquals(
            listOf(ExecutionTargetSelectionPersistenceFormat.selectionId(source)),
            savedIds(),
        )
    }

    fun testSameSourceCanDeliberatelyMoveToOneDifferentTarget() {
        val store = ExecutionTargetDescriptorStore.getInstance(project)
        val source = SourceFileId("vfs:file:///project/Mapper.xml")
        val firstTarget = target("ds-1", "public")
        val secondTarget = target("ds-2", "app")

        store.save(
            SourceTargetAssociation(source, firstTarget),
            ExecutionTargetDescriptor(firstTarget, "first"),
        )
        store.save(
            SourceTargetAssociation(source, secondTarget),
            ExecutionTargetDescriptor(secondTarget, "second"),
        )

        val restored = requireNotNull(store.load(source))
        assertEquals(secondTarget, restored.association.targetId)
        assertEquals(secondTarget, restored.descriptor.targetId)
        assertEquals(1, savedIds().size)
    }

    fun testSaveRejectsAssociationDescriptorTargetMismatch() {
        val store = ExecutionTargetDescriptorStore.getInstance(project)
        val source = SourceFileId("vfs:file:///project/Mapper.xml")

        try {
            store.save(
                SourceTargetAssociation(source, target("ds-1", "public")),
                ExecutionTargetDescriptor(target("ds-2", "public"), "other"),
            )
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        assertTrue(savedIds().isEmpty())
    }

    fun testMalformedAndInterruptedIndexStateIsPrunedFailClosed() {
        val source = SourceFileId("vfs:file:///project/Missing.xml")
        val missingRecordId = ExecutionTargetSelectionPersistenceFormat.selectionId(source)
        projectStore().setValue(
            ExecutionTargetDescriptorStore.INDEX_KEY,
            "../../not-a-selection-id\n$missingRecordId",
        )

        assertTrue(ExecutionTargetDescriptorStore.getInstance(project).pruneAndLoadAll().isEmpty())
        assertNull(projectStore().getValue(ExecutionTargetDescriptorStore.INDEX_KEY))
        assertNull(
            projectStore().getValue(
                "${ExecutionTargetDescriptorStore.RECORD_PREFIX}$missingRecordId",
            ),
        )
    }

    fun testUnindexedRecordIsNotDirectlyLoadableAndIsPruned() {
        val source = SourceFileId("vfs:file:///project/Orphan.xml")
        val id = ExecutionTargetSelectionPersistenceFormat.selectionId(source)
        val targetId = target("ds-1", "public")
        val payload = PersistedExecutionTargetSelection(
            SourceTargetAssociation(source, targetId),
            ExecutionTargetDescriptor(targetId, "orders"),
        )
        val recordKey = "${ExecutionTargetDescriptorStore.RECORD_PREFIX}$id"
        projectStore().setValue(
            recordKey,
            ExecutionTargetSelectionPersistenceFormat.encode(payload),
        )

        assertNull(ExecutionTargetDescriptorStore.getInstance(project).load(source))
        assertNull(projectStore().getValue(recordKey))
        assertNull(projectStore().getValue(ExecutionTargetDescriptorStore.INDEX_KEY))
    }

    fun testRecordWhoseSourceDoesNotMatchIndexedIdentityIsPruned() {
        val indexedSource = SourceFileId("vfs:file:///project/Indexed.xml")
        val payloadSource = SourceFileId("vfs:file:///project/Payload.xml")
        val id = ExecutionTargetSelectionPersistenceFormat.selectionId(indexedSource)
        val targetId = target("ds-1", "public")
        val payload = PersistedExecutionTargetSelection(
            SourceTargetAssociation(payloadSource, targetId),
            ExecutionTargetDescriptor(targetId, "orders"),
        )

        projectStore().setValue(ExecutionTargetDescriptorStore.INDEX_KEY, id)
        projectStore().setValue(
            "${ExecutionTargetDescriptorStore.RECORD_PREFIX}$id",
            ExecutionTargetSelectionPersistenceFormat.encode(payload),
        )

        assertTrue(ExecutionTargetDescriptorStore.getInstance(project).pruneAndLoadAll().isEmpty())
        assertNull(projectStore().getValue(ExecutionTargetDescriptorStore.INDEX_KEY))
        assertNull(projectStore().getValue("${ExecutionTargetDescriptorStore.RECORD_PREFIX}$id"))
    }

    fun testApplicationScopedLookalikeStateIsNotProjectPersistenceAuthority() {
        val source = SourceFileId("vfs:file:///project/AppScoped.xml")
        val id = ExecutionTargetSelectionPersistenceFormat.selectionId(source)
        val targetId = target("ds-1", "public")
        val payload = PersistedExecutionTargetSelection(
            SourceTargetAssociation(source, targetId),
            ExecutionTargetDescriptor(targetId, "orders"),
        )
        val applicationStore = PropertiesComponent.getInstance()
        applicationStore.setValue(ExecutionTargetDescriptorStore.INDEX_KEY, id)
        applicationStore.setValue(
            "${ExecutionTargetDescriptorStore.RECORD_PREFIX}$id",
            ExecutionTargetSelectionPersistenceFormat.encode(payload),
        )

        try {
            assertTrue(ExecutionTargetDescriptorStore.getInstance(project).pruneAndLoadAll().isEmpty())
            assertNull(ExecutionTargetDescriptorStore.getInstance(project).load(source))
        } finally {
            applicationStore.unsetValue(ExecutionTargetDescriptorStore.INDEX_KEY)
            applicationStore.unsetValue("${ExecutionTargetDescriptorStore.RECORD_PREFIX}$id")
        }
    }

    fun testRemoveClearsRecordAndIndex() {
        val store = ExecutionTargetDescriptorStore.getInstance(project)
        val source = SourceFileId("vfs:file:///project/Mapper.xml")
        val targetId = target("ds-1", "public")

        store.save(
            SourceTargetAssociation(source, targetId),
            ExecutionTargetDescriptor(targetId, "orders"),
        )
        assertNotNull(store.load(source))

        store.remove(source)

        assertNull(store.load(source))
        assertTrue(savedIds().isEmpty())
    }

    override fun tearDown() {
        try {
            clearProjectStore()
        } finally {
            super.tearDown()
        }
    }

    private fun target(dataSourceId: String, schema: String): ExecutionTargetId =
        ExecutionTargetId(
            StableDataSourceId(dataSourceId),
            ExplicitSchemaIdentity(schema),
        )

    private fun projectStore(): PropertiesComponent = PropertiesComponent.getInstance(project)

    private fun savedIds(): List<String> =
        projectStore().getValue(ExecutionTargetDescriptorStore.INDEX_KEY)
            ?.lineSequence()
            ?.filter { it.isNotBlank() }
            ?.toList()
            .orEmpty()

    private fun clearProjectStore() {
        val ids = savedIds()
        ids.forEach {
            projectStore().unsetValue("${ExecutionTargetDescriptorStore.RECORD_PREFIX}$it")
        }
        projectStore().unsetValue(ExecutionTargetDescriptorStore.INDEX_KEY)
    }
}
