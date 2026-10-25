package com.algorist.zMyBatis.startup

import com.algorist.zMyBatis.core.execution.ExecutionTargetDescriptor
import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.ExplicitSchemaIdentity
import com.algorist.zMyBatis.core.execution.SourceTargetAssociation
import com.algorist.zMyBatis.core.execution.StableDataSourceId
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.services.ConsoleCacheService
import com.algorist.zMyBatis.services.ExecutionTargetDescriptorStore
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MyBatisActionInterceptorActivityLifecycleTest : BasePlatformTestCase() {

    fun testStartupPrunesV3WhoseVfsSourceDisappeared() {
        val sourceFileId = SourceFileId("vfs:file:///project/Gone.xml")
        val targetStore = ExecutionTargetDescriptorStore.getInstance(project)
        saveV3(sourceFileId, "ds-1", "public", "orders")
        val cache = newIsolatedCache()

        prune(cache, targetStore) { null }

        assertNull(targetStore.load(sourceFileId))
    }

    fun testStartupRetainsV3WhoseExactVfsSourceStillExists() {
        val sourceUrl = "file:///project/Mapper.xml"
        val sourceFileId = SourceFileId("vfs:$sourceUrl")
        val targetStore = ExecutionTargetDescriptorStore.getInstance(project)
        saveV3(sourceFileId, "ds-1", "public", "orders")
        val cache = newIsolatedCache()

        prune(cache, targetStore) { it }

        assertNotNull(targetStore.load(sourceFileId))
        assertNull(cache.get(sourceUrl))
    }

    fun testShutdownDuringSourceLookupStopsV3Pruning() {
        val sourceFileId = SourceFileId("vfs:file:///project/StillPersisted.xml")
        val targetStore = ExecutionTargetDescriptorStore.getInstance(project)
        saveV3(sourceFileId, "ds-1", "public", "orders")
        val cache = newIsolatedCache()

        prune(cache, targetStore) {
            cache.markShuttingDown()
            null
        }

        assertNotNull(targetStore.load(sourceFileId))
    }

    override fun tearDown() {
        try {
            clearV3()
        } finally {
            super.tearDown()
        }
    }

    private fun prune(
        cache: ConsoleCacheService,
        targetStore: ExecutionTargetDescriptorStore,
        resolveSourceUrl: (String) -> String?,
    ) {
        MyBatisActionInterceptorActivity().prunePersistedTargets(
            project = project,
            cache = cache,
            targetStore = targetStore,
            resolveSourceUrl = resolveSourceUrl,
        )
    }

    private fun newIsolatedCache(): ConsoleCacheService =
        ConsoleCacheService(project).also { Disposer.register(testRootDisposable, it) }

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

    private fun clearV3() {
        val store = PropertiesComponent.getInstance(project)
        val ids = store.getValue(ExecutionTargetDescriptorStore.INDEX_KEY)
            ?.lineSequence()
            ?.filter { it.isNotBlank() }
            ?.toList()
            .orEmpty()
        ids.forEach {
            store.unsetValue("${ExecutionTargetDescriptorStore.RECORD_PREFIX}$it")
        }
        store.unsetValue(ExecutionTargetDescriptorStore.INDEX_KEY)
    }
}
