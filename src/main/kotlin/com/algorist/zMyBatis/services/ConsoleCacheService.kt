package com.algorist.zMyBatis.services

import com.intellij.database.console.JdbcConsole
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.util.CheckedDisposable
import com.intellij.openapi.util.Disposer
import java.util.concurrent.ConcurrentHashMap

/**
 * Project-level owner of live JdbcConsole cache/resource lifecycle plus migration-only v2
 * persistence compatibility.
 *
 * New restart target authority is v3 [ExecutionTargetDescriptorStore] state. The shipping action
 * registers v3-backed consoles through [putEphemeral], so creating/disposing a live console cannot
 * create or delete v3 target identity.
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

    }

    private class Entry(
        val console: JdbcConsole,
        val sentinel: CheckedDisposable,
    )

    /** Project-scoped store; no application-global project namespace is needed. */
    private val store: PropertiesComponent = PropertiesComponent.getInstance(project)
    private val cache = ConcurrentHashMap<String, Entry>()
    private val activeSelections = ConcurrentHashMap.newKeySet<String>()
    private val persistenceLock = Any()
    private val lifecycleLock = Any()

    @Volatile
    private var shuttingDown = false

    init {
        // The connection is parented to this project service, not to Project itself. IntelliJ
        // disposes project services on both project close and dynamic plugin unload, so the
        // listener cannot outlive the plugin classloader. projectClosing only flips the already
        // created service into the same shutdown gate; it never performs a new service lookup.
        ApplicationManager.getApplication()
            .messageBus
            .connect(this)
            .subscribe(ProjectManager.TOPIC, object : ProjectManagerListener {
                override fun projectClosing(closingProject: Project) {
                    handleProjectClosing(closingProject)
                }
            })
    }

    internal fun isShuttingDown(): Boolean = shuttingDown

    internal fun handleProjectClosing(closingProject: Project) {
        if (closingProject !== project) return
        LOG.info("zMyBatis: project closing — marking shutdown for persistence preservation")
        markShuttingDown()
    }

    /**
     * Returns a live cached console only while the project session lifecycle is active.
     * Disposal cleanup and replacement are serialized with registration so a stale entry can never
     * clear legacy v2 state belonging to a newer entry. Ephemeral entries never own v3 persistence.
     */
    fun get(mapperKey: String): JdbcConsole? = synchronized(lifecycleLock) {
        if (shuttingDown) return@synchronized null
        val entry = cache[mapperKey] ?: return@synchronized null
        if (!entry.sentinel.isDisposed) return@synchronized entry.console

        if (cache.remove(mapperKey, entry)) {
            LOG.info("zMyBatis: disposed ephemeral console observed for $mapperKey")
        }
        null
    }

    internal fun beginSelection(mapperKey: String): Boolean = synchronized(lifecycleLock) {
        !shuttingDown && activeSelections.add(mapperKey)
    }

    internal fun endSelection(mapperKey: String) {
        activeSelections.remove(mapperKey)
    }

    /**
     * Registers a live console only as an in-memory REUSE optimization.
     *
     * v3 target selection persistence is owned independently by [ExecutionTargetDescriptorStore].
     * Registration clears any obsolete v2 record once, but disposal/shutdown of this console never
     * creates or removes persisted target identity.
     */
    fun putEphemeral(
        mapperKey: String,
        console: JdbcConsole,
    ): Boolean = registerConsole(mapperKey, console)

    private fun registerConsole(
        mapperKey: String,
        console: JdbcConsole,
    ): Boolean {
        val sentinel = Disposer.newCheckedDisposable(console)
        if (sentinel.isDisposed) {
            LOG.warn("zMyBatis: console already disposed at registration for $mapperKey — rejecting")
            return false
        }

        val entry = Entry(console, sentinel)
        try {
            Disposer.register(sentinel) {
                synchronized(lifecycleLock) {
                    if (!cache.remove(mapperKey, entry)) {
                        LOG.info("zMyBatis: stale sentinel fired for $mapperKey — ignoring")
                    } else {
                        LOG.info("zMyBatis: ephemeral console disposed for $mapperKey")
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
                // A v3-backed console is only an ephemeral resource. Clear any obsolete v2
                // migration record once acquisition succeeds; never create or mutate v3 here.
                clearSessionLocked(mapperKey)
                cache[mapperKey] = entry
                true
            }
        }

        if (!accepted) {
            LOG.info("zMyBatis: rejecting console registration during shutdown/disposal for $mapperKey")
            if (!sentinel.isDisposed) Disposer.dispose(sentinel)
            return false
        }

        LOG.info("zMyBatis: ephemeral console cached for $mapperKey")
        return true
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

    /**
     * Removes stale persisted state only while the project lifecycle is active. A restore may
     * discover stale-looking state just as project close begins; if shutdown wins the lifecycle
     * lock, preservation takes precedence and the next startup re-evaluates the state safely.
     */
    fun clearSession(mapperKey: String) {
        synchronized(lifecycleLock) {
            if (shuttingDown) {
                LOG.info("zMyBatis: skipping session cleanup during shutdown for $mapperKey")
                return@synchronized
            }
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
     * Atomically closes the resource-acquisition gate. v2 is read/cleanup-only migration state and
     * v3 is owned by ExecutionTargetDescriptorStore, so shutdown never writes persistence from live
     * console resources.
     */
    fun markShuttingDown() {
        synchronized(lifecycleLock) {
            shuttingDown = true
            activeSelections.clear()
        }
        LOG.info("zMyBatis: markShuttingDown — console acquisition disabled")
    }

    override fun dispose() {
        synchronized(lifecycleLock) {
            shuttingDown = true
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


}
