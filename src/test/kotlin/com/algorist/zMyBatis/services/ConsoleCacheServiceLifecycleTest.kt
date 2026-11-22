package com.algorist.zMyBatis.services

import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.ExplicitSchemaIdentity
import com.algorist.zMyBatis.core.execution.StableDataSourceId
import com.algorist.zMyBatis.execution.DatabaseToolsConsoleAcquisitionFailure
import com.algorist.zMyBatis.execution.DatabaseToolsConsoleAdapter
import com.algorist.zMyBatis.execution.DatabaseToolsSqlExecutionFailure
import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.session.DatabaseSessionManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.psi.DbDataSource
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.DbImplUtil
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ConsoleCacheServiceLifecycleTest : BasePlatformTestCase() {

    fun testRealConsoleDisposalInvalidatesLifetimeCacheAndExecution() {
        val cache = newIsolatedCache()
        val console = newConsole()
        val lifetime = Disposer.newCheckedDisposable(console)
        val key = "file:///tmp/zmybatis/DisposedConsole.xml"
        try {
            assertTrue("a newly created console must be valid", console.isValid)
            assertTrue(cache.putEphemeral(key, console))
            assertSame(console, cache.get(key))

            Disposer.dispose(console)

            assertTrue(lifetime.isDisposed)
            assertFalse(console.isValid)
            assertNull(cache.get(key))
            var failure: DatabaseToolsSqlExecutionFailure? = null
            var executed = false
            DatabaseToolsConsoleAdapter.getInstance(project).executeSql(
                console, "SELECT 1", { true }, { executed = true }, { failure = it },
            )
            assertEquals(DatabaseToolsSqlExecutionFailure.ConsoleUnavailable, failure)
            assertFalse("a disposed console must never execute", executed)
        } finally {
            if (!lifetime.isDisposed) Disposer.dispose(console)
        }
    }

    fun testInvalidConsoleStillDisposesOwnedChildrenWhenEvicted() {
        val cache = newIsolatedCache()
        val console = newConsole()
        val lifetime = Disposer.newCheckedDisposable(console)
        val key = "file:///tmp/zmybatis/InvalidConsole.xml"
        try {
            assertTrue(console.isValid)
            assertTrue(cache.putEphemeral(key, console))
            // The platform invalidates the parent before disposing its children.
            console.beforeTreeDispose()
            assertFalse(console.isValid)
            assertFalse(lifetime.isDisposed)

            cache.evict(key)

            assertTrue("invalidity must not skip actual resource cleanup", lifetime.isDisposed)
            assertNull(cache.get(key))
        } finally {
            if (!lifetime.isDisposed) Disposer.dispose(console)
        }
    }

    fun testAdapterBorrowsExistingConsoleAndRejectsOverlapUntilRelease() {
        val cache = ConsoleCacheService.getInstance(project)
        val dataSource = registeredDbDataSource(
            name = "zMyBatis existing-console fixture",
            url = "jdbc:h2:mem:zmybatis_existing_console",
        )
        val existing = newConsole(dataSource, "ReuseMapper.xml")
        val lifetime = Disposer.newCheckedDisposable(existing)

        val key = "vfs:file:///tmp/zmybatis/ReuseMapper.xml"
        val adapter = DatabaseToolsConsoleAdapter.getInstance(project)
        var first: JdbcConsole? = null
        var firstRelease: (() -> Unit)? = null
        var failure: DatabaseToolsConsoleAcquisitionFailure? = null

        try {
            adapter.acquireAndDeliverConsole(
                dataSource = dataSource,
                schema = null,
                resourceKey = key,
                onConsoleReady = { console, release ->
                    first = console
                    firstRelease = release
                },
                onFailure = { failure = it },
            )
            assertNull(failure)
            assertSame("shipping acquisition must borrow the existing Database Tools console", existing, first)
            assertFalse("zMyBatis must not dispose a borrowed console", lifetime.isDisposed)
            assertNull("borrowed Database Tools consoles must not enter the zMyBatis resource cache", cache.get(key))

            var overlapping: JdbcConsole? = null
            adapter.acquireAndDeliverConsole(
                dataSource = dataSource,
                schema = null,
                resourceKey = key,
                onConsoleReady = { console, release ->
                    overlapping = console
                    release()
                },
                onFailure = { failure = it },
            )

            assertNull("same-source overlap must not concurrently mutate the shared console", overlapping)
            assertSame(DatabaseToolsConsoleAcquisitionFailure.ReuseBusy, failure)

            firstRelease!!.invoke()
            failure = null

            var reused: JdbcConsole? = null
            adapter.acquireAndDeliverConsole(
                dataSource = dataSource,
                schema = null,
                resourceKey = key,
                onConsoleReady = { console, release ->
                    reused = console
                    release()
                },
                onFailure = { failure = it },
            )

            assertNull(failure)
            assertSame("released invocation must borrow the same platform-owned console", existing, reused)
            assertFalse("terminal handoff must leave the platform console alive", lifetime.isDisposed)
        } finally {
            firstRelease?.invoke()
            if (!lifetime.isDisposed) Disposer.dispose(existing)
        }
    }

    fun testDifferentMapperSourcesCannotConcurrentlyBorrowTheSameDatabaseToolsConsole() {
        val dataSource = registeredDbDataSource(
            name = "zMyBatis shared-console fixture",
            url = "jdbc:h2:mem:zmybatis_shared_console",
        )
        val existing = newConsole(dataSource, "SharedConsole.sql")
        val lifetime = Disposer.newCheckedDisposable(existing)
        val adapter = DatabaseToolsConsoleAdapter.getInstance(project)
        val firstKey = "vfs:file:///tmp/zmybatis/FirstMapper.xml"
        val secondKey = "vfs:file:///tmp/zmybatis/SecondMapper.xml"
        var firstRelease: (() -> Unit)? = null
        var failure: DatabaseToolsConsoleAcquisitionFailure? = null

        try {
            adapter.acquireAndDeliverConsole(
                dataSource = dataSource,
                schema = null,
                resourceKey = firstKey,
                onConsoleReady = { console, release ->
                    assertSame(existing, console)
                    firstRelease = release
                },
                onFailure = { failure = it },
            )
            assertNull(failure)

            var secondDelivered = false
            adapter.acquireAndDeliverConsole(
                dataSource = dataSource,
                schema = null,
                resourceKey = secondKey,
                onConsoleReady = { _, release ->
                    secondDelivered = true
                    release()
                },
                onFailure = { failure = it },
            )

            assertFalse("one console document must never be mutated by two mapper invocations", secondDelivered)
            assertSame(DatabaseToolsConsoleAcquisitionFailure.ReuseBusy, failure)

            firstRelease!!.invoke()
            firstRelease = null
            failure = null

            adapter.acquireAndDeliverConsole(
                dataSource = dataSource,
                schema = null,
                resourceKey = secondKey,
                onConsoleReady = { console, release ->
                    assertSame(existing, console)
                    release()
                },
                onFailure = { failure = it },
            )
            assertNull("console identity lease must become reusable after terminal release", failure)
            assertFalse(lifetime.isDisposed)
        } finally {
            firstRelease?.invoke()
            if (!lifetime.isDisposed) Disposer.dispose(existing)
        }
    }

    fun testAdapterCreatesConsoleWhenNoExistingDatabaseToolsClientExists() {
        val dataSource = registeredDbDataSource(
            name = "zMyBatis no-existing-console fixture",
            url = "jdbc:h2:mem:zmybatis_no_existing_console",
        )
        val key = "vfs:file:///tmp/zmybatis/MissingMapper.xml"
        val sessionDataSource = requireNotNull(DbImplUtil.getMaybeLocalDataSource(dataSource))
        val adapter = DatabaseToolsConsoleAdapter.getInstance(project)
        var delivered: JdbcConsole? = null
        var failure: DatabaseToolsConsoleAcquisitionFailure? = null

        adapter.acquireAndDeliverConsole(
            dataSource = dataSource,
            schema = null,
            resourceKey = key,
            onConsoleReady = { console, release ->
                delivered = console
                release()
            },
            onFailure = { failure = it },
        )

        assertNull(failure)
        val createdConsole = requireNotNull(delivered) {
            "no-candidate flow must create a usable Database Tools console"
        }
        assertTrue(createdConsole.isValid)
        assertEquals("MissingMapper.xml - zMyBatis", createdConsole.file.virtualFile?.name)
        val sessionCandidates = DatabaseSessionManager.getSessions(project, sessionDataSource)
        val attachedSessions = sessionCandidates.filter { session ->
            session.clients.any { client -> client === createdConsole }
        }
        val sessionDiagnostics = sessionCandidates.joinToString("; ") { session ->
            "sessionIdentity=${System.identityHashCode(session)} clients=" +
                session.clients.joinToString { client ->
                    "${client.javaClass.simpleName}@${System.identityHashCode(client)}"
                }
        }
        assertEquals(
            "created console must be owned by exactly one registered Database Tools session; " +
                "createdConsole=${System.identityHashCode(createdConsole)} " +
                "consoleSession=${System.identityHashCode(createdConsole.session)} " +
                "sessionCandidates=[$sessionDiagnostics]",
            1,
            attachedSessions.size,
        )
        val authoritativeSession = attachedSessions.single()
        assertSame(
            "the platform-returned JdbcConsole.session must be the registered session that owns the console",
            createdConsole.session,
            authoritativeSession,
        )
        assertTrue(
            "created console session must remain registered for the selected datasource",
            sessionCandidates.any { session -> session === authoritativeSession },
        )
        assertTrue("terminal completion must release the source lease", ConsoleCacheService.getInstance(project).beginReuseLease(key))
        ConsoleCacheService.getInstance(project).endReuseLease(key)

        Disposer.dispose(createdConsole)
        assertFalse(
            "disposing a zMyBatis-created console must detach it from its authoritative Database Tools session",
            authoritativeSession.clients.any { client -> client === createdConsole },
        )
    }

    fun testAdapterRequiresChooserContextWhenMultipleExistingConsolesRemainAmbiguous() {
        val dataSource = registeredDbDataSource(
            name = "zMyBatis ambiguous-console fixture",
            url = "jdbc:h2:mem:zmybatis_ambiguous_console",
        )
        val first = newConsole(dataSource, "AmbiguousMapper.xml")
        val second = newConsole(dataSource, "AmbiguousMapper.xml")
        val firstLifetime = Disposer.newCheckedDisposable(first)
        val secondLifetime = Disposer.newCheckedDisposable(second)
        val key = "vfs:file:///tmp/zmybatis/AmbiguousMapper.xml"
        var delivered: JdbcConsole? = null
        var failure: DatabaseToolsConsoleAcquisitionFailure? = null

        try {
            DatabaseToolsConsoleAdapter.getInstance(project).acquireAndDeliverConsole(
                dataSource = dataSource,
                schema = null,
                resourceKey = key,
                dataContext = null,
                onConsoleReady = { console, release ->
                    delivered = console
                    release()
                },
                onFailure = { failure = it },
            )

            assertNull(delivered)
            assertSame(DatabaseToolsConsoleAcquisitionFailure.ConsoleSelectionUnavailable, failure)
            assertFalse("candidate selection must not dispose the first platform-owned console", firstLifetime.isDisposed)
            assertFalse("candidate selection must not dispose the second platform-owned console", secondLifetime.isDisposed)
        } finally {
            if (!firstLifetime.isDisposed) Disposer.dispose(first)
            if (!secondLifetime.isDisposed) Disposer.dispose(second)
        }
    }

    fun testCacheTargetMismatchEvictsInsteadOfReusingWrongTarget() {
        val cache = newIsolatedCache()
        val console = newConsole()
        val lifetime = Disposer.newCheckedDisposable(console)
        val key = "file:///tmp/zmybatis/TargetMismatch.xml"
        val targetA = targetId("ds-a", "public")
        val targetB = targetId("ds-b", "public")

        assertTrue(cache.putEphemeral(key, console, targetA))
        assertNull(cache.get(key, targetB))
        assertTrue("wrong-target cache entry must be disposed after eviction", lifetime.isDisposed)
    }

    fun testRuntimeConsoleAssociationPrefersRememberedSourceTargetAndDropsStaleConsole() {
        val cache = newIsolatedCache()
        val dataSource = registeredDbDataSource(
            name = "zMyBatis remembered-console fixture",
            url = "jdbc:h2:mem:zmybatis_remembered_console",
        )
        val first = newConsole(dataSource, "First.sql")
        val second = newConsole(dataSource, "Second.sql")
        val secondLifetime = Disposer.newCheckedDisposable(second)
        val key = "vfs:file:///tmp/zmybatis/Mapper.xml"
        val target = targetId("remembered-ds", "public")

        try {
            cache.rememberConsole(key, target, second)
            assertSame(
                "explicit source+target association must outrank filename/order heuristics",
                second,
                cache.rememberedConsole(key, target),
            )

            Disposer.dispose(second)
            assertTrue(secondLifetime.isDisposed)
            assertNull(
                "disposed remembered console must invalidate the runtime association",
                cache.rememberedConsole(key, target),
            )
        } finally {
            if (first.isValid) Disposer.dispose(first)
            if (!secondLifetime.isDisposed) Disposer.dispose(second)
        }
    }

    fun testReuseLeaseIsKeyScopedAndReleasable() {
        val cache = ConsoleCacheService.getInstance(project)
        val mapperKey = "file:///tmp/zmybatis/Mapper.xml"
        val otherKey = "file:///tmp/zmybatis/OtherMapper.xml"

        assertTrue(cache.beginReuseLease(mapperKey))
        assertFalse("same source must not have overlapping REUSE ownership", cache.beginReuseLease(mapperKey))
        assertTrue("unrelated sources must remain independently usable", cache.beginReuseLease(otherKey))
        cache.endReuseLease(mapperKey)
        assertTrue(cache.beginReuseLease(mapperKey))
        cache.endReuseLease(mapperKey)
        cache.endReuseLease(otherKey)
    }

    fun testCacheTargetIdentityPolicyRejectsCrossTargetAndNullMismatch() {
        val targetA = targetId("ds-a", "public")
        val targetB = targetId("ds-b", "public")

        assertTrue(consoleCacheTargetIdentityMatches(targetA, targetA))
        assertFalse(consoleCacheTargetIdentityMatches(targetA, targetB))
        assertFalse(consoleCacheTargetIdentityMatches(targetA, null))
        assertFalse(consoleCacheTargetIdentityMatches(null, targetA))
        assertTrue(
            "null-to-null is the deliberate non-persisted in-process path only",
            consoleCacheTargetIdentityMatches(null, null),
        )
    }

    fun testShutdownGateRejectsNewSelection() {
        val cache = newIsolatedCache()
        val mapperKey = "file:///tmp/zmybatis/ClosingMapper.xml"

        assertFalse(cache.isShuttingDown())
        assertTrue(cache.beginReuseLease(mapperKey))
        cache.endReuseLease(mapperKey)

        cache.markShuttingDown()

        assertTrue(cache.isShuttingDown())
        assertFalse(cache.beginReuseLease(mapperKey))
    }

    fun testProjectClosingHookEntersSameShutdownGate() {
        val cache = newIsolatedCache()

        assertFalse(cache.isShuttingDown())

        cache.handleProjectClosing(project)

        assertTrue(cache.isShuttingDown())
        assertFalse(cache.beginReuseLease("file:///tmp/zmybatis/AfterCloseMapper.xml"))
    }


    private fun targetId(
        dataSourceId: String,
        schema: String,
    ): ExecutionTargetId =
        ExecutionTargetId(
            StableDataSourceId(dataSourceId),
            ExplicitSchemaIdentity(schema),
        )

    private fun newIsolatedCache(): ConsoleCacheService =
        ConsoleCacheService(project).also { Disposer.register(testRootDisposable, it) }

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
            }
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
                LightVirtualFile(
                    fileName,
                    FileTypeManager.getInstance().getFileTypeByExtension("sql"),
                    "",
                ),
            )
            .build()

    private fun newConsole(): JdbcConsole {
        // Console validity includes registered datasource authority, even without a connection.
        val dataSource = LocalDataSource().apply {
            name = "zMyBatis lifetime fixture"
            driverClass = "org.h2.Driver"
            url = "jdbc:h2:mem:zmybatis_lifetime"
            isAutoSynchronize = false
        }
        val manager = LocalDataSourceManager.getInstance(project)
        manager.addDataSource(dataSource)
        Disposer.register(testRootDisposable) { manager.removeDataSource(dataSource) }
        return JdbcConsole.newConsole(project)
            .fromDataSource(dataSource)
            .forFile(LightVirtualFile("lifetime.sql", FileTypeManager.getInstance().getFileTypeByExtension("sql"), ""))
            .build()
    }
}
