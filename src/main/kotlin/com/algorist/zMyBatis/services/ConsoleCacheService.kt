package com.algorist.zMyBatis.services

import com.algorist.zMyBatis.core.execution.ExecutionTargetId
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
internal fun consoleCacheTargetIdentityMatches(
    cachedTargetId: ExecutionTargetId?,
    expectedTargetId: ExecutionTargetId?,
): Boolean = cachedTargetId == expectedTargetId

@Suppress("TooGenericExceptionCaught")
internal fun <T> disposeOwnedResourcesPreservingFailureSemantics(
    resources: Iterable<T>,
    isDisposed: (T) -> Boolean,
    disposeResource: (T) -> Unit,
) {
    var primaryFailure: Throwable? = null

    for (resource in resources) {
        if (isDisposed(resource)) continue
        try {
            disposeResource(resource)
        } catch (failure: Throwable) {
            primaryFailure = combineResourceDisposalFailure(primaryFailure, failure)
        }
    }

    primaryFailure?.let { throw it }
}

private fun combineResourceDisposalFailure(
    primaryFailure: Throwable?,
    nextFailure: Throwable,
): Throwable {
    if (primaryFailure == null) return nextFailure

    val primaryIsStrong = primaryFailure is ProcessCanceledException || primaryFailure !is Exception
    val nextIsStrong = nextFailure is ProcessCanceledException || nextFailure !is Exception

    return if (!primaryIsStrong && nextIsStrong) {
        if (nextFailure !== primaryFailure) nextFailure.addSuppressed(primaryFailure)
        nextFailure
    } else {
        if (nextFailure !== primaryFailure) primaryFailure.addSuppressed(nextFailure)
        primaryFailure
    }
}

@Service(Service.Level.PROJECT)
class ConsoleCacheService(private val project: Project) : com.intellij.openapi.Disposable {

    companion object {
        private val LOG = Logger.getInstance(ConsoleCacheService::class.java)

        fun getInstance(project: Project): ConsoleCacheService = project.service()
    }

    private class Entry(
        val console: JdbcConsole,
        val sentinel: CheckedDisposable,
        val targetId: ExecutionTargetId?,
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
    fun get(mapperKey: String): JdbcConsole? = get(mapperKey, expectedTargetId = null)

    /**
     * Returns a cached console only when its immutable target identity matches the invocation.
     *
     * A null identity is deliberate legacy in-process state (default schema or unavailable stable
     * datasource id). It never matches a persisted exact target id.
     */
    fun get(
        mapperKey: String,
        expectedTargetId: ExecutionTargetId?,
    ): JdbcConsole? {
        var rejectedEntry: Entry? = null
        val result = synchronized(lifecycleLock) {
            if (shuttingDown) return@synchronized null
            val entry = cache[mapperKey] ?: return@synchronized null
            if (entry.sentinel.isDisposed) {
                if (cache.remove(mapperKey, entry)) {
                    LOG.info("zMyBatis: disposed ephemeral console observed for $mapperKey")
                }
                return@synchronized null
            }
            if (!consoleCacheTargetIdentityMatches(entry.targetId, expectedTargetId)) {
                if (cache.remove(mapperKey, entry)) {
                    rejectedEntry = entry
                    LOG.info("zMyBatis: cached console target no longer matches invocation — evicting")
                }
                return@synchronized null
            }
            entry.console
        }

        rejectedEntry?.console?.let { staleConsole ->
            if (!Disposer.isDisposed(staleConsole)) {
                Disposer.dispose(staleConsole)
            }
        }
        return result
    }

    fun evict(mapperKey: String) {
        val entry = synchronized(lifecycleLock) {
            cache.remove(mapperKey)
        } ?: return

        if (!Disposer.isDisposed(entry.console)) {
            Disposer.dispose(entry.console)
        }
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
        targetId: ExecutionTargetId? = null,
    ): Boolean = registerConsole(mapperKey, console, targetId)

    private fun registerConsole(
        mapperKey: String,
        console: JdbcConsole,
        targetId: ExecutionTargetId?,
    ): Boolean {
        val sentinel = Disposer.newCheckedDisposable(console)
        if (sentinel.isDisposed) {
            LOG.warn("zMyBatis: console already disposed at registration for $mapperKey — rejecting")
            return false
        }

        val entry = Entry(console, sentinel, targetId)
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

        var supersededEntry: Entry? = null
        val accepted = synchronized(lifecycleLock) {
            if (shuttingDown || sentinel.isDisposed) {
                false
            } else {
                supersededEntry = cache.put(mapperKey, entry)
                true
            }
        }

        if (!accepted) {
            LOG.info("zMyBatis: rejecting console registration during shutdown/disposal for $mapperKey")
            if (!sentinel.isDisposed) Disposer.dispose(sentinel)
            return false
        }

        supersededEntry
            ?.takeIf { it.console !== console }
            ?.let { disposeDetachedEntries(listOf(it)) }

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

    private fun disposeDetachedEntries(entries: Iterable<Entry>) {
        disposeOwnedResourcesPreservingFailureSemantics(
            resources = entries.map { it.console },
            isDisposed = { Disposer.isDisposed(it) },
            disposeResource = { Disposer.dispose(it) },
        )
    }

    override fun dispose() {
        val detachedEntries = synchronized(lifecycleLock) {
            shuttingDown = true
            activeSelections.clear()
            val entries = cache.values.toList()
            cache.clear()
            entries
        }

        disposeDetachedEntries(detachedEntries)
    }
}
