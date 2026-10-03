package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.execution.ExecutionTargetDescriptor
import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.ExplicitSchemaIdentity
import com.algorist.zMyBatis.core.execution.SourceTargetAssociation
import com.algorist.zMyBatis.core.execution.StableDataSourceId
import com.algorist.zMyBatis.core.execution.TargetResolutionFailure
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.services.ExecutionTargetDescriptorStore
import com.algorist.zMyBatis.services.PersistedExecutionTargetSelection
import com.intellij.database.model.DasNamespace
import com.intellij.database.psi.DbDataSource
import com.intellij.openapi.project.Project

/**
 * Migration-only bridge from the shipping legacy action to #65's persisted target authority.
 *
 * It owns target persistence and exact re-resolution, but not SQL preparation/execution or console
 * lifecycle. #66 may delete this bridge when the Leap action becomes authoritative.
 */
internal class StoredExecutionTargetBridge private constructor(
    private val loadSelection: (SourceFileId) -> PersistedExecutionTargetSelection?,
    private val saveSelection: (PersistedExecutionTargetSelection) -> Unit,
    private val removeSelection: (SourceFileId) -> Unit,
    private val resolveDescriptor: (ExecutionTargetDescriptor) -> DatabaseToolsTargetResolution,
) {
    companion object {
        fun forProject(project: Project): StoredExecutionTargetBridge {
            val store = ExecutionTargetDescriptorStore.getInstance(project)
            val resolver = DatabaseToolsTargetResolver(project)
            return StoredExecutionTargetBridge(
                loadSelection = store::load,
                saveSelection = { selection ->
                    store.save(selection.association, selection.descriptor)
                },
                removeSelection = store::remove,
                resolveDescriptor = resolver::resolve,
            )
        }

        internal fun forTest(
            loadSelection: (SourceFileId) -> PersistedExecutionTargetSelection?,
            saveSelection: (PersistedExecutionTargetSelection) -> Unit,
            removeSelection: (SourceFileId) -> Unit,
            resolveDescriptor: (ExecutionTargetDescriptor) -> DatabaseToolsTargetResolution,
        ): StoredExecutionTargetBridge =
            StoredExecutionTargetBridge(
                loadSelection = loadSelection,
                saveSelection = saveSelection,
                removeSelection = removeSelection,
                resolveDescriptor = resolveDescriptor,
            )
    }

    fun resolve(sourceFileId: SourceFileId): StoredExecutionTargetResolution {
        val selection = loadSelection(sourceFileId) ?: return StoredExecutionTargetResolution.Missing
        return when (val resolved = resolveDescriptor(selection.descriptor)) {
            is DatabaseToolsTargetResolution.Success ->
                StoredExecutionTargetResolution.Success(
                    targetId = selection.descriptor.targetId,
                    dataSource = resolved.dataSource,
                    schema = resolved.schema,
                )
            is DatabaseToolsTargetResolution.Failed -> {
                removeSelection(sourceFileId)
                StoredExecutionTargetResolution.Invalid(resolved.failure)
            }
        }
    }

    fun remember(
        sourceFileId: SourceFileId,
        dataSource: DbDataSource,
        schemaName: String?,
    ): Boolean =
        rememberIdentity(
            sourceFileId = sourceFileId,
            stableDataSourceId = databaseToolsStableDataSourceId(dataSource),
            dataSourceDisplayName = dataSource.name,
            schemaName = schemaName,
        )

    fun rememberTarget(
        sourceFileId: SourceFileId,
        dataSource: DbDataSource,
        schemaName: String?,
    ): ExecutionTargetId? {
        val targetId = targetIdOrNull(
            stableDataSourceId = databaseToolsStableDataSourceId(dataSource),
            schemaName = schemaName,
        ) ?: run {
            removeSelection(sourceFileId)
            return null
        }

        return try {
            saveSelection(
                PersistedExecutionTargetSelection(
                    association = SourceTargetAssociation(sourceFileId, targetId),
                    descriptor = ExecutionTargetDescriptor(
                        targetId = targetId,
                        dataSourceDisplayName = dataSource.name.takeIf { it.isNotEmpty() },
                    ),
                ),
            )
            targetId
        } catch (_: IllegalArgumentException) {
            removeSelection(sourceFileId)
            null
        }
    }

    /**
     * Non-mutating exact-target revalidation for an already-started invocation.
     *
     * A stale target is deliberately not removed here: pre-execution validation must not mutate
     * persisted authority as a hidden side effect. The next ordinary resolve may prune it.
     */
    fun isCurrent(
        sourceFileId: SourceFileId,
        expectedTargetId: ExecutionTargetId,
    ): Boolean {
        val selection = loadSelection(sourceFileId) ?: return false
        if (selection.descriptor.targetId != expectedTargetId) return false
        return resolveDescriptor(selection.descriptor) is DatabaseToolsTargetResolution.Success
    }

    internal fun rememberIdentity(
        sourceFileId: SourceFileId,
        stableDataSourceId: String?,
        dataSourceDisplayName: String,
        schemaName: String?,
    ): Boolean {
        val targetId = targetIdOrNull(stableDataSourceId, schemaName)
        if (targetId == null) {
            removeSelection(sourceFileId)
            return false
        }

        return try {
            saveSelection(
                PersistedExecutionTargetSelection(
                    association = SourceTargetAssociation(sourceFileId, targetId),
                    descriptor = ExecutionTargetDescriptor(
                        targetId = targetId,
                        dataSourceDisplayName = dataSourceDisplayName.takeIf { it.isNotEmpty() },
                    ),
                ),
            )
            true
        } catch (_: IllegalArgumentException) {
            removeSelection(sourceFileId)
            false
        }
    }

    private fun targetIdOrNull(
        stableDataSourceId: String?,
        schemaName: String?,
    ): ExecutionTargetId? {
        val dataSourceId = stableDataSourceId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val explicitSchema = schemaName?.takeIf { it.isNotBlank() } ?: return null
        return try {
            ExecutionTargetId(
                dataSourceId = StableDataSourceId(dataSourceId),
                schema = ExplicitSchemaIdentity(explicitSchema),
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

internal sealed interface StoredExecutionTargetResolution {
    data object Missing : StoredExecutionTargetResolution

    data class Success(
        val targetId: ExecutionTargetId,
        val dataSource: DbDataSource,
        val schema: DasNamespace,
    ) : StoredExecutionTargetResolution

    data class Invalid(
        val failure: TargetResolutionFailure,
    ) : StoredExecutionTargetResolution
}
