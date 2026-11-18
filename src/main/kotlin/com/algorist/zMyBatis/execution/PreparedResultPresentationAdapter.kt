@file:Suppress("DialogTitleCapitalization")

package com.algorist.zMyBatis.execution

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.Action
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.AbstractTableModel

/**
 * Thin IDE boundary for an already-completed prepared execution.
 *
 * The adapter receives only a presentation snapshot. It cannot execute or re-execute SQL and it
 * schedules all UI work onto the EDT after checking the current project/invocation lifecycle.
 */
internal class PreparedResultPresentationAdapter(
    private val project: Project,
    private val ui: PreparedResultUi = SwingPreparedResultUi(project),
) {
    fun present(
        outcome: DatabaseSessionPreparedOutcome,
        isInvocationActive: () -> Boolean = { true },
        onFinished: () -> Unit = {},
    ) {
        val presentation = preparedResultPresentation(outcome)
        ApplicationManager.getApplication().invokeLater(
            {
                if (project.isDisposed || !isInvocationActive()) {
                    onFinished()
                    return@invokeLater
                }

                try {
                    when (presentation) {
                        is PreparedResultPresentation.Query ->
                            ui.showQuery(presentation)

                        is PreparedResultPresentation.Update ->
                            ui.showInfo(
                                title = "zMyBatis: Update Complete",
                                message = "${presentation.updateCount} row(s) affected.",
                            )

                        PreparedResultPresentation.Cancelled ->
                            ui.showInfo(
                                title = "zMyBatis: Execution Cancelled",
                                message = "The prepared execution was cancelled.",
                            )

                        is PreparedResultPresentation.Failure ->
                            ui.showError(
                                title = presentation.title,
                                message = "${presentation.message}\n\nDiagnostic: ${presentation.diagnosticCode}",
                            )
                    }
                } finally {
                    onFinished()
                }
            },
            ModalityState.any(),
        )
    }
}

internal interface PreparedResultUi {
    fun showQuery(presentation: PreparedResultPresentation.Query)

    fun showInfo(title: String, message: String)

    fun showError(title: String, message: String)
}

private class SwingPreparedResultUi(
    private val project: Project,
) : PreparedResultUi {
    override fun showQuery(presentation: PreparedResultPresentation.Query) {
        PreparedQueryResultDialog(project, presentation).show()
    }

    override fun showInfo(title: String, message: String) {
        Messages.showInfoMessage(project, message, title)
    }

    override fun showError(title: String, message: String) {
        Messages.showErrorDialog(project, message, title)
    }
}

@Suppress("MagicNumber")
private class PreparedQueryResultDialog(
    project: Project,
    private val presentation: PreparedResultPresentation.Query,
) : DialogWrapper(project, true) {

    init {
        title = "zMyBatis: Query Result"
        setOKButtonText("Close")
        init()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent {
        val table = JBTable(PreparedQueryTableModel(presentation)).apply {
            autoResizeMode = JTable.AUTO_RESIZE_OFF
            fillsViewportHeight = true
            setShowGrid(true)
        }

        val scroll = JBScrollPane(table).apply {
            preferredSize = Dimension(840, 420)
            border = BorderFactory.createEmptyBorder()
        }

        return JPanel(BorderLayout(0, 6)).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 4, 8)
            add(
                JBLabel(
                    "Prepared query result — ${presentation.rows.size} row(s), " +
                        "${presentation.columns.size} column(s)",
                ),
                BorderLayout.NORTH,
            )
            add(scroll, BorderLayout.CENTER)
        }
    }
}

private class PreparedQueryTableModel(
    private val presentation: PreparedResultPresentation.Query,
) : AbstractTableModel() {
    override fun getRowCount(): Int = presentation.rows.size

    override fun getColumnCount(): Int = presentation.columns.size

    override fun getColumnName(column: Int): String {
        val definition = presentation.columns[column]
        return definition.jdbcTypeName?.let { jdbcTypeName ->
            "${definition.label} [$jdbcTypeName]"
        } ?: definition.label
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? =
        presentation.rows[rowIndex][columnIndex]

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
}
