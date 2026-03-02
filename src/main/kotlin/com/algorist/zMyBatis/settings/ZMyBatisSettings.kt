package com.algorist.zMyBatis.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Application-level persistent settings for zMyBatis.
 *
 * Stored in: `{IDE config dir}/options/zMyBatis.xml`
 *
 * Settings:
 *  - [rememberLastInputs]    : Whether to pre-fill the parameter dialog with the last-used values.
 *  - [emptyInputPolicy]      : How to treat blank input fields in the parameter dialog.
 *  - [sqlPreview]            : Show a preview dialog before executing the resolved SQL.
 *  - [autoFormatSql]         : Reformat the resolved SQL before executing/previewing.
 *  - [consoleSessionPolicy]  : Whether to reuse an existing console or always open a new one.
 */
@Service(Service.Level.APP)
@State(
    name = "ZMyBatisSettings",
    storages = [Storage("zMyBatis.xml")]
)
class ZMyBatisSettings : PersistentStateComponent<ZMyBatisSettings.State> {

    data class State(
        var rememberLastInputs: Boolean = true,
        var emptyInputPolicy: EmptyInputPolicy = EmptyInputPolicy.NULL,
        var sqlPreview: Boolean = false,
        var autoFormatSql: Boolean = true,
        var consoleSessionPolicy: ConsoleSessionPolicy = ConsoleSessionPolicy.REUSE,
        var copyToClipboard: Boolean = true
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    // ── Convenience accessors ─────────────────────────────────────────────

    var rememberLastInputs: Boolean
        get() = myState.rememberLastInputs
        set(value) { myState.rememberLastInputs = value }

    var emptyInputPolicy: EmptyInputPolicy
        get() = myState.emptyInputPolicy
        set(value) { myState.emptyInputPolicy = value }

    var sqlPreview: Boolean
        get() = myState.sqlPreview
        set(value) { myState.sqlPreview = value }

    var autoFormatSql: Boolean
        get() = myState.autoFormatSql
        set(value) { myState.autoFormatSql = value }

    var consoleSessionPolicy: ConsoleSessionPolicy
        get() = myState.consoleSessionPolicy
        set(value) { myState.consoleSessionPolicy = value }

    var copyToClipboard: Boolean
        get() = myState.copyToClipboard
        set(value) { myState.copyToClipboard = value }

    companion object {
        fun getInstance(): ZMyBatisSettings =
            ApplicationManager.getApplication().getService(ZMyBatisSettings::class.java)
    }
}

/**
 * Policy for how blank/empty parameter inputs are interpreted.
 *
 * - [NULL]           : empty input → `null`  (MyBatis binds `NULL` in the query)
 * - [EMPTY_STRING]   : empty input → `""`    (MyBatis binds an empty string)
 */
enum class EmptyInputPolicy {
    NULL,
    EMPTY_STRING
}

/**
 * Policy for DB console session management.
 *
 * - [REUSE]     : reuse the already-open console for the same mapper file (default)
 * - [NEW_EACH]  : always open a brand-new console tab for every execution
 */
enum class ConsoleSessionPolicy {
    REUSE,
    NEW_EACH
}

