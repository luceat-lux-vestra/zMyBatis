package com.algorist.zMyBatis.services

import com.algorist.zMyBatis.core.execution.ExecutionTargetDescriptor
import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.ExplicitSchemaIdentity
import com.algorist.zMyBatis.core.execution.SourceTargetAssociation
import com.algorist.zMyBatis.core.execution.StableDataSourceId
import com.algorist.zMyBatis.core.source.SourceFileId

/**
 * Migration-only bridge from legacy v2 console-session persistence into the v3 target-selection
 * domain. #260 owns this bridge; later #65 startup migration should delete it once v2 state has no
 * supported reader.
 *
 * The bridge never guesses canonical source identity. The caller must first prove the canonical
 * [SourceFileId], and it must exactly match the v2 writer's VirtualFile.url convention.
 */
internal object LegacyV2TargetSelectionMigration {
    fun convert(
        session: PersistedConsoleSession,
        canonicalSourceFileId: SourceFileId?,
    ): PersistedExecutionTargetSelection? {
        val sourceFileId = canonicalSourceFileId ?: return null
        if (sourceFileId.value != "vfs:${session.mapperKey}") return null

        return try {
            val targetId = ExecutionTargetId(
                dataSourceId = StableDataSourceId(session.dataSourceId),
                schema = ExplicitSchemaIdentity(session.schemaName),
            )
            PersistedExecutionTargetSelection(
                association = SourceTargetAssociation(sourceFileId, targetId),
                descriptor = ExecutionTargetDescriptor(
                    targetId = targetId,
                    dataSourceDisplayName = session.dataSourceName.takeIf { it.isNotEmpty() },
                ),
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
