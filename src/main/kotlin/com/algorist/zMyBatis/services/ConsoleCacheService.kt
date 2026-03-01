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
 *   - On next startup [MyBatisActionInterceptorActivity] reads those values and silently
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

        /**
         * Returns all fileKeys that have a saved session entry.
         * Used at startup to silently re-create consoles.
         */
        fun allSavedFileKeys(): List<String> {
            val store = PropertiesComponent.getInstance()
            // PropertiesComponent has no "list all keys" API; we rely on callers knowing
            // the fileKeys. At startup MyBatisActionInterceptorActivity iterates project
            // files and checks loadSession() per file — but that requires knowing fileKeys.
            //
            // Instead we store a side-index: a newline-separated list of fileKeys.
            val raw = store.getValue("${PROPS_PREFIX}__index__") ?: return emptyList()
            return raw.split("\n").filter { it.isNotBlank() }
        }

        fun addToIndex(fileKey: String) {
            val store = PropertiesComponent.getInstance()
            val existing = store.getValue("${PROPS_PREFIX}__index__") ?: ""
            val keys = existing.split("\n").filter { it.isNotBlank() }.toMutableSet()
            if (keys.add(fileKey)) {
                store.setValue("${PROPS_PREFIX}__index__", keys.joinToString("\n"))
            }
        }

        fun removeFromIndex(fileKey: String) {
            val store = PropertiesComponent.getInstance()
            val existing = store.getValue("${PROPS_PREFIX}__index__") ?: return
            val keys = existing.split("\n").filter { it.isNotBlank() && it != fileKey }
            store.setValue("${PROPS_PREFIX}__index__", keys.joinToString("\n"))
        }

        /** Finds a LocalDataSource by name within the project. */
        fun findDataSourceByName(project: Project, dsName: String) =
            DbPsiFacade.getInstance(project).dataSources
                .firstOrNull { it.name == dsName }
    }

    private data class Entry(val console: JdbcConsole, val sentinel: CheckedDisposable)

    private val cache = ConcurrentHashMap<String, Entry>()

    /**
     * Set to true when [projectClosing] fires (before JdbcConsoles are disposed).
     * This is more reliable than checking app.isDisposeInProgress because the project
     * close sequence disposes JdbcConsoles before our service's dispose() is called.
     */
    @Volatile
    private var projectClosing = false

    /** Called by [MyBatisActionInterceptorActivity] via ProjectManagerListener.projectClosing */
    fun markShuttingDown() {
        projectClosing = true
    }

    /**
     * Returns true when the IDE or this project is in the process of shutting down.
     * In that case, console dispose events should NOT clear the saved session.
     */
    private fun isShuttingDown(): Boolean {
        if (projectClosing) return true
        val app = ApplicationManager.getApplication()
        if (app == null || app.isDisposed || app.isDisposeInProgress) return true
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
        cache.remove(fileKey)
        val sentinel = Disposer.newCheckedDisposable(console)
        Disposer.register(sentinel) {
            cache.remove(fileKey)
            // Only wipe the persistent session when the user explicitly closed the console
            // (application still running and project not being closed).
            // During IDE/project shutdown isShuttingDown() returns true, so we leave the
            // session data intact for the next-startup restore.
            if (!isShuttingDown()) {
                LOG.info("zMyBatis: console closed by user — clearing session for $fileKey")
                clearSession(fileKey)
                removeFromIndex(fileKey)
            } else {
                LOG.info("zMyBatis: console disposed during shutdown — keeping session for $fileKey")
            }
        }
        cache[fileKey] = Entry(console, sentinel)
        LOG.info("zMyBatis: console cached for $fileKey")
    }

    fun remove(fileKey: String) {
        cache.remove(fileKey)?.let { entry ->
            if (!entry.sentinel.isDisposed) Disposer.dispose(entry.sentinel)
        }
    }

    override fun dispose() {
        // Just clear the in-memory cache. Session data in PropertiesComponent is preserved.
        // The isShuttingDown() check in each sentinel callback handles the logic correctly
        // because project.isDisposed will be true by the time callbacks fire.
        cache.clear()
    }
}
