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

        group("Execution & Output") {
            row {
                checkBox("Show SQL preview before execution")
                    .bindSelected(
                        getter = { settings.sqlPreview },
                        setter = { settings.sqlPreview = it }
                    )
                contextHelp("When enabled, the resolved SQL is shown in a preview\ndialog before being sent to the database console.")
            }
            row {
                checkBox("Auto-format resolved SQL")
                    .bindSelected(
                        getter = { settings.autoFormatSql },
                        setter = { settings.autoFormatSql = it }
                    )
                contextHelp("Reformats the resolved SQL with keyword-aligned line breaks\nbefore execution or preview.")
            }
            row {
                checkBox("Copy resolved SQL to clipboard after execution")
                    .bindSelected(
                        getter = { settings.copyToClipboard },
                        setter = { settings.copyToClipboard = it }
                    )
                contextHelp("Automatically copies the resolved SQL text to the clipboard\nafter execution so you can paste it elsewhere.")
            }
            row {
                label("Console session:")
            }
            row {
                comboBox(ConsoleSessionPolicy.entries)
                    .bindItem(
                        getter = { settings.consoleSessionPolicy },
                        setter = { settings.consoleSessionPolicy = it ?: ConsoleSessionPolicy.REUSE }
                    )
                contextHelp(
                    "REUSE     — reuse the existing console for the same mapper file.\n" +
                    "NEW_EACH  — always open a brand-new console tab for every execution."
                )
            }
        }

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
                    "NULL         → treats blank input as null during zMyBatis evaluation\n" +
                    "EMPTY_STRING → treats blank input as an empty string during zMyBatis evaluation"
                )
            }
        }

        group("Parsing Engine") {
            row {
                checkBox("Strict OGNL mode")
                    .bindSelected(
                        getter = { settings.strictOgnlMode },
                        setter = { settings.strictOgnlMode = it }
                    )
                contextHelp(
                    "ON  — OGNL evaluation errors are propagated immediately.\n" +
                    "      Use this to catch type mismatches or typos in test= expressions.\n" +
                    "OFF — (default) OGNL evaluation errors are converted to the current\n" +
                    "      zMyBatis plugin-error text representation instead of being propagated."
                )
            }
            row {
                checkBox("Ignore unknown tags")
                    .bindSelected(
                        getter = { settings.ignoreUnknownTags },
                        setter = { settings.ignoreUnknownTags = it }
                    )
                contextHelp(
                    "ON  — Unrecognised XML elements (e.g. custom / 3rd-party tags) are\n" +
                    "      stripped before parsing; their text content is preserved.\n" +
                    "OFF — (default) An unknown tag causes a BuilderException, making the\n" +
                    "      problem visible immediately."
                )
            }
        }
    }
}
