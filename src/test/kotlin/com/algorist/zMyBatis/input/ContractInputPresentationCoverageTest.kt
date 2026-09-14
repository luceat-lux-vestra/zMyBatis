package com.algorist.zMyBatis.input

import com.algorist.zMyBatis.core.input.ExpectedInputType
import com.algorist.zMyBatis.core.input.InputContractProblem
import com.algorist.zMyBatis.core.input.InputContractProblemKind
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputKind
import com.algorist.zMyBatis.core.input.InputProvenance
import com.algorist.zMyBatis.core.input.InputRequiredness
import com.algorist.zMyBatis.core.input.InputRequirement
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractInputPresentationCoverageTest {
    @Test
    fun `presentation covers every maintained scalar temporal structured and raw editor shape`() {
        val requirements = listOf(
            requirement("text", InputKind.BOUND, ExpectedInputType(InputShape.SCALAR, InputScalarType.STRING))
                to ContractInputEditorKind.TEXT,
            requirement("boolean", InputKind.BOUND, ExpectedInputType(InputShape.SCALAR, InputScalarType.BOOLEAN))
                to ContractInputEditorKind.BOOLEAN,
            requirement("integer", InputKind.BOUND, ExpectedInputType(InputShape.SCALAR, InputScalarType.INTEGER))
                to ContractInputEditorKind.INTEGER,
            requirement("decimal", InputKind.BOUND, ExpectedInputType(InputShape.SCALAR, InputScalarType.DECIMAL))
                to ContractInputEditorKind.DECIMAL,
            requirement("uuid", InputKind.BOUND, ExpectedInputType(InputShape.SCALAR, InputScalarType.UUID))
                to ContractInputEditorKind.UUID,
            requirement("date", InputKind.BOUND, ExpectedInputType(InputShape.TEMPORAL, InputScalarType.DATE))
                to ContractInputEditorKind.DATE,
            requirement("time", InputKind.BOUND, ExpectedInputType(InputShape.TEMPORAL, InputScalarType.TIME))
                to ContractInputEditorKind.TIME,
            requirement("dateTime", InputKind.BOUND, ExpectedInputType(InputShape.TEMPORAL, InputScalarType.DATE_TIME))
                to ContractInputEditorKind.DATE_TIME,
            requirement("instant", InputKind.BOUND, ExpectedInputType(InputShape.TEMPORAL, InputScalarType.INSTANT))
                to ContractInputEditorKind.INSTANT,
            requirement("object", InputKind.BOUND, ExpectedInputType(InputShape.OBJECT))
                to ContractInputEditorKind.JSON_OBJECT,
            requirement("list", InputKind.BOUND, ExpectedInputType(InputShape.LIST))
                to ContractInputEditorKind.JSON_LIST,
            requirement("array", InputKind.BOUND, ExpectedInputType(InputShape.ARRAY))
                to ContractInputEditorKind.JSON_ARRAY,
            requirement("map", InputKind.BOUND, ExpectedInputType(InputShape.MAP))
                to ContractInputEditorKind.JSON_MAP,
            requirement(
                "raw",
                InputKind.RAW_INTERPOLATION,
                ExpectedInputType(InputShape.RAW_TEXT, InputScalarType.STRING),
            ) to ContractInputEditorKind.RAW_TEXT,
        )
        val contract = contract(requirements.map { it.first })

        val presentation = ContractInputPresentationFactory.create(contract)

        assertTrue(presentation.canSubmit)
        assertEquals(requirements.size, presentation.fields.size)
        requirements.forEachIndexed { index, (requirement, expectedEditor) ->
            val field = presentation.fields[index]
            assertEquals(requirement.id, field.requirementId)
            assertEquals(requirement.kind, field.inputKind)
            assertEquals(requirement.expectedType, field.expectedType)
            assertEquals(requirement.provenance, field.provenance)
            assertEquals(expectedEditor, field.editorKind)
        }

        val rawField = presentation.fields.single { it.inputKind == InputKind.RAW_INTERPOLATION }
        assertEquals(ContractInputRetentionPolicy.NEVER_RETAIN, rawField.retentionPolicy)
        assertTrue(rawField.requiresExplicitRawConfirmation)
        assertNull(rawField.exampleText)

        presentation.fields.filter { it.inputKind == InputKind.BOUND }.forEach { field ->
            assertEquals(ContractInputRetentionPolicy.RETAINABLE, field.retentionPolicy)
            assertFalse(field.requiresExplicitRawConfirmation)
        }
    }

    @Test
    fun `unknown ambiguous and unsupported blockers never become editable fields`() {
        val mappings = listOf(
            InputContractProblemKind.UNKNOWN to ContractInputPresentationProblemKind.CONTRACT_UNKNOWN,
            InputContractProblemKind.AMBIGUOUS to ContractInputPresentationProblemKind.CONTRACT_AMBIGUOUS,
            InputContractProblemKind.UNSUPPORTED to ContractInputPresentationProblemKind.CONTRACT_UNSUPPORTED,
        )

        mappings.forEach { (contractKind, presentationKind) ->
            val blocked = requirement(
                id = "blocked-${contractKind.name.lowercase()}",
                kind = InputKind.BOUND,
                expectedType = ExpectedInputType(InputShape.SCALAR, InputScalarType.STRING),
            )
            val problem = InputContractProblem(
                kind = contractKind,
                code = "fixture-${contractKind.name.lowercase()}",
                requirementId = blocked.id,
                provenance = blocked.provenance,
            )

            val presentation = ContractInputPresentationFactory.create(
                contract(requirements = listOf(blocked), problems = listOf(problem)),
            )

            assertFalse(presentation.canSubmit)
            assertTrue(presentation.fields.isEmpty())
            assertEquals(1, presentation.problems.size)
            assertEquals(presentationKind, presentation.problems.single().kind)
            assertEquals(problem.code, presentation.problems.single().code)
            assertEquals(blocked.id, presentation.problems.single().requirementId)
        }
    }

    private fun requirement(
        id: String,
        kind: InputKind,
        expectedType: ExpectedInputType,
    ): InputRequirement = InputRequirement(
        id = InputRequirementId(id),
        kind = kind,
        expectedType = expectedType,
        requiredness = InputRequiredness.REQUIRED,
        provenance = InputProvenance(
            listOf(
                InputEvidence.Placeholder(
                    kind = kind,
                    expression = id,
                    source = SourceEvidence(FILE, REVISION, null),
                ),
            ),
        ),
    )

    private fun contract(
        requirements: List<InputRequirement>,
        problems: List<InputContractProblem> = emptyList(),
    ): ParameterContract = ParameterContract(
        statementId = STATEMENT,
        requirements = requirements,
        aliases = emptyList(),
        internalBindings = emptyList(),
        blockingProblems = problems,
        sourceRevisions = mapOf(FILE to REVISION),
    )

    private companion object {
        val FILE = SourceFileId("src/example/Mapper.java")
        val REVISION = SourceRevision("revision-1")
        val STATEMENT = JavaStatementId(
            sourceFileId = FILE,
            qualifiedMapperType = "example.Mapper",
            methodSignature = MethodSignature(
                name = "find",
                parameterTypeIdentities = listOf(JavaTypeIdentity("java.lang.Object")),
            ),
        )
    }
}
