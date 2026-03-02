package com.algorist.zMyBatis

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Read-only preview dialog that shows the resolved Native SQL before execution.
 *
 * The user can:
 *  - Click **Execute** (OK) → proceed to run the SQL on the console
 *  - Click **Cancel**       → abort execution
 */
@Suppress("MagicNumber")
class SqlPreviewDialog(
    project: Project,
    private val sql: String
) : DialogWrapper(project, true) {

    init {
        title = "zMyBatis — SQL Preview"
        setOKButtonText("Execute")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val fontSize = EditorColorsManager.getInstance().globalScheme.editorFontSize
        val textArea = JBTextArea(sql).apply {
            isEditable = false
            lineWrap = false
            font = Font(Font.MONOSPACED, Font.PLAIN, fontSize)
            border = BorderFactory.createEmptyBorder(4, 6, 4, 6)
        }

        val scroll = JBScrollPane(textArea).apply {
            preferredSize = Dimension(720, 380)
            border = BorderFactory.createEmptyBorder()
        }

        val panel = JPanel(BorderLayout(0, 6)).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 4, 8)
            add(
                JBLabel("<html><b>Resolved SQL</b> — review before executing on the database</html>").apply {
                    border = BorderFactory.createEmptyBorder(0, 2, 4, 0)
                },
                BorderLayout.NORTH
            )
            add(scroll, BorderLayout.CENTER)
        }
        return panel
    }
}

