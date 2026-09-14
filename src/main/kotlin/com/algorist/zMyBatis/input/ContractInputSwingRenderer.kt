package com.algorist.zMyBatis.input

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.text.JTextComponent

internal data class ContractInputRenderedEditor(
    val editor: JTextComponent,
    val component: JComponent,
)

/**
 * Mechanical Swing rendering only. Semantic editor selection is already fixed in
 * [ContractInputField.editorKind] by the contract presentation adapter.
 */
internal object ContractInputSwingRenderer {
    private const val MULTILINE_ROWS = 4
    private const val MULTILINE_COLUMNS = 56
    private const val SINGLE_LINE_COLUMNS = 44
    private const val MULTILINE_HEIGHT = 88

    fun create(field: ContractInputField): ContractInputRenderedEditor {
        val editor = createEditor(field)
        val component = when (editor) {
            is JTextArea -> JBScrollPane(editor).apply {
                preferredSize = Dimension(560, MULTILINE_HEIGHT)
                maximumSize = Dimension(Int.MAX_VALUE, MULTILINE_HEIGHT)
                alignmentX = java.awt.Component.LEFT_ALIGNMENT
            }
            is JTextField -> editor.apply {
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                alignmentX = java.awt.Component.LEFT_ALIGNMENT
            }
            else -> error("unsupported contract input editor component")
        }
        return ContractInputRenderedEditor(editor, component)
    }

    private fun createEditor(field: ContractInputField): JTextComponent {
        val multiline = field.editorKind in setOf(
            ContractInputEditorKind.JSON_OBJECT,
            ContractInputEditorKind.JSON_LIST,
            ContractInputEditorKind.JSON_ARRAY,
            ContractInputEditorKind.JSON_MAP,
            ContractInputEditorKind.RAW_TEXT,
        )
        return if (multiline) {
            JBTextArea(MULTILINE_ROWS, MULTILINE_COLUMNS).apply {
                lineWrap = false
                emptyText.text = field.exampleText ?: if (field.editorKind == ContractInputEditorKind.RAW_TEXT) {
                    "raw SQL text — explicit confirmation required"
                } else {
                    ""
                }
                font = Font(
                    Font.MONOSPACED,
                    Font.PLAIN,
                    EditorColorsManager.getInstance().globalScheme.editorFontSize,
                )
                border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
            }
        } else {
            JBTextField(SINGLE_LINE_COLUMNS).apply {
                emptyText.text = field.exampleText.orEmpty()
            }
        }
    }
}
