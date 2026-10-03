package com.algorist.zMyBatis.services

import com.algorist.zMyBatis.core.execution.ExecutionTargetDescriptor
import com.algorist.zMyBatis.core.execution.SourceTargetAssociation
import com.algorist.zMyBatis.core.source.SourceFileId
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Project-scoped persistence for source -> execution-target selections.
 *
 * This service owns only serializable target-selection state. It deliberately has no dependency on
 * JdbcConsole or another live Database Tools resource, so console creation/disposal cannot become
 * persistence authority.
 */
@Service(Service.Level.PROJECT)
internal class ExecutionTargetDescriptorStore(private val project: Project) {
    companion object {
        private val LOG = Logger.getInstance(ExecutionTargetDescriptorStore::class.java)

        internal const val PROPS_PREFIX = "zMyBatis.target.v3."
        internal const val INDEX_KEY = "${PROPS_PREFIX}__index__"
        internal const val RECORD_PREFIX = "${PROPS_PREFIX}record."

        fun getInstance(project: Project): ExecutionTargetDescriptorStore = project.service()
    }

    private val store: PropertiesComponent = PropertiesComponent.getInstance(project)
    private val persistenceLock = Any()

    fun save(
        association: SourceTargetAssociation,
        descriptor: ExecutionTargetDescriptor,
    ) {
        val selection = PersistedExecutionTargetSelection(association, descriptor)
        val id = ExecutionTargetSelectionPersistenceFormat.selectionId(association.sourceFileId)
        synchronized(persistenceLock) {
            addToIndexLocked(id)
            // Keep the index first so an interrupted replacement remains discoverable and prunable.
            store.unsetValue(recordKey(id))
            store.setValue(recordKey(id), ExecutionTargetSelectionPersistenceFormat.encode(selection))
        }
    }

    fun load(sourceFileId: SourceFileId): PersistedExecutionTargetSelection? =
        synchronized(persistenceLock) {
            val id = ExecutionTargetSelectionPersistenceFormat.selectionId(sourceFileId)
            loadSelectionLocked(id)?.takeIf { it.association.sourceFileId == sourceFileId }
        }

    /**
     * Returns only structurally valid records and prunes malformed/stale index entries.
     */
    fun pruneAndLoadAll(): List<PersistedExecutionTargetSelection> =
        synchronized(persistenceLock) {
            val ids = savedSelectionIdsLocked()
            if (ids.isEmpty()) return@synchronized emptyList()

            val valid = mutableListOf<PersistedExecutionTargetSelection>()
            for (id in ids) {
                if (!ExecutionTargetSelectionPersistenceFormat.isValidSelectionId(id)) {
                    LOG.warn("zMyBatis: pruning malformed target-selection index id '$id'")
                    removeFromIndexLocked(id)
                    continue
                }

                val selection = loadSelectionLocked(id)
                if (
                    selection == null ||
                    ExecutionTargetSelectionPersistenceFormat.selectionId(selection.association.sourceFileId) != id
                ) {
                    LOG.info("zMyBatis: pruning stale or mismatched target-selection record '$id'")
                    removeRecordByIdLocked(id)
                    continue
                }

                valid.add(selection)
            }

            valid.sortedBy { it.association.sourceFileId.value }
        }

    fun remove(sourceFileId: SourceFileId) {
        val id = ExecutionTargetSelectionPersistenceFormat.selectionId(sourceFileId)
        synchronized(persistenceLock) {
            removeRecordByIdLocked(id)
        }
    }

    private fun loadSelectionLocked(id: String): PersistedExecutionTargetSelection? {
        val raw = store.getValue(recordKey(id)) ?: return null
        return ExecutionTargetSelectionPersistenceFormat.decode(raw)
    }

    private fun savedSelectionIdsLocked(): List<String> {
        val raw = store.getValue(INDEX_KEY) ?: return emptyList()
        return raw.lineSequence().filter { it.isNotBlank() }.distinct().toList()
    }

    private fun addToIndexLocked(id: String) {
        val ids = savedSelectionIdsLocked().toMutableSet()
        if (ids.add(id)) writeIndexLocked(ids)
    }

    private fun removeFromIndexLocked(id: String) {
        val ids = savedSelectionIdsLocked().filterTo(linkedSetOf()) { it != id }
        writeIndexLocked(ids)
    }

    private fun writeIndexLocked(ids: Set<String>) {
        if (ids.isEmpty()) {
            store.unsetValue(INDEX_KEY)
        } else {
            store.setValue(INDEX_KEY, ids.sorted().joinToString("\n"))
        }
    }

    private fun removeRecordByIdLocked(id: String) {
        if (ExecutionTargetSelectionPersistenceFormat.isValidSelectionId(id)) {
            store.unsetValue(recordKey(id))
        }
        removeFromIndexLocked(id)
    }

    private fun recordKey(id: String): String = "$RECORD_PREFIX$id"
}
