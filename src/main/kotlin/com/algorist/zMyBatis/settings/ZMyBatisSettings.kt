package com.algorist.zMyBatis.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Application-level persistent settings for the registered Leap execution path.
 *
 * Stored in: `{IDE config dir}/options/zMyBatis.xml`
 *
 * Removed legacy evaluator/dialog fields are intentionally not loaded into runtime state.
 */
@Service(Service.Level.APP)
@State(
    name = "ZMyBatisSettings",
    storages = [Storage("zMyBatis.xml")]
)
class ZMyBatisSettings : PersistentStateComponent<ZMyBatisSettings.State> {

    data class State(
        var rememberLastInputs: Boolean = false,
        var sqlPreview: Boolean = false,
        var autoFormatSql: Boolean = true,
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    var rememberLastInputs: Boolean
        get() = myState.rememberLastInputs
        set(value) { myState.rememberLastInputs = value }

    var sqlPreview: Boolean
        get() = myState.sqlPreview
        set(value) { myState.sqlPreview = value }

    var autoFormatSql: Boolean
        get() = myState.autoFormatSql
        set(value) { myState.autoFormatSql = value }


    companion object {
        fun getInstance(): ZMyBatisSettings =
            ApplicationManager.getApplication().getService(ZMyBatisSettings::class.java)
    }
}
