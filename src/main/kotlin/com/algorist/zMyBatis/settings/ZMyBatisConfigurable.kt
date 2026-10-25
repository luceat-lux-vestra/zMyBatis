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
@Suppress("DialogTitleCapitalization")
class ZMyBatisConfigurable : BoundConfigurable("zMyBatis") {

    private val settings = ZMyBatisSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        group("Execution & Output") {
            row {
                checkBox("Show SQL preview before execution")
                    .bindSelected(
                        getter = { settings.sqlPreview },
                        setter = { settings.sqlPreview = it },
                    )
                contextHelp(
                    "Shows the final immutable execution artifact before dispatch.\n" +
                        "Mutation and raw-interpolation safety rules can require preview regardless of this setting.",
                )
            }
            row {
                checkBox("Auto-format SQL preview")
                    .bindSelected(
                        getter = { settings.autoFormatSql },
                        setter = { settings.autoFormatSql = it },
                    )
                contextHelp(
                    "Formats presentation text only; formatting never changes the immutable SQL artifact sent to Database Tools.",
                )
            }
            row {
                label("Console session:")
            }
            row {
                comboBox(ConsoleSessionPolicy.entries)
                    .bindItem(
                        getter = { settings.consoleSessionPolicy },
                        setter = { settings.consoleSessionPolicy = it ?: ConsoleSessionPolicy.REUSE },
                    )
                contextHelp(
                    "REUSE     — reuse a matching live console resource.\n" +
                        "NEW_EACH  — acquire a fresh console for every execution.\n" +
                        "Persisted datasource/schema identity is independent of this resource policy.",
                )
            }
        }

        group("Parameter Dialog") {
            row {
                checkBox("Remember last parameter inputs per Mapper statement")
                    .bindSelected(
                        getter = { settings.rememberLastInputs },
                        setter = { settings.rememberLastInputs = it },
                    )
                contextHelp(
                    "Opt-in contract-driven retention keyed by canonical statement identity. " +
                        "Raw interpolation values are never retained.",
                )
            }
        }
    }
}
