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
        stableDataSourceId: String?,
        dataSourceDisplayName: String,
        schemaName: String?,
    ): Boolean {
        val dataSourceId = stableDataSourceId?.trim()?.takeIf { it.isNotEmpty() }
        val explicitSchema = schemaName?.trim()?.takeIf { it.isNotEmpty() }
        if (dataSourceId == null || explicitSchema == null) {
            removeSelection(sourceFileId)
            return false
        }

        return try {
            val targetId = ExecutionTargetId(
                dataSourceId = StableDataSourceId(dataSourceId),
                schema = ExplicitSchemaIdentity(explicitSchema),
            )
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
}

internal sealed interface StoredExecutionTargetResolution {
    data object Missing : StoredExecutionTargetResolution

    data class Success(
        val dataSource: DbDataSource,
        val schema: DasNamespace,
    ) : StoredExecutionTargetResolution

    data class Invalid(
        val failure: TargetResolutionFailure,
    ) : StoredExecutionTargetResolution
}
