package com.algorist.zMyBatis.input

import com.algorist.zMyBatis.core.input.InputKind
import com.algorist.zMyBatis.core.input.InputRequirementId
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.source.JavaStatementId
import com.algorist.zMyBatis.core.source.StatementId
import com.algorist.zMyBatis.core.source.XmlStatementId
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Project-scoped retained input storage for the contract-driven parameter UI.
 *
 * Retention is keyed by the complete canonical [StatementId]. Values are still only presentation
 * drafts: callers must explicitly accept a loaded value before [ContractInputAdapter] may turn it
 * into an execution input. Raw `${}` interpolation is never persisted or restored.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "ZMyBatisContractInputHistory",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
class ContractInputHistoryService(
    @Suppress("UNUSED_PARAMETER") project: Project,
) : PersistentStateComponent<ContractInputHistoryService.State> {
    class State {
        var history: MutableMap<String, MutableMap<String, String>> = mutableMapOf()
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    /**
     * Stores only current, caller-editable, non-raw requirements. Unknown/stale requirement ids and
     * raw interpolation are discarded at this boundary even if a caller attempts to persist them.
     */
    fun save(contract: ParameterContract, rawValues: Map<InputRequirementId, String>) {
        if (contract.isPreparationBlocked) return

        val retainableIds = contract.requirements
            .asSequence()
            .filter { it.kind != InputKind.RAW_INTERPOLATION }
            .map { it.id }
            .toSet()
        val retained = rawValues
            .filterKeys { it in retainableIds }
            .entries
            .sortedBy { it.key.value }
            .associateTo(linkedMapOf()) { it.key.value to it.value }

        val key = ContractInputRetentionKey.of(contract.statementId).value
        if (retained.isEmpty()) {
            myState.history.remove(key)
        } else {
            myState.history[key] = retained.toMutableMap()
        }
    }

    /**
     * Restores presentation drafts only for requirements that still exist and remain non-raw in the
     * current contract. The returned values are not executable until explicitly accepted by the UI.
     */
    fun load(contract: ParameterContract): Map<InputRequirementId, String> {
        if (contract.isPreparationBlocked) return emptyMap()

        val key = ContractInputRetentionKey.of(contract.statementId).value
        val stored = myState.history[key].orEmpty()
        val retainableIds = contract.requirements
            .asSequence()
            .filter { it.kind != InputKind.RAW_INTERPOLATION }
            .map { it.id }
            .toSet()

        return stored.entries
            .mapNotNull { (rawId, value) ->
                val id = runCatching { InputRequirementId(rawId) }.getOrNull() ?: return@mapNotNull null
                if (id in retainableIds) id to value else null
            }
            .sortedBy { it.first.value }
            .associateTo(linkedMapOf()) { it }
    }

    fun clear(statementId: StatementId) {
        myState.history.remove(ContractInputRetentionKey.of(statementId).value)
    }

    fun clearAll() {
        myState.history.clear()
    }

    companion object {
        fun getInstance(project: Project): ContractInputHistoryService = project.service()
    }
}

@JvmInline
value class ContractInputRetentionKey private constructor(val value: String) {
    companion object {
        fun of(statementId: StatementId): ContractInputRetentionKey = ContractInputRetentionKey(
            when (statementId) {
                is XmlStatementId -> listOf(
                    "xml",
                    encode(statementId.sourceFileId.value),
                    encode(statementId.namespace),
                    encode(statementId.statementId),
                ).joinToString("|")

                is JavaStatementId -> buildList {
                    add("java")
                    add(encode(statementId.sourceFileId.value))
                    add(encode(statementId.qualifiedMapperType))
                    add(encode(statementId.methodSignature.name))
                    add(statementId.methodSignature.parameterTypeIdentities.size.toString())
                    statementId.methodSignature.parameterTypeIdentities.forEach { add(encode(it.value)) }
                }.joinToString("|")
            },
        )

        private fun encode(value: String): String = "${value.length}:$value"
    }
}
