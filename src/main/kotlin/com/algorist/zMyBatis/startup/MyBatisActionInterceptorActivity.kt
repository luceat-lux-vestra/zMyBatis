package com.algorist.zMyBatis.startup

import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.services.ConsoleCacheService
import com.algorist.zMyBatis.services.ExecutionTargetDescriptorStore
import com.algorist.zMyBatis.services.LegacyV2ConsoleSessionMigrationStore
import com.algorist.zMyBatis.services.LegacyV2TargetSelectionMigration
import com.algorist.zMyBatis.services.PersistedConsoleSession
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFileManager

/**
 * Startup owns persistence migration/cleanup only.
 *
 * It deliberately does not create JdbcConsole resources. A console is acquired only from an
 * explicit execution action after the persisted target has been re-resolved exactly.
 */
class MyBatisActionInterceptorActivity : ProjectActivity {

    companion object {
        private val LOG = Logger.getInstance(MyBatisActionInterceptorActivity::class.java)
    }

    override suspend fun execute(project: Project) {
        if (project.isDisposed) return

        val cache = ConsoleCacheService.getInstance(project)
        val migrationStore = LegacyV2ConsoleSessionMigrationStore.getInstance(project)
        val targetStore = ExecutionTargetDescriptorStore.getInstance(project)
        migratePersistedSelections(
            project = project,
            cache = cache,
            migrationStore = migrationStore,
            targetStore = targetStore,
            resolveSourceUrl = { url ->
                VirtualFileManager.getInstance()
                    .findFileByUrl(url)
                    ?.takeIf { it.isValid }
                    ?.url
            },
        )
    }

    /**
     * Migrates v2 mapper-session identity to v3 source/target selection without constructing any
     * Database Tools execution resource.
     *
     * v3 wins if an interrupted migration leaves both generations present. The migration therefore
     * never overwrites an already valid v3 selection with older v2 state.
     */
    internal fun migratePersistedSelections(
        project: Project,
        cache: ConsoleCacheService,
        migrationStore: LegacyV2ConsoleSessionMigrationStore,
        targetStore: ExecutionTargetDescriptorStore,
        resolveSourceUrl: (String) -> String?,
    ) {
        if (project.isDisposed || cache.isShuttingDown()) return

        val shouldStop = { project.isDisposed || cache.isShuttingDown() }
        pruneMissingV3Sources(targetStore, resolveSourceUrl, shouldStop)
        if (shouldStop()) return

        var sessions = emptyList<PersistedConsoleSession>()
        val indexTransitionCompleted = cache.runStartupMigrationTransitionIfActive {
            sessions = migrationStore.pruneStaleIndex()
        }
        if (!indexTransitionCompleted || sessions.isEmpty()) return

        for (session in sessions) {
            if (project.isDisposed || cache.isShuttingDown()) return

            val resolvedUrl = resolveSourceUrl(session.mapperKey)
            if (shouldStop()) return
            if (resolvedUrl != session.mapperKey) {
                LOG.info("zMyBatis: pruning v2 target selection whose source can no longer be proven")
                if (!clearV2IfActive(cache, migrationStore, session.mapperKey)) return
                continue
            }

            val sourceFileId = try {
                SourceFileId("vfs:$resolvedUrl")
            } catch (_: IllegalArgumentException) {
                if (!clearV2IfActive(cache, migrationStore, session.mapperKey)) return
                continue
            }

            val migrated = LegacyV2TargetSelectionMigration.convert(session, sourceFileId)
            val transitionCompleted = cache.runStartupMigrationTransitionIfActive {
                if (targetStore.load(sourceFileId) == null) {
                    if (migrated == null) {
                        migrationStore.clearSession(session.mapperKey)
                        return@runStartupMigrationTransitionIfActive
                    }
                    targetStore.save(migrated.association, migrated.descriptor)
                }

                // Save/validate v3 first. If cleanup is interrupted, duplicate v2+v3 state is
                // harmless and the next startup keeps v3 authoritative before removing v2 again.
                migrationStore.clearSession(session.mapperKey)
            }
            if (!transitionCompleted) return
        }
    }

    private fun clearV2IfActive(
        cache: ConsoleCacheService,
        migrationStore: LegacyV2ConsoleSessionMigrationStore,
        mapperKey: String,
    ): Boolean = cache.runStartupMigrationTransitionIfActive {
        migrationStore.clearSession(mapperKey)
    }

    private fun pruneMissingV3Sources(
        targetStore: ExecutionTargetDescriptorStore,
        resolveSourceUrl: (String) -> String?,
        shouldStop: () -> Boolean,
    ) {
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
