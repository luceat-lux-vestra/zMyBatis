package com.algorist.zMyBatis.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

/**
 * Settings page shown at:
 *   Settings / Preferences → Tools → zMyBatis
 */
class ZMyBatisConfigurable : BoundConfigurable("zMyBatis") {

    private val settings = ZMyBatisSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {

        group("Parameter Dialog") {

            row {
                checkBox("Remember last parameter inputs per Mapper statement")
                    .bindSelected(
                        getter = { settings.rememberLastInputs },
                        setter = { settings.rememberLastInputs = it }
                    )
            }
            row {
                label("Empty input handling:")
            }
            row {
                comboBox(EmptyInputPolicy.entries)
                    .bindItem(
                        getter = { settings.emptyInputPolicy },
                        setter = { settings.emptyInputPolicy = it ?: EmptyInputPolicy.NULL }
                    )
                contextHelp(
                    "Determines how blank fields in the parameter dialog are treated.\n" +
                    "NULL  → binds SQL NULL\n" +
                    "EMPTY_STRING  → binds an empty string \"\""
                )
            }
        }
    }
}

