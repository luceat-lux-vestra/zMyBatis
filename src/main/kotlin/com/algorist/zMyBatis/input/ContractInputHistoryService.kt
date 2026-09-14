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
 * All input text is treated as potentially sensitive. The global remember-inputs setting only
 * enables the feature: each bound value must also be explicitly selected for retention by the user
 * before it may reach this storage boundary. Retention is keyed by the complete canonical
 * [StatementId]. Loaded values remain presentation drafts and need a separate explicit acceptance
 * before [ContractInputAdapter] may turn them into execution input. Raw `${}` interpolation is
 * never persisted or restored, even if a caller attempts to select it.
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

    fun save(
        contract: ParameterContract,
        rawValues: Map<InputRequirementId, String>,
        explicitlyRetainedIds: Set<InputRequirementId>,
    ) {
        if (contract.isPreparationBlocked) return
        val retained = ContractInputHistoryPolicy.filterForSave(
            contract = contract,
            rawValues = rawValues,
            explicitlyRetainedIds = explicitlyRetainedIds,
        )
        val key = ContractInputRetentionKey.of(contract.statementId).value
        if (retained.isEmpty()) {
            myState.history.remove(key)
        } else {
            myState.history[key] = retained.toMutableMap()
        }
    }

    fun load(contract: ParameterContract): Map<InputRequirementId, String> {
        if (contract.isPreparationBlocked) return emptyMap()
        val key = ContractInputRetentionKey.of(contract.statementId).value
        return ContractInputHistoryPolicy.restore(contract, myState.history[key].orEmpty())
    }

    fun clear(statementId: StatementId) {
        myState.history.remove(ContractInputRetentionKey.of(statementId).value)
    }

    companion object {
        fun getInstance(project: Project): ContractInputHistoryService = project.service()
    }
}

internal object ContractInputHistoryPolicy {
    fun filterForSave(
        contract: ParameterContract,
        rawValues: Map<InputRequirementId, String>,
        explicitlyRetainedIds: Set<InputRequirementId>,
    ): Map<String, String> {
        if (contract.isPreparationBlocked || explicitlyRetainedIds.isEmpty()) return emptyMap()
        val retainableIds = retainableIds(contract).intersect(explicitlyRetainedIds)
        return rawValues
            .filterKeys { it in retainableIds }
            .entries
            .sortedBy { it.key.value }
            .associateTo(linkedMapOf()) { it.key.value to it.value }
    }

    fun restore(
        contract: ParameterContract,
        storedValues: Map<String, String>,
    ): Map<InputRequirementId, String> {
        if (contract.isPreparationBlocked) return emptyMap()
        val retainableIds = retainableIds(contract)
        return storedValues.entries
            .mapNotNull { (rawId, value) ->
                val id = runCatching { InputRequirementId(rawId) }.getOrNull() ?: return@mapNotNull null
                if (id in retainableIds) id to value else null
            }
            .sortedBy { it.first.value }
            .associateTo(linkedMapOf()) { (id, value) -> id to value }
    }

    private fun retainableIds(contract: ParameterContract): Set<InputRequirementId> =
        contract.requirements
            .asSequence()
            .filter { it.kind != InputKind.RAW_INTERPOLATION }
            .map { it.id }
            .toSet()
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
