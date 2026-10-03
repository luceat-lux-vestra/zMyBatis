package com.algorist.zMyBatis.services

import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.ExplicitSchemaIdentity
import com.algorist.zMyBatis.core.execution.StableDataSourceId
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ConsoleCacheServiceLifecycleTest : BasePlatformTestCase() {

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
}
