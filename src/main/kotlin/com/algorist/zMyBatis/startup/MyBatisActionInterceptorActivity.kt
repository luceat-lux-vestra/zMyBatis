package com.algorist.zMyBatis.startup

import com.algorist.zMyBatis.services.ConsoleCacheService
import com.algorist.zMyBatis.services.ExecutionTargetDescriptorStore
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFileManager

/**
 * Startup owns fail-closed maintenance of current v3 target descriptors only.
 *
 * It never creates Database Tools resources, restores a console, migrates an older persistence
 * generation, or executes SQL.
 */
class MyBatisActionInterceptorActivity : ProjectActivity {

    companion object {
        private val LOG = Logger.getInstance(MyBatisActionInterceptorActivity::class.java)
    }

    override suspend fun execute(project: Project) {
        if (project.isDisposed) return

        val cache = ConsoleCacheService.getInstance(project)
        val targetStore = ExecutionTargetDescriptorStore.getInstance(project)
        prunePersistedTargets(
            project = project,
            cache = cache,
            targetStore = targetStore,
            resolveSourceUrl = { url ->
                VirtualFileManager.getInstance()
                    .findFileByUrl(url)
                    ?.takeIf { it.isValid }
                    ?.url
            },
        )
    }

    internal fun prunePersistedTargets(
        project: Project,
        cache: ConsoleCacheService,
        targetStore: ExecutionTargetDescriptorStore,
        resolveSourceUrl: (String) -> String?,
    ) {
        val shouldStop = { project.isDisposed || cache.isShuttingDown() }
        if (shouldStop()) return

        for ((association, _) in targetStore.pruneAndLoadAll()) {
            if (shouldStop()) return
            val sourceFileId = association.sourceFileId
            val value = sourceFileId.value
            if (!value.startsWith("vfs:")) continue

            val expectedUrl = value.removePrefix("vfs:")
            val resolvedUrl = if (expectedUrl.isEmpty()) null else resolveSourceUrl(expectedUrl)
            if (shouldStop()) return
            if (expectedUrl.isEmpty() || resolvedUrl != expectedUrl) {
                LOG.info("zMyBatis: pruning v3 target selection whose source can no longer be proven")
                targetStore.remove(sourceFileId)
            }
        }
    }
}
