package com.algorist.zMyBatis.services

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Migration-only owner of project-scoped v2 console-session persistence.
 *
 * This store has no live Database Tools resource or lifecycle authority. Startup migration must
 * compose it with [ConsoleCacheService.runStartupMigrationTransitionIfActive] so irreversible v2
 * cleanup is linearized against project shutdown.
 *
 * Legacy application-scoped `zMyBatis.session.*` records are deliberately outside this store.
 * Their collision-prone project hash and mutable datasource display name cannot prove target
 * identity, so they remain ignored fail-closed.
 */
@Service(Service.Level.PROJECT)
internal class LegacyV2ConsoleSessionMigrationStore(project: Project) {

    companion object {
        private val LOG = Logger.getInstance(LegacyV2ConsoleSessionMigrationStore::class.java)
        internal const val INDEX_KEY = "zMyBatis.session.v2.__index__"
        internal const val RECORD_PREFIX = "zMyBatis.session.v2.record."

        fun getInstance(project: Project): LegacyV2ConsoleSessionMigrationStore = project.service()
    }

    private val store: PropertiesComponent = PropertiesComponent.getInstance(project)
    private val persistenceLock = Any()

    /**
     * Returns structurally valid v2 records and removes malformed/stale indexed state.
     *
     * Callers that need shutdown ordering must invoke this inside the startup lifecycle transition
     * gate owned by [ConsoleCacheService].
     */
    internal fun pruneStaleIndex(): List<PersistedConsoleSession> = synchronized(persistenceLock) {
        val ids = savedSessionIdsLocked()
        if (ids.isEmpty()) return@synchronized emptyList()

        val valid = mutableListOf<PersistedConsoleSession>()
        for (id in ids) {
            if (!ConsoleSessionPersistenceFormat.isValidSessionId(id)) {
                LOG.warn("zMyBatis: pruning malformed v2 session index id '$id'")
                removeFromIndexLocked(id)
                continue
            }

            val session = loadSessionLocked(id)
            if (session == null) {
                LOG.info("zMyBatis: pruning stale v2 session index id '$id'")
                removeRecordByIdLocked(id)
                continue
            }
            valid.add(session)
        }
        valid
    }

    /**
     * Removes one migration-only v2 record.
     *
     * This method deliberately has no project-shutdown policy. Startup owns that policy through
     * [ConsoleCacheService.runStartupMigrationTransitionIfActive].
     */
    internal fun clearSession(mapperKey: String) {
        val id = ConsoleSessionPersistenceFormat.sessionId(mapperKey)
        synchronized(persistenceLock) {
            removeRecordByIdLocked(id)
        }
    }

    private fun loadSessionLocked(id: String): PersistedConsoleSession? {
        val raw = store.getValue(recordKey(id)) ?: return null
        val session = ConsoleSessionPersistenceFormat.decode(raw) ?: return null
        return if (ConsoleSessionPersistenceFormat.sessionId(session.mapperKey) == id) {
            session
        } else {
            LOG.warn("zMyBatis: v2 session id/content mismatch for '$id'")
            null
        }
    }

    private fun savedSessionIdsLocked(): List<String> {
        val raw = store.getValue(INDEX_KEY) ?: return emptyList()
        return raw.lineSequence().filter { it.isNotBlank() }.distinct().toList()
    }

    private fun removeFromIndexLocked(id: String) {
        val ids = savedSessionIdsLocked().filterTo(linkedSetOf()) { it != id }
        writeIndexLocked(ids)
    }

    private fun writeIndexLocked(ids: Set<String>) {
        if (ids.isEmpty()) {
            store.unsetValue(INDEX_KEY)
        } else {
            store.setValue(INDEX_KEY, ids.sorted().joinToString("\n"))
        }
    }

    private fun removeRecordByIdLocked(id: String) {
        if (ConsoleSessionPersistenceFormat.isValidSessionId(id)) {
            store.unsetValue(recordKey(id))
        }
        removeFromIndexLocked(id)
    }

    private fun recordKey(id: String): String = "$RECORD_PREFIX$id"
}
