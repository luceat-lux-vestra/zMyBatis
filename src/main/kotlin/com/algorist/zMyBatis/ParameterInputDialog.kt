package com.algorist.zMyBatis

import com.google.gson.JsonSyntaxException
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator

/**
 * Parameter input dialog — **single unified panel** (no tabs).
 *
 * All parameters are shown together in one scrollable view so the user
 * can fill every value at once without switching between tabs:
 *
 *  - **Scalar params** (`#{id}`, `#{status}`) → one-line text field
 *  - **Object params** (`#{user.name}`, `test="user.id == 1"`) → multi-line JSON text area
 */
@Suppress("MagicNumber")
class ParameterInputDialog(
    project: Project,
    paramNames: List<String>,
    /** Root parameter names accessed via dot-notation. */
    private val objectParams: Set<String> = emptySet()
) : DialogWrapper(project, true) {

    companion object {
        private const val FIELD_COLUMNS   = 30
        private const val JSON_FIELD_ROWS = 3
        private const val JSON_FIELD_COLS = 52
    }

    private val scalarParamNames = paramNames.filter { it !in objectParams }
    private val objectParamNames = paramNames.filter { it in objectParams }

    // ── Scalar: one JBTextField per scalar parameter ────────────────────────
    private val simpleFields: Map<String, JBTextField> = scalarParamNames.associateWith {
        JBTextField(FIELD_COLUMNS).apply {
            emptyText.text = "null  |  42  |  text  |  [1,2,3]"
        }
    }

    // ── Object: one JBTextArea per object parameter ─────────────────────────
    private val jsonFields: Map<String, JBTextArea> = objectParamNames.associateWith { name ->
        JBTextArea(JSON_FIELD_ROWS, JSON_FIELD_COLS).apply {
            lineWrap = false
            emptyText.text = jsonPlaceholder(name)
            font = Font(
                Font.MONOSPACED, Font.PLAIN,
                EditorColorsManager.getInstance().globalScheme.editorFontSize
            )
            border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
        }
    }

    init {
        title = "Enter MyBatis Parameters"
        init()
    }

    // ── UI construction ───────────────────────────────────────────────────────

    @Suppress("CyclomaticComplexMethod")
    override fun createCenterPanel(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        }

        // ── Scalar parameters ───────────────────────────────────────────────
        for (name in scalarParamNames) {
            val field = simpleFields[name] ?: continue
            val row = JPanel(BorderLayout(8, 0)).apply {
                maximumSize = Dimension(Int.MAX_VALUE, 36)
                alignmentX = java.awt.Component.LEFT_ALIGNMENT
                add(JBLabel("$name:").apply { preferredSize = Dimension(120, 24) }, BorderLayout.WEST)
                add(field, BorderLayout.CENTER)
            }
            panel.add(row)
            panel.add(javax.swing.Box.createVerticalStrut(4))
        }

        // ── Separator + Object parameters ───────────────────────────────────
        if (objectParamNames.isNotEmpty()) {
            if (scalarParamNames.isNotEmpty()) {
                panel.add(javax.swing.Box.createVerticalStrut(8))
                panel.add(JSeparator().apply {
                    maximumSize = Dimension(Int.MAX_VALUE, 2)
                    alignmentX = java.awt.Component.LEFT_ALIGNMENT
                })
                panel.add(javax.swing.Box.createVerticalStrut(4))
            }
            panel.add(JBLabel("<html><b>Object / Array parameters</b> — enter JSON values</html>").apply {
                alignmentX = java.awt.Component.LEFT_ALIGNMENT
                border = BorderFactory.createEmptyBorder(0, 0, 4, 0)
            })

            for (name in objectParamNames) {
                val area = jsonFields[name] ?: continue
                val nameLabel = JBLabel("$name:").apply {
                    foreground = JBColor.foreground()
                    border = BorderFactory.createEmptyBorder(4, 0, 2, 0)
                    alignmentX = java.awt.Component.LEFT_ALIGNMENT
                }
                val scroll = JBScrollPane(area).apply {
                    preferredSize = Dimension(500, 70)
                    maximumSize  = Dimension(Int.MAX_VALUE, 70)
                    alignmentX   = java.awt.Component.LEFT_ALIGNMENT
                }
                panel.add(nameLabel)
                panel.add(scroll)
            }
        }

        val totalRows = scalarParamNames.size + objectParamNames.size
        return JBScrollPane(panel).apply {
            preferredSize = Dimension(560, (totalRows * 80 + 40).coerceIn(160, 500))
            border = BorderFactory.createEmptyBorder()
        }
    }

    // ── Validation ────────────────────────────────────────────────────────────

    override fun doValidate(): ValidationInfo? {
        for (name in objectParamNames) {
            val area = jsonFields[name] ?: continue
            val text = area.text.trim()
            if (text.isEmpty()) continue
            try {
                JsonParameterParser.parseValue(text)
            } catch (e: JsonSyntaxException) {
                return ValidationInfo("'$name' — invalid JSON: ${e.message}", area)
            }
        }
        return null
    }

    // ── Value retrieval ───────────────────────────────────────────────────────

    /**
     * Returns the parameter map collecting all visible fields:
     *  - Scalar params → parsed via [parseSimpleValue]
     *  - Object params → parsed via [JsonParameterParser.parseValue] as nested Maps
     */
    fun getValues(): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()

        for (name in scalarParamNames) {
            val text = simpleFields[name]?.text?.trim() ?: continue
            if (text.isEmpty()) continue
            result[name] = parseSimpleValue(text)
        }

        for (name in objectParamNames) {
            val text = jsonFields[name]?.text?.trim() ?: continue
            if (text.isEmpty()) continue
            result[name] = JsonParameterParser.parseValue(text)
        }

        return result
    }

    // ── Simple value parser ───────────────────────────────────────────────────

    @Suppress("CyclomaticComplexMethod")
    private fun parseSimpleValue(raw: String): Any? {
        val v = raw.trim()
        return when {
            v.equals("null",  ignoreCase = true) -> null
            v.equals("true",  ignoreCase = true) -> true
            v.equals("false", ignoreCase = true) -> false
            v.startsWith("[") && v.endsWith("]") -> parseSimpleList(v)
            v.length >= 2 && v.startsWith("'") && v.endsWith("'") -> v.substring(1, v.length - 1)
            v.length >= 2 && v.startsWith("\"") && v.endsWith("\"") -> v.substring(1, v.length - 1)
            else -> v.toLongOrNull() ?: v.toDoubleOrNull() ?: v
        }
    }

    private fun parseSimpleList(raw: String): List<Any?> {
        val inner = raw.substring(1, raw.length - 1)
        return inner.split(",").map { parseSimpleValue(it.trim()) }
    }

    // ── Placeholder helpers ───────────────────────────────────────────────────

    private fun jsonPlaceholder(name: String): String = when {
        name.endsWith("s", ignoreCase = true) &&
        !name.endsWith("status", ignoreCase = true) &&
        !name.endsWith("address", ignoreCase = true) ->
            """[1, 2, 3]  or  [{"id": 1, "name": "A"}, ...]"""
        name.contains("list", ignoreCase = true) ||
        name.contains("ids",  ignoreCase = true) ->
            """[1, 2, 3]"""
        else ->
            """{"id": 1, "name": "Alice"}"""
    }
}
