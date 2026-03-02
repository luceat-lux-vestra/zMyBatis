package com.algorist.zMyBatis.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.intellij.openapi.components.service

/**
 * Project-level service that persists the last-used parameter values per Mapper statement.
 *
 * Key   : Mapper statement ID, composed as "{filePath}::{statementId}" (e.g.
 *         "/src/mapper/UserMapper.xml::selectById")
 *         For annotation-based mappers: "{filePath}::{className}#{methodName}"
 *
 * Value : A flat map of { paramName -> rawInputText }.
 *         Stored as raw text (as the user typed), not parsed values,
 *         so it can be put back verbatim into the input fields.
 *
 * Storage: `.idea/zMyBatisHistory.xml` (workspace-level, not shared via VCS)
 */
@Service(Service.Level.PROJECT)
@State(
    name = "ZMyBatisParameterHistory",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)]
)
class ParameterHistoryService(
    @Suppress("UNUSED_PARAMETER") project: Project
) : PersistentStateComponent<ParameterHistoryService.State> {

    /**
     * Outer map : statementKey → inner map
     * Inner map : paramName   → last raw input text
     */
    class State {
        var history: MutableMap<String, MutableMap<String, String>> = mutableMapOf()
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Saves [rawValues] (paramName → raw text) for the given [statementKey].
     * Only non-null entries are stored; null/missing entries are removed.
     */
    fun save(statementKey: String, rawValues: Map<String, String>) {
        if (rawValues.isEmpty()) {
            myState.history.remove(statementKey)
        } else {
            myState.history[statementKey] = rawValues.toMutableMap()
        }
    }

    /**
     * Returns the last saved raw input map for [statementKey], or an empty map.
     */
    fun load(statementKey: String): Map<String, String> =
        myState.history[statementKey] ?: emptyMap()

    companion object {
        fun getInstance(project: Project): ParameterHistoryService = project.service()
    }
}

