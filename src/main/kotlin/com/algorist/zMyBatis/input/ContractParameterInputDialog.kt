package com.algorist.zMyBatis.input

import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputKind
import com.algorist.zMyBatis.core.input.InputRequiredness
import com.algorist.zMyBatis.core.input.InputRequirementId
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.settings.ZMyBatisSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.JTextComponent

/**
 * Swing renderer for the contract-driven input boundary.
 *
 * The dialog does not discover parameters, infer types from names, parse values itself, or turn
 * examples/history into executable inputs. It renders [ContractInputPresentation] and delegates
 * submission policy to [ContractInputSubmissionPolicy], then typed decoding/final environment
 * validation to [ContractInputAdapter].
 *
 * Production action cutover is deliberately owned by #66. Until that cutover, the legacy dialog
 * may remain on the legacy execution path; this class is the target UI boundary for that migration.
 */
@Suppress("MagicNumber", "LongMethod")
class ContractParameterInputDialog(
    project: Project,
    private val contract: ParameterContract,
) : DialogWrapper(project, true) {
    private data class FieldUi(
        val editor: JTextComponent,
        val component: JComponent,
        val optionalSupply: JBCheckBox?,
        val rawConfirmation: JBCheckBox?,
        val retainSelection: JBCheckBox?,
    )

    private val presentation = ContractInputPresentationFactory.create(contract)
    private val retentionEnabled = ZMyBatisSettings.getInstance().rememberLastInputs
    private val history = ContractInputHistoryService.getInstance(project)
    private val retainedDrafts = if (retentionEnabled && presentation.canSubmit) {
        history.load(contract).toMutableMap()
    } else {
        linkedMapOf()
    }
    private val editedRequirementIds = linkedSetOf<InputRequirementId>()
    private var suppressDocumentTracking = false
    private val acceptRetained = JBCheckBox("Use unchanged remembered values for this execution")
    private val clearRetained = JButton("Clear remembered values")
    private val fields = presentation.fields.associate { field -> field.requirementId to createFieldUi(field) }
    private var acceptedEnvironment: InputEnvironment? = null
    private var preparedRawValues: Map<InputRequirementId, String> = emptyMap()

    init {
        title = "Enter MyBatis Parameters"
        acceptRetained.isSelected = false
        clearRetained.addActionListener { clearRememberedValues() }
        init()
        isOKActionEnabled = presentation.canSubmit
    }

    override fun createCenterPanel(): JComponent {
        if (!presentation.canSubmit) return createBlockedPanel()

        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        }

        if (retainedDrafts.isNotEmpty()) {
            panel.add(
                JPanel(BorderLayout(8, 0)).apply {
                    alignmentX = java.awt.Component.LEFT_ALIGNMENT
                    maximumSize = Dimension(Int.MAX_VALUE, 36)
                    add(acceptRetained, BorderLayout.CENTER)
                    add(clearRetained, BorderLayout.EAST)
                },
            )
            panel.add(Box.createVerticalStrut(8))
        }

        presentation.fields.forEach { field ->
            val ui = fields.getValue(field.requirementId)
            val requiredMarker = if (field.requiredness == InputRequiredness.REQUIRED) " *" else ""
            val kindMarker = if (field.inputKind == InputKind.RAW_INTERPOLATION) " — raw interpolation" else ""
            panel.add(
                JBLabel("${displayName(field.requirementId)}$requiredMarker$kindMarker").apply {
                    toolTipText = provenanceText(field)
                    alignmentX = java.awt.Component.LEFT_ALIGNMENT
                },
            )
            panel.add(Box.createVerticalStrut(2))
            panel.add(ui.component)

            ui.optionalSupply?.let {
                panel.add(Box.createVerticalStrut(2))
                panel.add(it.apply { alignmentX = java.awt.Component.LEFT_ALIGNMENT })
            }
            ui.rawConfirmation?.let {
                panel.add(Box.createVerticalStrut(2))
                panel.add(it.apply { alignmentX = java.awt.Component.LEFT_ALIGNMENT })
            }
            ui.retainSelection?.let {
                panel.add(Box.createVerticalStrut(2))
                panel.add(it.apply { alignmentX = java.awt.Component.LEFT_ALIGNMENT })
            }
            panel.add(Box.createVerticalStrut(10))
        }

        panel.add(
            JBLabel("* Required. Placeholder examples are not submitted as input.").apply {
                alignmentX = java.awt.Component.LEFT_ALIGNMENT
            },
        )

        return JBScrollPane(panel).apply {
            preferredSize = Dimension(620, (presentation.fields.size * 126 + 80).coerceIn(220, 680))
            border = BorderFactory.createEmptyBorder()
        }
    }

    override fun doValidate(): ValidationInfo? = when (val prepared = prepareCurrentInput()) {
        is ContractInputAdapterResult.Success -> null
        is ContractInputAdapterResult.Failure -> validationInfo(prepared.failures.first())
    }

    override fun doOKAction() {
        when (val prepared = prepareCurrentInput()) {
            is ContractInputAdapterResult.Success -> {
                acceptedEnvironment = prepared.environment
                if (retentionEnabled) {
                    val explicitlyRetainedIds = fields
                        .filterValues { it.retainSelection?.isSelected == true }
                        .keys
                    history.save(
                        contract = contract,
                        rawValues = preparedRawValues,
                        explicitlyRetainedIds = explicitlyRetainedIds,
                    )
                }
                super.doOKAction()
            }
            is ContractInputAdapterResult.Failure -> acceptedEnvironment = null
        }
    }

    override fun doCancelAction() {
        acceptedEnvironment = null
        preparedRawValues = emptyMap()
        super.doCancelAction()
    }

    /** Returns the exact validated environment only after successful dialog acceptance. */
    fun inputEnvironment(): InputEnvironment = requireNotNull(acceptedEnvironment) {
        "input environment is available only after successful dialog acceptance"
    }

    private fun createFieldUi(field: ContractInputField): FieldUi {
        val rendered = ContractInputSwingRenderer.create(field)
        val editor = rendered.editor
        retainedDrafts[field.requirementId]?.let { retained -> editor.text = retained }

        val optionalSupply = if (field.requiredness == InputRequiredness.OPTIONAL) {
            JBCheckBox("Supply this optional input", false)
        } else {
            null
        }
        val rawConfirmation = if (field.requiresExplicitRawConfirmation) {
            JBCheckBox("I understand this value is inserted as raw SQL text", false)
        } else {
            null
        }
        val retainSelection = if (
            retentionEnabled && field.retentionPolicy == ContractInputRetentionPolicy.RETAINABLE
        ) {
            JBCheckBox(
                "Remember this value in this project workspace (plain text)",
                field.requirementId in retainedDrafts,
            ).apply {
                toolTipText =
                    "Input text may be sensitive. If selected, it is stored as plain text in this project's workspace metadata."
            }
        } else {
            null
        }

        editor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = markEdited(field.requirementId, optionalSupply)
            override fun removeUpdate(event: DocumentEvent) = markEdited(field.requirementId, optionalSupply)
            override fun changedUpdate(event: DocumentEvent) = markEdited(field.requirementId, optionalSupply)
        })

        return FieldUi(editor, rendered.component, optionalSupply, rawConfirmation, retainSelection)
    }

    private fun markEdited(requirementId: InputRequirementId, optionalSupply: JBCheckBox?) {
        if (suppressDocumentTracking) return
        editedRequirementIds += requirementId
        optionalSupply?.isSelected = true
    }

    private fun prepareCurrentInput(): ContractInputAdapterResult {
        val drafts = presentation.fields.map { field ->
            val ui = fields.getValue(field.requirementId)
            ContractInputDraft(
                requirementId = field.requirementId,
                text = ui.editor.text,
                supplied = field.requiredness == InputRequiredness.REQUIRED || ui.optionalSupply?.isSelected == true,
                edited = field.requirementId in editedRequirementIds,
                retained = field.requirementId in retainedDrafts,
                rawConfirmed = ui.rawConfirmation?.isSelected == true,
            )
        }
        val result = ContractInputSubmissionPolicy.prepare(
            contract = contract,
            drafts = drafts,
            acceptRetained = acceptRetained.isSelected,
        )
        preparedRawValues = if (result is ContractInputAdapterResult.Success) {
            drafts.filter { it.supplied }.associate { it.requirementId to it.text }
        } else {
            emptyMap()
        }
        return result
    }

    private fun validationInfo(failure: ContractInputAdapterFailure): ValidationInfo {
        val requirementId = when (failure) {
            is ContractInputAdapterFailure.Codec -> failure.requirementId
            is ContractInputAdapterFailure.Environment -> failure.failure.requirementId
            is ContractInputAdapterFailure.Presentation -> failure.problem.requirementId
        }
        val message = when (failure) {
            is ContractInputAdapterFailure.Codec ->
                "${displayName(failure.requirementId)}: ${humanize(failure.failure.kind.name)}"
            is ContractInputAdapterFailure.Environment ->
                "${requirementId?.let(::displayName) ?: "Input contract"}: ${humanize(failure.failure.kind.name)}"
            is ContractInputAdapterFailure.Presentation ->
                "${requirementId?.let(::displayName) ?: "Input contract"}: ${humanize(failure.problem.code)}"
        }
        return ValidationInfo(message, requirementId?.let { fields[it]?.component })
    }

    private fun createBlockedPanel(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        add(JBLabel("This parameter contract cannot be edited safely:"))
        presentation.problems.forEach { problem ->
            add(Box.createVerticalStrut(4))
            add(JBLabel("• ${humanize(problem.code)}"))
        }
        preferredSize = Dimension(560, (presentation.problems.size * 30 + 70).coerceAtLeast(160))
    }

    private fun clearRememberedValues() {
        history.clear(contract.statementId)
        suppressDocumentTracking = true
        try {
            retainedDrafts.keys.toList().forEach { id ->
                if (id !in editedRequirementIds) {
                    fields[id]?.editor?.text = ""
                    fields[id]?.optionalSupply?.isSelected = false
                }
                fields[id]?.retainSelection?.isSelected = false
            }
            retainedDrafts.clear()
            acceptRetained.isSelected = false
            acceptRetained.isEnabled = false
            clearRetained.isEnabled = false
        } finally {
            suppressDocumentTracking = false
        }
    }

    private fun displayName(requirementId: InputRequirementId): String {
        val aliases = contract.aliases.filter { it.requirementId == requirementId }.map { it.name }.distinct()
        return when (aliases.size) {
            0 -> requirementId.value
            1 -> aliases.single()
            else -> aliases.joinToString(prefix = "[", postfix = "]")
        }
    }

    private fun provenanceText(field: ContractInputField): String = field.provenance.evidence.joinToString("; ") { evidence ->
        when (evidence) {
            is InputEvidence.MapperMethodParameter -> buildString {
                append("mapper parameter #${evidence.index}")
                evidence.sourceName?.let { append(" ($it)") }
                append(": ${evidence.typeIdentity.value}")
            }
            is InputEvidence.ExplicitParamAlias -> "@Param(\"${evidence.alias}\")"
            is InputEvidence.GeneratedAlias -> "generated alias ${evidence.alias} (${evidence.ruleId})"
            is InputEvidence.Placeholder -> "${evidence.kind}: ${evidence.expression}"
            is InputEvidence.OgnlExpression -> "OGNL: ${evidence.expression}"
            is InputEvidence.ForeachCollection -> "foreach collection: ${evidence.expression}"
            is InputEvidence.ForeachLocal -> "foreach ${evidence.role}: ${evidence.name}"
            is InputEvidence.BindLocal -> "bind ${evidence.name}: ${evidence.expression}"
        }
    }

    private fun humanize(value: String): String = value
        .replace('-', ' ')
        .replace('_', ' ')
        .lowercase()
}
