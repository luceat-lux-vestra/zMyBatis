package com.algorist.zMyBatis.startup

import com.algorist.zMyBatis.services.ConsoleCacheService
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MyBatisActionInterceptorActivityLifecycleTest : BasePlatformTestCase() {

    fun testShutdownAtSchedulingBoundaryCannotTriggerLateServiceLookupOrRestore() {
        val cache = ConsoleCacheService(project).also { Disposer.register(testRootDisposable, it) }
        val activity = MyBatisActionInterceptorActivity()
        var lookupCount = 0
        var restoreCount = 0
        var scheduledTask: Runnable? = null

        activity.scheduleStartupRestore(
            project = project,
            cacheProvider = {
                lookupCount++
                cache
            },
            scheduler = { task, expired ->
                // The service must already be acquired before this asynchronous boundary. Make
                // shutdown win here, exactly where the old implementation could later attempt a
                // new project.service() lookup from the queued callback.
                assertEquals(1, lookupCount)
                cache.markShuttingDown()
                assertTrue(expired())
                scheduledTask = task
            },
            restore = { _, _ -> restoreCount++ }
        )

        assertEquals(1, lookupCount)
        assertNotNull(scheduledTask)

        scheduledTask!!.run()

        assertEquals(1, lookupCount)
        assertEquals(0, restoreCount)
    }

    fun testActiveScheduledRestoreUsesPreAcquiredServiceExactlyOnce() {
        val cache = ConsoleCacheService(project).also { Disposer.register(testRootDisposable, it) }
        val activity = MyBatisActionInterceptorActivity()
        var lookupCount = 0
        var restoreCount = 0
        var restoredCache: ConsoleCacheService? = null

        activity.scheduleStartupRestore(
            project = project,
            cacheProvider = {
                lookupCount++
                cache
            },
            scheduler = { task, expired ->
                assertEquals(1, lookupCount)
                assertFalse(expired())
                task.run()
            },
            restore = { _, suppliedCache ->
                restoreCount++
                restoredCache = suppliedCache
            }
        )

        assertEquals(1, lookupCount)
        assertEquals(1, restoreCount)
        assertSame(cache, restoredCache)
    }
}
