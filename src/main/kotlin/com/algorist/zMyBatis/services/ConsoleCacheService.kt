package com.algorist.zMyBatis.services

import com.intellij.database.console.JdbcConsole
import com.intellij.database.psi.DbDataSource
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.DbImplUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.CheckedDisposable
import com.intellij.openapi.util.Disposer
import java.util.concurrent.ConcurrentHashMap

/**
 * Project-level service that owns the JdbcConsole cache and restart persistence.
 *
 * Persistence v2 deliberately does not migrate the legacy application-level
 * `zMyBatis.session.*` records. Those records contain only a collision-prone project hash and
 * mutable datasource display name, so there is no safe way to prove which project/datasource they
 * belonged to. Ignoring them is the fail-closed migration policy: the user selects datasource/schema
 * once again and only then is a v2 record created.
 */
@Service(Service.Level.PROJECT)
class ConsoleCacheService(private val project: Project) : com.intellij.openapi.Disposable {

    companion object {
        private val LOG = Logger.getInstance(ConsoleCacheService::class.java)
        private const val PROPS_PREFIX = "zMyBatis.session.v2."
        private const val INDEX_KEY = "${PROPS_PREFIX}__index__"
        private const val RECORD_PREFIX = "${PROPS_PREFIX}record."

        fun getInstance(project: Project): ConsoleCacheService = project.service()

        /**
         * Returns the IDE-assigned datasource UUID. Display names are diagnostic only and must
         * never participate in restart identity.
         */
        fun stableDataSourceId(dataSource: DbDataSource): String? = try {
            DbImplUtil.getMaybeLocalDataSource(dataSource)
                ?.uniqueId
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } catch (ex: Throwable) {
            LOG.warn("zMyBatis: cannot obtain stable datasource identity for '${dataSource.name}'", ex)
            null
        }
    }

    private class Entry(
        val console: JdbcConsole,
        val sentinel: CheckedDisposable,
        val persistedSession: PersistedConsoleSession?
    )

    /** Project-scoped store; no application-global project namespace is needed. */
    private val store: PropertiesComponent = PropertiesComponent.getInstance(project)
    private val cache = ConcurrentHashMap<String, Entry>()
    private val activeSelections = ConcurrentHashMap.newKeySet<String>()
    private val persistenceLock = Any()
    private val lifecycleLock = Any()

    @Volatile
    private var shuttingDown = false

    internal fun isShuttingDown(): Boolean = shuttingDown

    /**
     * Returns a live cached console only while the project session lifecycle is active.
     * Disposal cleanup and replacement are serialized with [put] so a stale entry can never clear
     * persistence belonging to a newer entry for the same mapper.
     */
    fun get(mapperKey: String): JdbcConsole? = synchronized(lifecycleLock) {
        if (shuttingDown) return@synchronized null
        val entry = cache[mapperKey] ?: return@synchronized null
        if (!entry.sentinel.isDisposed) return@synchronized entry.console

        if (cache.remove(mapperKey, entry)) {
            LOG.info("zMyBatis: disposed console observed — clearing session for $mapperKey")
            clearSessionLocked(mapperKey)
        }
        null
    }

    internal fun beginSelection(mapperKey: String): Boolean = synchronized(lifecycleLock) {
        if (shuttingDown) false else activeSelections.add(mapperKey)
    }

    internal fun endSelection(mapperKey: String) {
        activeSelections.remove(mapperKey)
    }

    /**
     * Caches [console] and updates restart persistence as one lifecycle transition.
     *
     * Restart persistence requires both a stable datasource UUID and an explicitly selected schema.
     * "Use Default Schema" remains reusable in-process but is not persisted because the effective
     * default schema may change across restarts and cannot be proven from an empty schema identity.
     *
     * The transition is accepted only while the service is active and the console sentinel is live.
     * A rejected registration never removes/replaces an older live entry and never mutates its
     * persisted session. The caller retains ownership of [console] when this method returns false.
     */
    fun put(
        mapperKey: String,
        console: JdbcConsole,
        dataSourceId: String?,
        dataSourceName: String,
        schemaName: String
    ): Boolean {
        val stableDataSourceId = dataSourceId?.takeIf { it.isNotBlank() }
        val persistedSession = if (stableDataSourceId != null && schemaName.isNotBlank()) {
            PersistedConsoleSession(
                mapperKey = mapperKey,
                dataSourceId = stableDataSourceId,
                dataSourceName = dataSourceName,
                schemaName = schemaName
            )
        } else {
            null
        }

        val sentinel = Disposer.newCheckedDisposable(console)
        if (sentinel.isDisposed) {
            LOG.warn("zMyBatis: console already disposed at put() for $mapperKey — rejecting registration")
            return false
        }

        val entry = Entry(console, sentinel, persistedSession)
        try {
            Disposer.register(sentinel) {
                synchronized(lifecycleLock) {
                    if (!cache.remove(mapperKey, entry)) {
                        LOG.info("zMyBatis: stale sentinel fired for $mapperKey — ignoring")
                    } else if (shuttingDown) {
                        LOG.info("zMyBatis: console disposed during shutdown — keeping session for $mapperKey")
                    } else {
                        LOG.info("zMyBatis: console closed by user — clearing session for $mapperKey")
                        clearSessionLocked(mapperKey)
                    }
                }
            }
        } catch (ex: Throwable) {
            LOG.warn("zMyBatis: failed to register console sentinel for $mapperKey", ex)
            if (!sentinel.isDisposed) Disposer.dispose(sentinel)
            return false
        }

        val accepted = synchronized(lifecycleLock) {
            if (shuttingDown || sentinel.isDisposed) {
                false
            } else {
                if (persistedSession != null) {
                    saveSession(persistedSession)
                } else {
                    clearSessionLocked(mapperKey)
                    val reason = if (stableDataSourceId == null) {
                        "datasource '$dataSourceName' has no stable ID"
                    } else {
                        "default schema has no stable restart identity"
                    }
                    LOG.warn("zMyBatis: $reason; session will not survive restart")
                }
                cache[mapperKey] = entry
                true
            }
        }

        if (!accepted) {
            LOG.info("zMyBatis: rejecting console registration during shutdown/disposal for $mapperKey")
            if (!sentinel.isDisposed) Disposer.dispose(sentinel)
            return false
        }

        LOG.info(
            "zMyBatis: console cached for $mapperKey " +
                "(ds=$dataSourceName, dsId=${dataSourceId ?: "<unpersisted>"}, schema=$schemaName)"
        )
        return true
    }

    /** Resolves an exact datasource UUID. Missing or duplicate IDs fail closed. */
    fun findDataSourceById(dataSourceId: String): DbDataSource? {
        val matches = DbPsiFacade.getInstance(project).dataSources
            .filter { stableDataSourceId(it) == dataSourceId }
        return when (matches.size) {
            1 -> matches.single()
            0 -> {
                LOG.info("zMyBatis: datasource id '$dataSourceId' is no longer available")
                null
            }
            else -> {
                LOG.error("zMyBatis: datasource id '$dataSourceId' is ambiguous (${matches.size} matches)")
                null
            }
        }
    }

    /**
     * Returns only structurally valid v2 records and removes stale/malformed index entries.
     * The index stores fixed SHA-256 record IDs rather than file paths, so arbitrary mapper paths
     * cannot corrupt the index format.
     */
    internal fun pruneStaleIndex(): List<PersistedConsoleSession> = synchronized(lifecycleLock) {
        if (shuttingDown) return@synchronized emptyList()
        synchronized(persistenceLock) {
            val ids = savedSessionIdsLocked()
            if (ids.isEmpty()) return@synchronized emptyList()

            val valid = mutableListOf<PersistedConsoleSession>()
            for (id in ids) {
                if (!ConsoleSessionPersistenceFormat.isValidSessionId(id)) {
                    LOG.warn("zMyBatis: pruning malformed session index id '$id'")
                    removeFromIndexLocked(id)
                    continue
                }

                val session = loadSessionLocked(id)
                if (session == null) {
                    LOG.info("zMyBatis: pruning stale session index id '$id'")
                    removeRecordByIdLocked(id)
                    continue
                }
                valid.add(session)
            }
            valid
        }
    }

    fun clearSession(mapperKey: String) {
        synchronized(lifecycleLock) {
            clearSessionLocked(mapperKey)
        }
    }

    @Suppress("unused")
    fun remove(mapperKey: String) {
        synchronized(lifecycleLock) {
            cache.remove(mapperKey)
            clearSessionLocked(mapperKey)
        }
        LOG.info("zMyBatis: explicitly removed session for $mapperKey")
    }

    /**
     * Atomically enters shutdown before re-persisting. Disposal callbacks and new registrations use
     * the same lock, so no callback can erase state and no queued restore/selection can recreate it
     * after shutdown wins the lifecycle race.
     */
    fun markShuttingDown() {
        val persisted = synchronized(lifecycleLock) {
            shuttingDown = true
            val count = persistAllLiveSessions()
            activeSelections.clear()
            count
        }
        LOG.info("zMyBatis: markShuttingDown — persisted $persisted live session(s)")
    }

    override fun dispose() {
        synchronized(lifecycleLock) {
            shuttingDown = true
            persistAllLiveSessions()
            activeSelections.clear()
            cache.clear()
        }
    }

    private fun clearSessionLocked(mapperKey: String) {
        val id = ConsoleSessionPersistenceFormat.sessionId(mapperKey)
        synchronized(persistenceLock) {
            removeRecordByIdLocked(id)
        }
    }

    private fun saveSession(session: PersistedConsoleSession) {
        val id = ConsoleSessionPersistenceFormat.sessionId(session.mapperKey)
        synchronized(persistenceLock) {
            // Index-first guarantees every newly written record has a discoverable cleanup path.
            // Existing ids make addToIndexLocked() a no-op, so updates go straight to the record.
            addToIndexLocked(id)
            store.setValue(recordKey(id), ConsoleSessionPersistenceFormat.encode(session))
        }
    }

    private fun loadSessionLocked(id: String): PersistedConsoleSession? {
        val raw = store.getValue(recordKey(id)) ?: return null
        val session = ConsoleSessionPersistenceFormat.decode(raw) ?: return null
        return if (ConsoleSessionPersistenceFormat.sessionId(session.mapperKey) == id) {
            session
        } else {
            LOG.warn("zMyBatis: session id/content mismatch for '$id'")
            null
        }
    }

    private fun savedSessionIdsLocked(): List<String> {
        val raw = store.getValue(INDEX_KEY) ?: return emptyList()
        return raw.lineSequence().filter { it.isNotBlank() }.distinct().toList()
    }

    private fun addToIndexLocked(id: String) {
        val ids = savedSessionIdsLocked().toMutableSet()
        if (ids.add(id)) writeIndexLocked(ids)
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

    private fun persistAllLiveSessions(): Int {
        var persisted = 0
        for ((mapperKey, entry) in cache) {
            if (entry.sentinel.isDisposed) continue
            val session = entry.persistedSession ?: continue
            saveSession(session)
            persisted++
            LOG.info("zMyBatis: re-persisted session for $mapperKey")
        }
        return persisted
    }
}
