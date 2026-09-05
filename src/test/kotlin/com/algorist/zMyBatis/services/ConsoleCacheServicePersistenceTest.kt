package com.algorist.zMyBatis.services

import com.intellij.ide.util.PropertiesComponent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.charset.StandardCharsets
import java.util.Base64

class ConsoleCacheServicePersistenceTest : BasePlatformTestCase() {

    companion object {
        private const val V2_INDEX = "zMyBatis.session.v2.__index__"
        private const val V2_RECORD_PREFIX = "zMyBatis.session.v2.record."
        private const val LEGACY_PREFIX = "zMyBatis.session."
    }

    fun testV2SessionIsReadFromProjectScopedStore() {
        val mapperKey = "file:///tmp/zmybatis/Mapper.xml"
        val session = PersistedConsoleSession(
            mapperKey = mapperKey,
            dataSourceId = "550e8400-e29b-41d4-a716-446655440000",
            dataSourceName = "orders",
            schemaName = "public"
        )
        val id = ConsoleSessionPersistenceFormat.sessionId(mapperKey)
        val projectStore = PropertiesComponent.getInstance(project)
        projectStore.setValue(V2_INDEX, id)
        projectStore.setValue("$V2_RECORD_PREFIX$id", ConsoleSessionPersistenceFormat.encode(session))

        val restored = ConsoleCacheService.getInstance(project).pruneStaleIndex()

        assertEquals(listOf(session), restored)
    }

    fun testLegacyApplicationScopedStateIsDeliberatelyIgnored() {
        val legacyMapperKey = "file:///tmp/zmybatis/LegacyMapper.xml"
        val legacyIndex = "${LEGACY_PREFIX}__index__.${project.basePath?.hashCode() ?: "global"}"
        val applicationStore = PropertiesComponent.getInstance()
        applicationStore.setValue(legacyIndex, legacyMapperKey)
        applicationStore.setValue("$LEGACY_PREFIX$legacyMapperKey", "orders|||public")

        try {
            assertTrue(ConsoleCacheService.getInstance(project).pruneStaleIndex().isEmpty())
        } finally {
            applicationStore.unsetValue(legacyIndex)
            applicationStore.unsetValue("$LEGACY_PREFIX$legacyMapperKey")
        }
    }

    fun testMalformedProjectIndexEntryIsPruned() {
        val projectStore = PropertiesComponent.getInstance(project)
        projectStore.setValue(V2_INDEX, "../../not-a-session-id")

        assertTrue(ConsoleCacheService.getInstance(project).pruneStaleIndex().isEmpty())
        assertNull(projectStore.getValue(V2_INDEX))
    }

    fun testIndexedMissingRecordIsPrunedFailClosed() {
        val mapperKey = "file:///tmp/zmybatis/InterruptedWriteMapper.xml"
        val id = ConsoleSessionPersistenceFormat.sessionId(mapperKey)
        val projectStore = PropertiesComponent.getInstance(project)
        projectStore.setValue(V2_INDEX, id)
        projectStore.unsetValue("$V2_RECORD_PREFIX$id")

        assertTrue(ConsoleCacheService.getInstance(project).pruneStaleIndex().isEmpty())
        assertNull(projectStore.getValue(V2_INDEX))
        assertNull(projectStore.getValue("$V2_RECORD_PREFIX$id"))
    }

    fun testImplicitDefaultSchemaRecordIsPruned() {
        val mapperKey = "file:///tmp/zmybatis/DefaultMapper.xml"
        val id = ConsoleSessionPersistenceFormat.sessionId(mapperKey)
        val projectStore = PropertiesComponent.getInstance(project)
        val raw = listOf(
            ConsoleSessionPersistenceFormat.VERSION,
            encodeField(mapperKey),
            encodeField("550e8400-e29b-41d4-a716-446655440000"),
            encodeField("orders"),
            ""
        ).joinToString("|")
        projectStore.setValue(V2_INDEX, id)
        projectStore.setValue("$V2_RECORD_PREFIX$id", raw)

        assertTrue(ConsoleCacheService.getInstance(project).pruneStaleIndex().isEmpty())
        assertNull(projectStore.getValue(V2_INDEX))
        assertNull(projectStore.getValue("$V2_RECORD_PREFIX$id"))
    }

    fun testSelectionGuardIsScopedToProjectService() {
        val cache = ConsoleCacheService.getInstance(project)
        val mapperKey = "file:///tmp/zmybatis/Mapper.xml"

        assertTrue(cache.beginSelection(mapperKey))
        assertFalse(cache.beginSelection(mapperKey))
        cache.endSelection(mapperKey)
        assertTrue(cache.beginSelection(mapperKey))
        cache.endSelection(mapperKey)
    }

    fun testShutdownGateRejectsNewSelection() {
        val cache = ConsoleCacheService.getInstance(project)
        val mapperKey = "file:///tmp/zmybatis/ClosingMapper.xml"

        assertFalse(cache.isShuttingDown())
        assertTrue(cache.beginSelection(mapperKey))
        cache.endSelection(mapperKey)

        cache.markShuttingDown()

        assertTrue(cache.isShuttingDown())
        assertFalse(cache.beginSelection(mapperKey))
    }

    fun testShutdownGatePreservesPersistedStateAgainstLateCleanup() {
        val mapperKey = "file:///tmp/zmybatis/ClosingRestoreMapper.xml"
        val session = PersistedConsoleSession(
            mapperKey = mapperKey,
            dataSourceId = "550e8400-e29b-41d4-a716-446655440099",
            dataSourceName = "orders",
            schemaName = "public"
        )
        val id = ConsoleSessionPersistenceFormat.sessionId(mapperKey)
        val projectStore = PropertiesComponent.getInstance(project)
        val raw = ConsoleSessionPersistenceFormat.encode(session)
        projectStore.setValue(V2_INDEX, id)
        projectStore.setValue("$V2_RECORD_PREFIX$id", raw)
        val cache = ConsoleCacheService.getInstance(project)

        cache.markShuttingDown()
        cache.clearSession(mapperKey)

        assertEquals(id, projectStore.getValue(V2_INDEX))
        assertEquals(raw, projectStore.getValue("$V2_RECORD_PREFIX$id"))
    }

    override fun tearDown() {
        try {
            val projectStore = PropertiesComponent.getInstance(project)
            val ids = projectStore.getValue(V2_INDEX)
                ?.lineSequence()
                ?.filter { it.isNotBlank() }
                ?.toList()
                .orEmpty()
            ids.forEach { projectStore.unsetValue("$V2_RECORD_PREFIX$it") }
            projectStore.unsetValue(V2_INDEX)
        } finally {
            super.tearDown()
        }
    }

    private fun encodeField(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
}
