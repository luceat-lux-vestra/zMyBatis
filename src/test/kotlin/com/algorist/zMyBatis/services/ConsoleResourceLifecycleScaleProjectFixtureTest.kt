package com.algorist.zMyBatis.services

import com.algorist.zMyBatis.execution.DatabaseToolsConsoleAcquisitionFailure
import com.algorist.zMyBatis.execution.DatabaseToolsConsoleAdapter
import com.intellij.database.console.JdbcConsole
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.psi.DbDataSource
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.DbImplUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.CheckedDisposable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ConsoleResourceLifecycleScaleProjectFixtureTest : BasePlatformTestCase() {

    private val projectTeardownLifetimes = mutableListOf<CheckedDisposable>()
    private var projectTeardownPolicy: String? = null

    fun testRepeatedReuseCompletionKeepsOneCachedConsoleAndNoLeaseStranded() {
        val cache = ConsoleCacheService.getInstance(project)
        val dataSource = registeredDbDataSource(
            name = "zMyBatis resource lifecycle reuse",
            url = "jdbc:h2:mem:zmybatis_resource_reuse",
        )
        val adapter = DatabaseToolsConsoleAdapter.getInstance(project)
        val key = "file:///tmp/zmybatis/ResourceReuseMapper.xml"
        val uniqueConsoles = mutableListOf<JdbcConsole>()
        var failure: DatabaseToolsConsoleAcquisitionFailure? = null

        try {
            repeat(REUSE_ITERATIONS) {
                adapter.acquireAndDeliverConsole(
                    dataSource = dataSource,
                    schema = null,
                    resourceKey = key,
                    forceNew = false,
                    onConsoleReady = { console, release ->
                        if (uniqueConsoles.none { it === console }) {
                            uniqueConsoles += console
                        }
                        release()
                    },
                    onFailure = { failure = it },
                )

                assertNull(failure)
                assertTrue(
                    "terminal REUSE completion must release keyed ownership",
                    cache.beginReuseLease(key),
                )
                cache.endReuseLease(key)
            }

            assertEquals(1, uniqueConsoles.size)
            assertSame(uniqueConsoles.single(), cache.get(key))

            val lifetime = Disposer.newCheckedDisposable(uniqueConsoles.single())
            assertFalse(lifetime.isDisposed)
            cache.evict(key)
            assertTrue("evicting the bounded REUSE owner must dispose its console", lifetime.isDisposed)

            println(
                "ZMYBATIS_RESOURCE_LIFECYCLE phase=repeated-completion " +
                    "policy=REUSE iterations=$REUSE_ITERATIONS " +
                    "distinct_consoles=${uniqueConsoles.size} cache_retained_before_evict=1 " +
                    "lease_stranded=false disposed_after_evict=true",
            )
        } finally {
            cache.evict(key)
        }
    }

    fun testRepeatedNewEachCompletionRemainsOutsideReuseCacheUntilProjectTeardown() {
        val cache = ConsoleCacheService.getInstance(project)
        val dataSource = registeredDbDataSource(
            name = "zMyBatis resource lifecycle new-each",
            url = "jdbc:h2:mem:zmybatis_resource_new_each",
        )
        val adapter = DatabaseToolsConsoleAdapter.getInstance(project)
        val key = "file:///tmp/zmybatis/ResourceNewEachMapper.xml"
        val consoles = mutableListOf<JdbcConsole>()
        var failure: DatabaseToolsConsoleAcquisitionFailure? = null

        repeat(NEW_EACH_ITERATIONS) {
            adapter.acquireAndDeliverConsole(
                dataSource = dataSource,
                schema = null,
                resourceKey = key,
                forceNew = true,
                onConsoleReady = { console, release ->
                    consoles += console
                    projectTeardownLifetimes += Disposer.newCheckedDisposable(console)
                    release()
                },
                onFailure = { failure = it },
            )
            assertNull(failure)
        }

        assertEquals(NEW_EACH_ITERATIONS, consoles.size)
        consoles.forEachIndexed { index, console ->
            assertTrue(
                "NEW_EACH iteration $index must produce a distinct console",
                consoles.take(index).none { previous -> previous === console },
            )
        }
        assertNull("NEW_EACH must remain outside the zMyBatis REUSE cache", cache.get(key))
        assertEquals(
            NEW_EACH_ITERATIONS,
            projectTeardownLifetimes.count { !it.isDisposed },
        )
        projectTeardownPolicy = "NEW_EACH"

        println(
            "ZMYBATIS_RESOURCE_LIFECYCLE phase=repeated-completion " +
                "policy=NEW_EACH iterations=$NEW_EACH_ITERATIONS " +
                "distinct_consoles=${consoles.size} cache_retained=0 " +
                "live_after_terminal=${projectTeardownLifetimes.count { !it.isDisposed }} " +
                "ownership=zmybatis-cache:false project_disposal_pending=true",
        )
    }

    fun testCancellationAndFailureDoNotStrandReuseOwnershipOrCacheResources() {
        val cache = ConsoleCacheService.getInstance(project)
        val dataSource = registeredDbDataSource(
            name = "zMyBatis resource lifecycle terminal paths",
            url = "jdbc:h2:mem:zmybatis_resource_terminal",
        )
        val adapter = DatabaseToolsConsoleAdapter.getInstance(project)
        val cancellationKey = "file:///tmp/zmybatis/ResourceCancellationMapper.xml"
        val failureKey = "file:///tmp/zmybatis/ResourceFailureMapper.xml"

        try {
            try {
                adapter.acquireAndDeliverConsole(
                    dataSource = dataSource,
                    schema = null,
                    resourceKey = cancellationKey,
                    forceNew = false,
                    onConsoleReady = { _, _ -> throw ProcessCanceledException() },
                    onFailure = { fail("cancellation must not become an acquisition failure") },
                )
                fail("ProcessCanceledException must propagate")
            } catch (_: ProcessCanceledException) {
                // Expected platform cancellation.
            }

            assertNull(cache.get(cancellationKey))
            assertTrue(cache.beginReuseLease(cancellationKey))
            cache.endReuseLease(cancellationKey)

            var failure: DatabaseToolsConsoleAcquisitionFailure? = null
            adapter.acquireAndDeliverConsole(
                dataSource = dataSource,
                schema = null,
                resourceKey = failureKey,
                forceNew = false,
                onConsoleReady = { _, _ -> error("fixture delivery failure") },
                onFailure = { failure = it },
            )

            assertSame(DatabaseToolsConsoleAcquisitionFailure.ConsoleCreationFailed, failure)
            assertNull(cache.get(failureKey))
            assertTrue(cache.beginReuseLease(failureKey))
            cache.endReuseLease(failureKey)

            println(
                "ZMYBATIS_RESOURCE_LIFECYCLE phase=terminal-cleanup " +
                    "policy=REUSE cancellation_cache_retained=0 failure_cache_retained=0 " +
                    "lease_stranded=false",
            )
        } finally {
            cache.evict(cancellationKey)
            cache.evict(failureKey)
        }
    }

    fun testServiceDisposalConvergesAllOwnedReuseResources() {
        val cache = ConsoleCacheService(project)
        val dataSource = registeredDbDataSource(
            name = "zMyBatis resource lifecycle service disposal",
            url = "jdbc:h2:mem:zmybatis_resource_service_disposal",
        )
        val lifetimes = mutableListOf<CheckedDisposable>()

        repeat(SERVICE_OWNED_RESOURCES) { index ->
            val console = newConsole(dataSource, "service-owned-$index.sql")
            lifetimes += Disposer.newCheckedDisposable(console)
            assertTrue(
                cache.putEphemeral(
                    mapperKey = "file:///tmp/zmybatis/ServiceOwned$index.xml",
                    console = console,
                ),
            )
        }

        assertEquals(SERVICE_OWNED_RESOURCES, lifetimes.count { !it.isDisposed })
        Disposer.dispose(cache)
        assertEquals(SERVICE_OWNED_RESOURCES, lifetimes.count { it.isDisposed })

        println(
            "ZMYBATIS_RESOURCE_LIFECYCLE phase=service-disposal " +
                "policy=REUSE owned_before=$SERVICE_OWNED_RESOURCES " +
                "disposed_after=$SERVICE_OWNED_RESOURCES",
        )
    }

    override fun tearDown() {
        val lifetimes = projectTeardownLifetimes.toList()
        val policy = projectTeardownPolicy
        try {
            super.tearDown()
        } finally {
            if (lifetimes.isNotEmpty()) {
                val disposed = lifetimes.count { it.isDisposed }
                println(
                    "ZMYBATIS_RESOURCE_LIFECYCLE phase=project-disposal " +
                        "policy=${policy ?: "UNKNOWN"} resources=${lifetimes.size} disposed=$disposed",
                )
                assertEquals(
                    "project teardown must dispose real NEW_EACH console resources",
                    lifetimes.size,
                    disposed,
                )
            }
        }
    }

    private fun registeredDbDataSource(
        name: String,
        url: String,
    ): DbDataSource {
        val local = LocalDataSource().apply {
            this.name = name
            driverClass = "org.h2.Driver"
            this.url = url
            isAutoSynchronize = false
        }
        val manager = LocalDataSourceManager.getInstance(project)
        manager.addDataSource(local)
        Disposer.register(testRootDisposable) { manager.removeDataSource(local) }

        return requireNotNull(
            DbPsiFacade.getInstance(project).dataSources.firstOrNull { candidate ->
                DbImplUtil.getMaybeLocalDataSource(candidate) === local
            },
        ) {
            "registered LocalDataSource must be exposed as DbDataSource"
        }
    }

    private fun newConsole(
        dataSource: DbDataSource,
        fileName: String,
    ): JdbcConsole =
        JdbcConsole.newConsole(project)
            .fromDataSource(dataSource)
            .forFile(
                com.intellij.testFramework.LightVirtualFile(
                    fileName,
                    com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
                        .getFileTypeByExtension("sql"),
                    "",
                ),
            )
            .build()

    companion object {
        private const val REUSE_ITERATIONS = 32
        private const val NEW_EACH_ITERATIONS = 16
        private const val SERVICE_OWNED_RESOURCES = 8
    }
}
