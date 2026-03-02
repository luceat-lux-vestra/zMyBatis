package com.algorist.zMyBatis.services

import com.intellij.database.console.JdbcConsole
import com.intellij.database.psi.DbPsiFacade
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.CheckedDisposable
import com.intellij.openapi.util.Disposer
import java.util.concurrent.ConcurrentHashMap

/**
 * Project-level service that owns the JdbcConsole cache.
 *
 * Session lifecycle:
 *   - [put] creates the in-memory cache entry AND persists session data (dsName + schemaName)
 *     so no caller needs to call saveSession/addToIndex separately.
 *   - Sentinel callback always clears session on dispose (no shutdown-flag race condition).
 *   - [markShuttingDown] / [dispose] re-persist all still-live sessions so they survive
 *     the upcoming restart even after the sentinels fire and call clearSession.
 *   - [pruneStaleIndex] is called at startup to remove index entries whose consoles were
 *     closed while the IDE was not running (or after a crash).
 */
@Service(Service.Level.PROJECT)
class ConsoleCacheService(private val project: Project) : com.intellij.openapi.Disposable {

    companion object {
        private val LOG = Logger.getInstance(ConsoleCacheService::class.java)
        private const val PROPS_PREFIX = "zMyBatis.session."
        private const val SEP = "|||"

        fun getInstance(project: Project): ConsoleCacheService = project.service()

        // ── Persistence ──────────────────────────────────────────────────────

        fun saveSession(fileKey: String, dsName: String, schemaName: String) {
            PropertiesComponent.getInstance()
                .setValue("$PROPS_PREFIX$fileKey", "$dsName$SEP$schemaName")
        }

        fun loadSession(fileKey: String): Pair<String, String>? {
            val raw = PropertiesComponent.getInstance().getValue("$PROPS_PREFIX$fileKey")
                ?: return null
            val idx = raw.indexOf(SEP)
            if (idx < 0) return null
            return raw.substring(0, idx) to raw.substring(idx + SEP.length)
        }

        fun clearSession(fileKey: String) {
            PropertiesComponent.getInstance().unsetValue("$PROPS_PREFIX$fileKey")
        }

        // ── Per-project index ────────────────────────────────────────────────

        private fun indexKey(project: Project): String {
            val ns = project.basePath?.hashCode()?.toString() ?: "global"
            return "${PROPS_PREFIX}__index__.$ns"
        }

        fun allSavedFileKeys(project: Project): List<String> {
            val raw = PropertiesComponent.getInstance().getValue(indexKey(project))
                ?: return emptyList()
            return raw.split("\n").filter { it.isNotBlank() }
        }

        fun addToIndex(project: Project, fileKey: String) {
            val store = PropertiesComponent.getInstance()
            val key = indexKey(project)
            val existing = store.getValue(key) ?: ""
            val keys = existing.split("\n").filter { it.isNotBlank() }.toMutableSet()
            if (keys.add(fileKey)) store.setValue(key, keys.joinToString("\n"))
        }

        fun removeFromIndex(project: Project, fileKey: String) {
            val store = PropertiesComponent.getInstance()
            val key = indexKey(project)
            val existing = store.getValue(key) ?: return
            val keys = existing.split("\n").filter { it.isNotBlank() && it != fileKey }
            store.setValue(key, keys.joinToString("\n"))
        }

        fun findDataSourceByName(project: Project, dsName: String) =
            DbPsiFacade.getInstance(project).dataSources
                .firstOrNull { it.name == dsName }
    }

    // Entry now carries the session data needed to re-persist on shutdown.
    private data class Entry(
        val console: JdbcConsole,
        val sentinel: CheckedDisposable,
        val dsName: String,
        val schemaName: String
    )

    private val cache = ConcurrentHashMap<String, Entry>()

    /**
     * Set AFTER persistAllLiveSessions() has already written everything to disk.
     * Sentinel callbacks check this flag and skip clearSession/removeFromIndex when true,
     * because the session data has already been re-persisted and must survive the restart.
     */
    @Volatile
    private var shuttingDown = false

    // ── Public API ───────────────────────────────────────────────────────────

    fun get(fileKey: String): JdbcConsole? {
        val entry = cache[fileKey] ?: return null
        if (entry.sentinel.isDisposed) {
            LOG.info("zMyBatis: sentinel disposed for $fileKey — evicting")
            cache.remove(fileKey, entry)
            return null
        }
        return entry.console
    }

    /**
     * Registers [console] in the cache and persists session data atomically.
     * Callers no longer need to call saveSession / addToIndex separately.
     */
    fun put(fileKey: String, console: JdbcConsole, dsName: String, schemaName: String) {
        cache.remove(fileKey)

        // Guard: if the console is already disposed (e.g. build() failed silently),
        // still persist the session so the next startup can try to restore it,
        // but do not add a dead entry to the in-memory cache.
        if (Disposer.isDisposed(console)) {
            LOG.warn("zMyBatis: console already disposed at put() for $fileKey — persisting session only")
            saveSession(fileKey, dsName, schemaName)
            addToIndex(project, fileKey)
            return
        }

        // Persist immediately so the session survives even if the sentinel fires quickly.
        saveSession(fileKey, dsName, schemaName)
        addToIndex(project, fileKey)

        val sentinel = Disposer.newCheckedDisposable(console)
        Disposer.register(sentinel) {
            val current = cache[fileKey]
            if (current != null && current.sentinel !== sentinel) {
                LOG.info("zMyBatis: stale sentinel fired for $fileKey — ignoring")
                return@register
            }
            cache.remove(fileKey)
            if (shuttingDown) {
                // Session was already re-persisted in markShuttingDown() / dispose().
                // Do NOT clear it — it must survive for the next startup restore.
                LOG.info("zMyBatis: console disposed during shutdown — keeping session for $fileKey")
            } else {
                // User explicitly closed the console — remove session so it is not restored.
                LOG.info("zMyBatis: console closed by user — clearing session for $fileKey")
                clearSession(fileKey)
                removeFromIndex(project, fileKey)
            }
        }

        cache[fileKey] = Entry(console, sentinel, dsName, schemaName)
        LOG.info("zMyBatis: console cached for $fileKey (ds=$dsName, schema=$schemaName)")
    }

    /**
     * Called by ProjectManagerListener.projectClosing.
     * Persists all live sessions FIRST, then sets shuttingDown=true so that
     * subsequent sentinel callbacks skip clearSession/removeFromIndex.
     */
    fun markShuttingDown() {
        // Order matters: persist before setting the flag so that any sentinel callback
        // that already started (but hasn't checked the flag yet) does not race with us.
        persistAllLiveSessions()
        shuttingDown = true
        LOG.info("zMyBatis: markShuttingDown — persisted ${cache.size} live session(s)")
    }

    /**
     * Called at startup to remove index entries that no longer have a live console
     * (e.g. user closed them while IDE was shut down, or after a crash).
     * Returns the pruned list of valid fileKeys.
     */
    fun pruneStaleIndex(): List<String> {
        val saved = allSavedFileKeys(project)
        if (saved.isEmpty()) return emptyList()

        val valid = mutableListOf<String>()
        for (fileKey in saved) {
            val sessionExists = loadSession(fileKey) != null
            if (sessionExists) {
                valid.add(fileKey)
            } else {
                LOG.info("zMyBatis: pruning stale index entry (no session data) — $fileKey")
                removeFromIndex(project, fileKey)
            }
        }
        return valid
    }

    @Suppress("unused")
    fun remove(fileKey: String) {
        cache.remove(fileKey)
        clearSession(fileKey)
        removeFromIndex(project, fileKey)
        LOG.info("zMyBatis: explicitly removed session for $fileKey")
    }

    override fun dispose() {
        persistAllLiveSessions()
        shuttingDown = true
        cache.clear()
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private fun persistAllLiveSessions() {
        for ((fileKey, entry) in cache) {
            if (entry.sentinel.isDisposed) continue
            saveSession(fileKey, entry.dsName, entry.schemaName)
            addToIndex(project, fileKey)
            LOG.info("zMyBatis: re-persisted session for $fileKey")
        }
    }
}
