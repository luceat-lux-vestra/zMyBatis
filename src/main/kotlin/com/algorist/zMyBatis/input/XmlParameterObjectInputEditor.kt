package com.algorist.zMyBatis.input

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Dual presentation for one authoritative XML parameter object.
 *
 * Fields mode is a convenience projection of source-proven dotted paths. JSON mode exposes the
 * complete object directly. Both produce one JSON object for the existing xml-parameter-object
 * contract; neither changes core caller authority.
 */
internal class XmlParameterObjectInputEditor(
    private val hints: List<XmlParameterObjectPathHint>,
) {
    private val pathFields = linkedMapOf<String, JBTextField>()
    private val jsonEditor = JBTextArea(8, 56).apply {
        lineWrap = false
        font = Font(
            Font.MONOSPACED,
            Font.PLAIN,
            EditorColorsManager.getInstance().globalScheme.editorFontSize,
        )
        border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
    }
    private val tabs = JTabbedPane()
    private var suppressTracking = false
    private var explicitlyEdited = false

    val primaryEditor: JBTextArea = jsonEditor

    val component: JComponent = tabs

    init {
        val fieldsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        }
        fieldsPanel.add(
            JBLabel("Enter every required value. Gray 'Example:' text is a hint and is not submitted.").apply {
                alignmentX = java.awt.Component.LEFT_ALIGNMENT
            },
        )
        fieldsPanel.add(Box.createVerticalStrut(8))
        hints.forEach { hint ->
            val field = JBTextField(36).apply {
                val placeholder = XmlParameterObjectInputSupport.scalarPlaceholder(hint)
                emptyText.text = placeholder
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                accessibleContext.accessibleName = hint.path
                accessibleContext.accessibleDescription =
                    "Required XML parameter path. $placeholder"
            }
            pathFields[hint.path] = field
            fieldsPanel.add(
                JPanel(BorderLayout(8, 0)).apply {
                    alignmentX = java.awt.Component.LEFT_ALIGNMENT
                    maximumSize = Dimension(Int.MAX_VALUE, 36)
                    add(JBLabel(hint.path).apply {
                        preferredSize = Dimension(180, 24)
                    }, BorderLayout.WEST)
                    add(field, BorderLayout.CENTER)
                },
            )
            fieldsPanel.add(Box.createVerticalStrut(4))
            field.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(event: DocumentEvent) = fieldEdited()
                override fun removeUpdate(event: DocumentEvent) = fieldEdited()
                override fun changedUpdate(event: DocumentEvent) = fieldEdited()
            })
        }

        jsonEditor.text = XmlParameterObjectInputSupport.template(hints)
        jsonEditor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = jsonEdited()
            override fun removeUpdate(event: DocumentEvent) = jsonEdited()
            override fun changedUpdate(event: DocumentEvent) = jsonEdited()
        })

        tabs.addTab(
            "Fields",
            JBScrollPane(fieldsPanel).apply {
                preferredSize = Dimension(580, (hints.size * 40 + 24).coerceIn(150, 360))
                border = BorderFactory.createEmptyBorder()
            },
        )
        tabs.addTab(
            "JSON",
            JBScrollPane(jsonEditor).apply {
                preferredSize = Dimension(580, 190)
                border = BorderFactory.createEmptyBorder()
            },
        )
        tabs.toolTipText =
            "Fields edits source-proven paths directly; JSON edits the complete XML parameter object."
    }

    fun textForSubmission(): String =
        if (tabs.selectedIndex == FIELDS_TAB) {
            XmlParameterObjectInputSupport.composeScalarValues(
                pathFields.mapValues { (_, field) -> field.text },
            )
        } else {
            jsonEditor.text
        }

    fun validationMessage(): String? {
        if (tabs.selectedIndex != FIELDS_TAB) return null
        val missing = pathFields
            .filterValues { it.text.isBlank() }
            .keys
            .toList()
        return if (missing.isEmpty()) {
            null
        } else {
            "Required XML values are missing: ${missing.joinToString()}. " +
                "Gray 'Example:' text is only a hint; enter a value or use the JSON tab."
        }
    }

    fun isExplicitlyEdited(): Boolean = explicitlyEdited

    fun loadRetainedJson(text: String) {
        suppressTracking = true
        try {
            jsonEditor.text = text
            pathFields.values.forEach { it.text = "" }
            tabs.selectedIndex = JSON_TAB
            explicitlyEdited = false
        } finally {
            suppressTracking = false
        }
    }

    fun clearValue() {
        suppressTracking = true
        try {
            pathFields.values.forEach { it.text = "" }
            jsonEditor.text = XmlParameterObjectInputSupport.template(hints)
            tabs.selectedIndex = FIELDS_TAB
            explicitlyEdited = false
        } finally {
            suppressTracking = false
        }
    }

    internal fun setPathValueForTest(path: String, value: String) {
        pathFields.getValue(path).text = value
    }

    internal fun selectJsonForTest(text: String) {
        tabs.selectedIndex = JSON_TAB
        jsonEditor.text = text
    }

    internal fun pathNamesForTest(): List<String> = pathFields.keys.toList()

    private fun fieldEdited() {
        if (suppressTracking) return
        explicitlyEdited = true
        suppressTracking = true
        try {
            jsonEditor.text = XmlParameterObjectInputSupport.composeScalarValues(
                pathFields.mapValues { (_, field) ->
                    field.text.ifBlank { "null" }
                },
            )
        } finally {
            suppressTracking = false
        }
    }

    private fun jsonEdited() {
        if (!suppressTracking) explicitlyEdited = true
    }

    private companion object {
        const val FIELDS_TAB = 0
        const val JSON_TAB = 1
    }
}
