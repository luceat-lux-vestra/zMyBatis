package com.algorist.zMyBatis.services

import com.intellij.database.console.JdbcConsole
import com.intellij.database.psi.DbPsiFacade
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
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
 * Backing file strategy: **LightVirtualFile** (in-memory only).
 *   - Never touches the disk → no VFS events → no project-reload → no console dispose.
 *   - The downside is LightVirtualFile consoles are NOT restored by IntelliJ after restart.
 *
 * IDE-restart recovery strategy:
 *   - When a console is created we persist the chosen DataSource name + SearchPath string
 *     into PropertiesComponent under key "zMyBatis.session.{fileKey}".
 *   - On next startup [com.algorist.zMyBatis.startup.MyBatisActionInterceptorActivity] reads those values and silently
 *     re-creates the console (no data-source chooser shown to the user).
 *
 * Console dispose strategy:
 *   - When a console is disposed while the application is still alive and the project is
 *     not being closed (i.e. the user explicitly closed/deleted it from the Services tab),
 *     the persistent session data is also cleaned up so the console is NOT restored on the
 *     next startup.
 *   - When a console is disposed during IDE/project shutdown (app.isDisposeInProgress or
 *     project.isDisposed/being closed), the callback skips the cleanup and the session
 *     survives for next-startup restore.
 *
 * Index scoping:
 *   - The fileKey index is stored per-project (namespaced by project.basePath) to prevent
 *     cross-project contamination in multi-project workspaces.
 *
 * Services-tab title: LightVirtualFile.name = mapper filename (e.g. "CustomerMapper.xml")
 *   → title = name = "CustomerMapper.xml"  ✅
 */
@Service(Service.Level.PROJECT)
class ConsoleCacheService(private val project: Project) : com.intellij.openapi.Disposable {

    companion object {
        private val LOG = Logger.getInstance(ConsoleCacheService::class.java)
        private const val PROPS_PREFIX = "zMyBatis.session."
        private const val SEP = "|||"

        fun getInstance(project: Project): ConsoleCacheService = project.service()

        // ── Persistence (DataSource + SearchPath) ───────────────────────────

        /** Persists the DataSource name and SearchPath string for [fileKey]. */
        fun saveSession(fileKey: String, dsName: String, searchPathStr: String) {
            PropertiesComponent.getInstance()
                .setValue("$PROPS_PREFIX$fileKey", "$dsName$SEP$searchPathStr")
        }

        /**
         * Returns the saved (dsName, searchPathStr) for [fileKey], or null if absent.
         */
        fun loadSession(fileKey: String): Pair<String, String>? {
            val raw = PropertiesComponent.getInstance().getValue("$PROPS_PREFIX$fileKey")
                ?: return null
            val idx = raw.indexOf(SEP)
            if (idx < 0) return null
            return raw.substring(0, idx) to raw.substring(idx + SEP.length)
        }

        /** Removes the saved session entry for [fileKey]. */
        fun clearSession(fileKey: String) {
            PropertiesComponent.getInstance().unsetValue("$PROPS_PREFIX$fileKey")
        }

        // ── Per-project index ────────────────────────────────────────────────
        // The index is namespaced by the project basePath so that sessions from different
        // projects never bleed into each other.  Without this, re-opening project A could
        // try to restore sessions that belong to project B (different datasource names,
        // different file paths) and leave zombie entries in the index.

        private fun indexKey(project: Project): String {
            val ns = project.basePath?.hashCode()?.toString() ?: "global"
            return "${PROPS_PREFIX}__index__.$ns"
        }

        /**
         * Returns all fileKeys saved for [project].
         * Used at startup to silently re-create consoles.
         */
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
            if (keys.add(fileKey)) {
                store.setValue(key, keys.joinToString("\n"))
            }
        }

        fun removeFromIndex(project: Project, fileKey: String) {
            val store = PropertiesComponent.getInstance()
            val key = indexKey(project)
            val existing = store.getValue(key) ?: return
            val keys = existing.split("\n").filter { it.isNotBlank() && it != fileKey }
            store.setValue(key, keys.joinToString("\n"))
        }

        /** Finds a LocalDataSource by name within the project. */
        fun findDataSourceByName(project: Project, dsName: String) =
            DbPsiFacade.getInstance(project).dataSources
                .firstOrNull { it.name == dsName }
    }

    private data class Entry(val console: JdbcConsole, val sentinel: CheckedDisposable)

    private val cache = ConcurrentHashMap<String, Entry>()

    /**
     * Set to true when [markShuttingDown] fires (before JdbcConsoles are disposed)
     * OR when our own [dispose] method is called.
     * This is more reliable than checking app.isDisposeInProgress because the project
     * close sequence disposes JdbcConsoles before our service's dispose() is called.
     */
    @Volatile
    private var shuttingDown = false

    /** Called by [com.algorist.zMyBatis.startup.MyBatisActionInterceptorActivity] via ProjectManagerListener.projectClosing */
    fun markShuttingDown() {
        shuttingDown = true
    }

    /**
     * Returns true when the IDE or this project is in the process of shutting down.
     * In that case, console dispose events should NOT clear the saved session.
     */
    private fun isShuttingDown(): Boolean {
        if (shuttingDown) return true
        val app = ApplicationManager.getApplication()
        if (app == null || app.isDisposed) return true
        if (project.isDisposed) return true
        return false
    }

    fun get(fileKey: String): JdbcConsole? {
        val entry = cache[fileKey] ?: return null
        if (entry.sentinel.isDisposed) {
            LOG.info("zMyBatis: sentinel disposed for $fileKey — evicting")
            cache.remove(fileKey, entry)
            return null
        }
        return entry.console
    }

    fun put(fileKey: String, console: JdbcConsole) {
        // Evict any previous entry.  We do NOT dispose the old sentinel here — it is still
        // a child of the old console in the Disposer tree and will be cleaned up when that
        // console is eventually disposed.  We just need to make sure the old callback does
        // NOT corrupt the new entry or wipe the session.  We achieve that by capturing the
        // sentinel reference in the closure and checking it is still the active one.
        cache.remove(fileKey)
        val sentinel = Disposer.newCheckedDisposable(console)
        Disposer.register(sentinel) {
            // Guard: only act if this sentinel belongs to the currently active entry.
            // If put() was called again before this callback fires, cache[fileKey] will
            // point to a newer Entry with a different sentinel — skip everything in that case.
            val current = cache[fileKey]
            if (current != null && current.sentinel !== sentinel) {
                LOG.info("zMyBatis: stale sentinel fired for $fileKey — ignoring")
                return@register
            }
            cache.remove(fileKey)
            // Only wipe the persistent session when the user explicitly closed the console
            // (application still running and project not being closed).
            // During IDE/project shutdown isShuttingDown() returns true, so we leave the
            // session data intact for the next-startup restore.
            if (!isShuttingDown()) {
                LOG.info("zMyBatis: console closed by user — clearing session for $fileKey")
                clearSession(fileKey)
                removeFromIndex(project, fileKey)
            } else {
                LOG.info("zMyBatis: console disposed during shutdown — keeping session for $fileKey")
            }
        }
        cache[fileKey] = Entry(console, sentinel)
        LOG.info("zMyBatis: console cached for $fileKey")
    }


    /**
     * Explicitly removes the cached entry AND clears the persistent session.
     * Call this only when the intent is to permanently forget the console
     * (e.g. a programmatic "reset" — currently unused externally, but exposed for safety).
     */
    @Suppress("unused")
    fun remove(fileKey: String) {
        // Remove from in-memory cache first so the sentinel callback (if it fires) sees
        // no current entry and skips the redundant clearSession() call.
        cache.remove(fileKey)
        clearSession(fileKey)
        removeFromIndex(project, fileKey)
        LOG.info("zMyBatis: explicitly removed session for $fileKey")
    }

    override fun dispose() {
        // Mark as shutting down BEFORE clearing the cache.
        // Some JdbcConsoles may still be alive at this point and will be disposed
        // shortly after by the platform; their sentinel callbacks must NOT clear the
        // saved session data.  Setting the flag here guarantees isShuttingDown()
        // returns true even if projectClosing was never triggered (e.g. when the
        // project.isDisposed check races with an already-running callback).
        shuttingDown = true
        cache.clear()
    }
}
