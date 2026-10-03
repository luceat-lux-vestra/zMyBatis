package com.algorist.zMyBatis.services

import com.intellij.database.console.JdbcConsole
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.CheckedDisposable
import com.intellij.openapi.util.Disposer
import java.util.concurrent.ConcurrentHashMap

/**
 * Project-level owner of live JdbcConsole cache/resource lifecycle and startup lifecycle gating.
 *
 * Persisted target identity is owned by [ExecutionTargetDescriptorStore]. Migration-only v2 state
 * is owned by [LegacyV2ConsoleSessionMigrationStore]. This service therefore has no persistence
 * storage surface: registering, observing, or disposing a live console cannot create, mutate, or
 * delete v2/v3 target persistence.
 */
@Service(Service.Level.PROJECT)
class ConsoleCacheService(private val project: Project) : com.intellij.openapi.Disposable {

    companion object {
        private val LOG = Logger.getInstance(ConsoleCacheService::class.java)

        fun getInstance(project: Project): ConsoleCacheService = project.service()
    }

    private class Entry(
        val console: JdbcConsole,
        val sentinel: CheckedDisposable,
    )

    private val cache = ConcurrentHashMap<String, Entry>()
    private val activeSelections = ConcurrentHashMap.newKeySet<String>()
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
        LOG.info("zMyBatis: project closing — marking shutdown")
        markShuttingDown()
    }

    /**
     * Returns a live cached console only while the project session lifecycle is active.
     * Ephemeral console disposal affects only the in-memory cache.
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
     * Linearizes one startup migration persistence transition with project shutdown.
     *
     * Source/VFS discovery happens outside this lock. Once that evidence is ready, v2/v3 mutation
     * either completes before shutdown or is rejected after shutdown wins.
     */
    internal fun runStartupMigrationTransitionIfActive(block: () -> Unit): Boolean =
        synchronized(lifecycleLock) {
            if (project.isDisposed || shuttingDown) {
                false
            } else {
                block()
                true
            }
        }

    /**
     * Registers a live console only as an in-memory REUSE optimization.
     *
     * Registration/disposal has no v2 or v3 persistence side effects.
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
        } catch (ex: ProcessCanceledException) {
            disposeSentinelAfterRegistrationFailure(sentinel, ex)
            throw ex
        } catch (ex: Exception) {
            LOG.warn(
                "zMyBatis: failed to register console sentinel for $mapperKey " +
                    "(type=${ex.javaClass.name})"
            )
            disposeSentinelAfterRegistrationFailure(sentinel, ex)
            return false
        } catch (fatal: Throwable) {
            LOG.error("zMyBatis: fatal console sentinel registration failure for $mapperKey", fatal)
            disposeSentinelAfterRegistrationFailure(sentinel, fatal)
            throw fatal
        }

        val accepted = synchronized(lifecycleLock) {
            if (shuttingDown || sentinel.isDisposed) {
                false
            } else {
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

    @Suppress("TooGenericExceptionCaught")
    private fun disposeSentinelAfterRegistrationFailure(
        sentinel: CheckedDisposable,
        primaryFailure: Throwable,
    ) {
        if (sentinel.isDisposed) return

        try {
            Disposer.dispose(sentinel)
        } catch (cleanupFailure: ProcessCanceledException) {
            preservePrimaryOrRethrowCleanup(primaryFailure, cleanupFailure)
        } catch (cleanupFailure: Exception) {
            if (primaryFailure is ProcessCanceledException || primaryFailure !is Exception) {
                primaryFailure.addSuppressed(cleanupFailure)
            } else {
                primaryFailure.addSuppressed(cleanupFailure)
                LOG.warn(
                    "zMyBatis: failed to dispose rejected console sentinel " +
                        "(type=${cleanupFailure.javaClass.name})"
                )
            }
        } catch (cleanupFatal: Throwable) {
            preservePrimaryOrRethrowCleanup(primaryFailure, cleanupFatal)
        }
    }

    private fun preservePrimaryOrRethrowCleanup(
        primaryFailure: Throwable,
        cleanupFailure: Throwable,
    ) {
        if (primaryFailure is ProcessCanceledException || primaryFailure !is Exception) {
            primaryFailure.addSuppressed(cleanupFailure)
            return
        }

        cleanupFailure.addSuppressed(primaryFailure)
        throw cleanupFailure
    }

    /**
     * Atomically closes resource acquisition and startup migration transitions.
     *
     * Persistence stores are separate services, so shutdown never writes persistence from live
     * console resources.
     */
    fun markShuttingDown() {
        synchronized(lifecycleLock) {
            shuttingDown = true
            activeSelections.clear()
        }
        LOG.info("zMyBatis: markShuttingDown — console acquisition and migration disabled")
    }

    override fun dispose() {
        synchronized(lifecycleLock) {
            shuttingDown = true
            activeSelections.clear()
            cache.clear()
        }
    }
}
