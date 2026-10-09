package com.algorist.zMyBatis.services

import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.ExplicitSchemaIdentity
import com.algorist.zMyBatis.core.execution.StableDataSourceId
import com.algorist.zMyBatis.execution.DatabaseToolsConsoleAdapter
import com.algorist.zMyBatis.execution.DatabaseToolsSqlExecutionFailure
import com.intellij.database.console.JdbcConsole
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

    fun testSelectionGuardIsScopedToProjectService() {
        val cache = ConsoleCacheService.getInstance(project)
        val mapperKey = "file:///tmp/zmybatis/Mapper.xml"

        assertTrue(cache.beginSelection(mapperKey))
        assertFalse(cache.beginSelection(mapperKey))
        cache.endSelection(mapperKey)
        assertTrue(cache.beginSelection(mapperKey))
        cache.endSelection(mapperKey)
    }

    fun testCacheTargetIdentityPolicyRejectsCrossTargetAndNullMismatch() {
        val targetA = targetId("ds-a", "public")
        val targetB = targetId("ds-b", "public")

        assertTrue(consoleCacheTargetIdentityMatches(targetA, targetA))
        assertFalse(consoleCacheTargetIdentityMatches(targetA, targetB))
        assertFalse(consoleCacheTargetIdentityMatches(targetA, null))
        assertFalse(consoleCacheTargetIdentityMatches(null, targetA))
        assertTrue(
            "null-to-null is the deliberate legacy in-process path only",
            consoleCacheTargetIdentityMatches(null, null),
        )
    }

    fun testShutdownGateRejectsNewSelection() {
        val cache = newIsolatedCache()
        val mapperKey = "file:///tmp/zmybatis/ClosingMapper.xml"

        assertFalse(cache.isShuttingDown())
        assertTrue(cache.beginSelection(mapperKey))
        cache.endSelection(mapperKey)

        cache.markShuttingDown()

        assertTrue(cache.isShuttingDown())
        assertFalse(cache.beginSelection(mapperKey))
    }

    fun testProjectClosingHookEntersSameShutdownGate() {
        val cache = newIsolatedCache()

        assertFalse(cache.isShuttingDown())

        cache.handleProjectClosing(project)

        assertTrue(cache.isShuttingDown())
        assertFalse(cache.beginSelection("file:///tmp/zmybatis/AfterCloseMapper.xml"))
    }

    fun testShutdownGateRejectsStartupMigrationTransition() {
        val cache = newIsolatedCache()
        var transitionRan = false

        cache.markShuttingDown()
        val completed = cache.runStartupMigrationTransitionIfActive {
            transitionRan = true
        }

        assertFalse(completed)
        assertFalse(transitionRan)
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

    private fun newConsole(): JdbcConsole =
        JdbcConsole.newConsole(project)
            .forFile(LightVirtualFile("lifetime.sql", FileTypeManager.getInstance().getFileTypeByExtension("sql"), ""))
            .build()
}
