package com.algorist.zMyBatis.input

import com.algorist.zMyBatis.core.input.ExpectedInputType
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputKind
import com.algorist.zMyBatis.core.input.InputProvenance
import com.algorist.zMyBatis.core.input.InputRequiredness
import com.algorist.zMyBatis.core.input.InputRequirementId
import com.algorist.zMyBatis.core.input.InputScalarType
import com.algorist.zMyBatis.core.input.InputShape
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.input.SourceEvidence
import com.algorist.zMyBatis.core.source.JavaStatementId
import com.algorist.zMyBatis.core.source.JavaTypeIdentity
import com.algorist.zMyBatis.core.source.MethodSignature
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.settings.ZMyBatisSettings
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField

class ContractInputSwingRendererProjectFixtureTest : BasePlatformTestCase() {
    fun testBoundScalarEditorsRenderAsSingleLineJetBrainsFields() {
        val text = ContractInputSwingRenderer.create(field(ContractInputEditorKind.TEXT))
        val integer = ContractInputSwingRenderer.create(field(ContractInputEditorKind.INTEGER))
        val date = ContractInputSwingRenderer.create(field(ContractInputEditorKind.DATE))

        listOf(text, integer, date).forEach { rendered ->
            assertTrue(rendered.editor is JBTextField)
            assertSame(rendered.editor, rendered.component)
        }
    }

    fun testStructuredContractEditorsRenderAsMultilineScrollPanes() {
        val structuredKinds = listOf(
            ContractInputEditorKind.JSON_OBJECT,
            ContractInputEditorKind.JSON_LIST,
            ContractInputEditorKind.JSON_ARRAY,
            ContractInputEditorKind.JSON_MAP,
        )

        structuredKinds.forEach { kind ->
            val rendered = ContractInputSwingRenderer.create(field(kind))

            assertTrue("$kind must use a multiline editor", rendered.editor is JBTextArea)
            assertTrue("$kind must be wrapped in a scroll pane", rendered.component is JBScrollPane)
            val scrollPane = rendered.component as JBScrollPane
            assertSame(rendered.editor, scrollPane.viewport.view)
        }
    }

    fun testRawInterpolationRendersDistinctMultilineEditorWithoutBecomingBoundInput() {
        val field = field(ContractInputEditorKind.RAW_TEXT)
        val rendered = ContractInputSwingRenderer.create(field)

        assertEquals(InputKind.RAW_INTERPOLATION, field.inputKind)
        assertTrue(field.requiresExplicitRawConfirmation)
        assertEquals(ContractInputRetentionPolicy.NEVER_RETAIN, field.retentionPolicy)
        assertTrue(rendered.editor is JBTextArea)
        assertTrue(rendered.component is JBScrollPane)
        assertSame(rendered.editor, (rendered.component as JBScrollPane).viewport.view)
    }

    fun testDialogDoesNotPublishEnvironmentBeforeExplicitAcceptance() {
        val settings = ZMyBatisSettings.getInstance()
        val originalRetention = settings.rememberLastInputs
        settings.rememberLastInputs = false
        val contract = ParameterContract(
            statementId = JavaStatementId(
                sourceFileId = FILE,
                qualifiedMapperType = "fixture.Mapper",
                methodSignature = MethodSignature(
                    name = "find",
                    parameterTypeIdentities = listOf(JavaTypeIdentity("java.lang.String")),
                ),
            ),
            requirements = emptyList(),
            aliases = emptyList(),
            internalBindings = emptyList(),
            blockingProblems = emptyList(),
            sourceRevisions = mapOf(FILE to REVISION),
        )
        val dialog = ContractParameterInputDialog(project, contract)

        try {
            try {
                dialog.inputEnvironment()
                fail("dialog must not expose an InputEnvironment before explicit OK acceptance")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("successful dialog acceptance"))
            }
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
            settings.rememberLastInputs = originalRetention
        }
    }

    private fun field(editorKind: ContractInputEditorKind): ContractInputField {
        val raw = editorKind == ContractInputEditorKind.RAW_TEXT
        val inputKind = if (raw) InputKind.RAW_INTERPOLATION else InputKind.BOUND
        val expectedType = when (editorKind) {
            ContractInputEditorKind.TEXT -> ExpectedInputType(InputShape.SCALAR, InputScalarType.STRING)
            ContractInputEditorKind.BOOLEAN -> ExpectedInputType(InputShape.SCALAR, InputScalarType.BOOLEAN)
            ContractInputEditorKind.INTEGER -> ExpectedInputType(InputShape.SCALAR, InputScalarType.INTEGER)
            ContractInputEditorKind.DECIMAL -> ExpectedInputType(InputShape.SCALAR, InputScalarType.DECIMAL)
            ContractInputEditorKind.UUID -> ExpectedInputType(InputShape.SCALAR, InputScalarType.UUID)
            ContractInputEditorKind.DATE -> ExpectedInputType(InputShape.TEMPORAL, InputScalarType.DATE)
            ContractInputEditorKind.TIME -> ExpectedInputType(InputShape.TEMPORAL, InputScalarType.TIME)
            ContractInputEditorKind.DATE_TIME -> ExpectedInputType(InputShape.TEMPORAL, InputScalarType.DATE_TIME)
            ContractInputEditorKind.INSTANT -> ExpectedInputType(InputShape.TEMPORAL, InputScalarType.INSTANT)
            ContractInputEditorKind.JSON_OBJECT -> ExpectedInputType(InputShape.OBJECT)
            ContractInputEditorKind.JSON_LIST -> ExpectedInputType(InputShape.LIST)
            ContractInputEditorKind.JSON_ARRAY -> ExpectedInputType(InputShape.ARRAY)
            ContractInputEditorKind.JSON_MAP -> ExpectedInputType(InputShape.MAP)
            ContractInputEditorKind.RAW_TEXT -> ExpectedInputType(InputShape.RAW_TEXT, InputScalarType.STRING)
        }
        val requirementId = InputRequirementId("fixture-${editorKind.name.lowercase()}")
        val provenance = InputProvenance(
            listOf(
                InputEvidence.Placeholder(
                    kind = inputKind,
                    expression = requirementId.value,
                    source = SourceEvidence(FILE, REVISION, null),
                ),
            ),
        )

        return ContractInputField(
            requirementId = requirementId,
            inputKind = inputKind,
            requiredness = InputRequiredness.REQUIRED,
            expectedType = expectedType,
            editorKind = editorKind,
            provenance = provenance,
            retentionPolicy = if (raw) {
                ContractInputRetentionPolicy.NEVER_RETAIN
            } else {
                ContractInputRetentionPolicy.RETAINABLE
            },
            requiresExplicitRawConfirmation = raw,
            exampleText = if (raw) null else "fixture example",
        )
    }

    private companion object {
        val FILE = SourceFileId("fixture://contract-input-renderer")
        val REVISION = SourceRevision("fixture-revision")
    }
}
