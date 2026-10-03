package com.algorist.zMyBatis.startup

import com.algorist.zMyBatis.core.execution.ExecutionTargetDescriptor
import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.ExplicitSchemaIdentity
import com.algorist.zMyBatis.core.execution.SourceTargetAssociation
import com.algorist.zMyBatis.core.execution.StableDataSourceId
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.services.ConsoleCacheService
import com.algorist.zMyBatis.services.ConsoleSessionPersistenceFormat
import com.algorist.zMyBatis.services.ExecutionTargetDescriptorStore
import com.algorist.zMyBatis.services.LegacyV2ConsoleSessionMigrationStore
import com.algorist.zMyBatis.services.PersistedConsoleSession
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MyBatisActionInterceptorActivityLifecycleTest : BasePlatformTestCase() {

    companion object {
        private const val V2_INDEX = "zMyBatis.session.v2.__index__"
        private const val V2_RECORD_PREFIX = "zMyBatis.session.v2.record."
    }

    fun testStartupMigratesV2SelectionWithoutCreatingConsole() {
        val mapperKey = "file:///project/Mapper.xml"
        seedV2(mapperKey, "ds-1", "orders", "public")
        val cache = newIsolatedCache()
        val targetStore = ExecutionTargetDescriptorStore.getInstance(project)

        migrate(cache, targetStore) { it }

        val sourceFileId = SourceFileId("vfs:$mapperKey")
        val migrated = requireNotNull(targetStore.load(sourceFileId))
        assertEquals("ds-1", migrated.descriptor.targetId.dataSourceId.value)
        assertEquals("public", migrated.descriptor.targetId.schema.value)
        assertEquals("orders", migrated.descriptor.dataSourceDisplayName)
        assertNull(cache.get(mapperKey))
        assertV2Absent(mapperKey)
    }

    fun testStartupPrunesV2WhenCanonicalSourceCannotBeProven() {
        val mapperKey = "file:///project/Moved.xml"
        seedV2(mapperKey, "ds-1", "orders", "public")
        val cache = newIsolatedCache()
        val targetStore = ExecutionTargetDescriptorStore.getInstance(project)

        migrate(cache, targetStore) { null }

        assertNull(targetStore.load(SourceFileId("vfs:$mapperKey")))
        assertV2Absent(mapperKey)
    }

    fun testExistingV3WinsOverInterruptedDuplicateV2State() {
        val mapperKey = "file:///project/Mapper.xml"
        val sourceFileId = SourceFileId("vfs:$mapperKey")
        val targetStore = ExecutionTargetDescriptorStore.getInstance(project)
        saveV3(sourceFileId, "ds-new", "new_schema", "new")
        seedV2(mapperKey, "ds-old", "old", "old_schema")
        val cache = newIsolatedCache()

        migrate(cache, targetStore) { it }

        val retained = requireNotNull(targetStore.load(sourceFileId))
        assertEquals("ds-new", retained.descriptor.targetId.dataSourceId.value)
        assertEquals("new_schema", retained.descriptor.targetId.schema.value)
        assertV2Absent(mapperKey)
    }

    fun testStartupPrunesV3WhoseVfsSourceDisappeared() {
        val sourceFileId = SourceFileId("vfs:file:///project/Gone.xml")
        val targetStore = ExecutionTargetDescriptorStore.getInstance(project)
        saveV3(sourceFileId, "ds-1", "public", "orders")
        val cache = newIsolatedCache()

        migrate(cache, targetStore) { null }

        assertNull(targetStore.load(sourceFileId))
    }

    fun testShutdownDuringSourceLookupStopsV3Pruning() {
        val sourceFileId = SourceFileId("vfs:file:///project/StillPersisted.xml")
        val targetStore = ExecutionTargetDescriptorStore.getInstance(project)
        saveV3(sourceFileId, "ds-1", "public", "orders")
        val cache = newIsolatedCache()

        migrate(cache, targetStore) {
            cache.markShuttingDown()
            null
        }

        assertNotNull(targetStore.load(sourceFileId))
    }

    fun testShutdownDuringV2SourceLookupLeavesMigrationStateUntouched() {
        val mapperKey = "file:///project/ClosingDuringLookup.xml"
        seedV2(mapperKey, "ds-1", "orders", "public")
        val cache = newIsolatedCache()
        val targetStore = ExecutionTargetDescriptorStore.getInstance(project)

        migrate(cache, targetStore) {
            cache.markShuttingDown()
            it
        }

        assertNull(targetStore.load(SourceFileId("vfs:$mapperKey")))
        assertNotNull(projectStore().getValue(V2_INDEX))
        assertNotNull(projectStore().getValue(v2RecordKey(mapperKey)))
    }

    fun testShutdownGateLeavesMigrationStateUntouched() {
        val mapperKey = "file:///project/Closing.xml"
        seedV2(mapperKey, "ds-1", "orders", "public")
        val cache = newIsolatedCache()
        val targetStore = ExecutionTargetDescriptorStore.getInstance(project)
        cache.markShuttingDown()

        migrate(cache, targetStore) { it }

        assertNull(targetStore.load(SourceFileId("vfs:$mapperKey")))
        assertNotNull(projectStore().getValue(V2_INDEX))
        assertNotNull(projectStore().getValue(v2RecordKey(mapperKey)))
    }

    override fun tearDown() {
        try {
            clearV2()
            clearV3()
        } finally {
            super.tearDown()
        }
    }

    private fun migrate(
        cache: ConsoleCacheService,
        targetStore: ExecutionTargetDescriptorStore,
        resolveSourceUrl: (String) -> String?,
    ) {
        MyBatisActionInterceptorActivity().migratePersistedSelections(
            project = project,
            cache = cache,
            migrationStore = LegacyV2ConsoleSessionMigrationStore.getInstance(project),
            targetStore = targetStore,
            resolveSourceUrl = resolveSourceUrl,
        )
    }

    private fun newIsolatedCache(): ConsoleCacheService =
        ConsoleCacheService(project).also { Disposer.register(testRootDisposable, it) }

    private fun seedV2(
        mapperKey: String,
        dataSourceId: String,
        dataSourceName: String,
        schemaName: String,
    ) {
        val session = PersistedConsoleSession(
            mapperKey = mapperKey,
            dataSourceId = dataSourceId,
            dataSourceName = dataSourceName,
            schemaName = schemaName,
        )
        val id = ConsoleSessionPersistenceFormat.sessionId(mapperKey)
        projectStore().setValue(V2_INDEX, id)
        projectStore().setValue("$V2_RECORD_PREFIX$id", ConsoleSessionPersistenceFormat.encode(session))
    }

    private fun saveV3(
        sourceFileId: SourceFileId,
        dataSourceId: String,
        schemaName: String,
        displayName: String,
    ) {
        val targetId = ExecutionTargetId(
            StableDataSourceId(dataSourceId),
            ExplicitSchemaIdentity(schemaName),
        )
        ExecutionTargetDescriptorStore.getInstance(project).save(
            SourceTargetAssociation(sourceFileId, targetId),
            ExecutionTargetDescriptor(targetId, displayName),
        )
    }

    private fun assertV2Absent(mapperKey: String) {
        assertNull(projectStore().getValue(V2_INDEX))
        assertNull(projectStore().getValue(v2RecordKey(mapperKey)))
    }

    private fun v2RecordKey(mapperKey: String): String =
        "$V2_RECORD_PREFIX${ConsoleSessionPersistenceFormat.sessionId(mapperKey)}"

    private fun projectStore(): PropertiesComponent = PropertiesComponent.getInstance(project)

    private fun clearV2() {
        val ids = projectStore().getValue(V2_INDEX)
            ?.lineSequence()
            ?.filter { it.isNotBlank() }
            ?.toList()
            .orEmpty()
        ids.forEach { projectStore().unsetValue("$V2_RECORD_PREFIX$it") }
        projectStore().unsetValue(V2_INDEX)
    }

    private fun clearV3() {
        val ids = projectStore().getValue(ExecutionTargetDescriptorStore.INDEX_KEY)
            ?.lineSequence()
            ?.filter { it.isNotBlank() }
            ?.toList()
            .orEmpty()
        ids.forEach {
            projectStore().unsetValue("${ExecutionTargetDescriptorStore.RECORD_PREFIX}$it")
        }
        projectStore().unsetValue(ExecutionTargetDescriptorStore.INDEX_KEY)
    }
}
