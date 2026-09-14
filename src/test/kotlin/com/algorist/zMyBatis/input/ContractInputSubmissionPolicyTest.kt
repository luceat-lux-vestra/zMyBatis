package com.algorist.zMyBatis.input

import com.algorist.zMyBatis.core.input.ExecutionInputOrigin
import com.algorist.zMyBatis.core.input.ExpectedInputType
import com.algorist.zMyBatis.core.input.InputContractProblem
import com.algorist.zMyBatis.core.input.InputContractProblemKind
import com.algorist.zMyBatis.core.input.InputEnvironmentFailureKind
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

class ContractInputSubmissionPolicyTest {
    @Test
    fun `required field is not executable until user explicitly edits it`() {
        val id = integerRequirement("id")
        val result = ContractInputSubmissionPolicy.prepare(
            contract(id),
            drafts = listOf(draft(id, text = "42", supplied = true, edited = false)),
            acceptRetained = false,
        )

        val problem = result.failuresOrFail().single() as ContractInputAdapterFailure.Presentation
        assertEquals("required-input-not-explicitly-entered", problem.problem.code)
        assertEquals(id.id, problem.problem.requirementId)
    }

    @Test
    fun `optional field may be omitted without manufacturing an input`() {
        val required = integerRequirement("id")
        val optional = stringRequirement("note", InputRequiredness.OPTIONAL)
        val result = ContractInputSubmissionPolicy.prepare(
            contract(required, optional),
            drafts = listOf(
                draft(required, "7", supplied = true, edited = true),
                draft(optional, "example only", supplied = false, edited = false),
            ),
            acceptRetained = false,
        )

        assertTrue(result is ContractInputAdapterResult.Success)
        val environment = (result as ContractInputAdapterResult.Success).environment
        assertEquals(1, environment.values.size)
        assertNull(environment.value(optional.id))
    }

    @Test
    fun `remembered value requires explicit acceptance and preserves retained origin`() {
        val id = integerRequirement("id")
        val unaccepted = ContractInputSubmissionPolicy.prepare(
            contract(id),
            drafts = listOf(draft(id, "9", supplied = true, edited = false, retained = true)),
            acceptRetained = false,
        )
        val problem = unaccepted.failuresOrFail().single() as ContractInputAdapterFailure.Presentation
        assertEquals("retained-input-requires-explicit-acceptance", problem.problem.code)

        val accepted = ContractInputSubmissionPolicy.prepare(
            contract(id),
            drafts = listOf(draft(id, "9", supplied = true, edited = false, retained = true)),
            acceptRetained = true,
        )
        assertTrue(accepted is ContractInputAdapterResult.Success)
        val environment = (accepted as ContractInputAdapterResult.Success).environment
        assertEquals(ExecutionInputOrigin.USER_ACCEPTED_RETAINED, environment.value(id.id)?.origin)
    }

    @Test
    fun `editing remembered value turns it into user entered input`() {
        val id = integerRequirement("id")
        val result = ContractInputSubmissionPolicy.prepare(
            contract(id),
            drafts = listOf(draft(id, "10", supplied = true, edited = true, retained = true)),
            acceptRetained = false,
        )

        assertTrue(result is ContractInputAdapterResult.Success)
        val environment = (result as ContractInputAdapterResult.Success).environment
        assertEquals(ExecutionInputOrigin.USER_ENTERED, environment.value(id.id)?.origin)
    }

    @Test
    fun `raw interpolation requires explicit confirmation`() {
        val raw = rawRequirement("column")
        val result = ContractInputSubmissionPolicy.prepare(
            contract(raw),
            drafts = listOf(draft(raw, "created_at", supplied = true, edited = true, rawConfirmed = false)),
            acceptRetained = false,
        )

        val problem = result.failuresOrFail().single() as ContractInputAdapterFailure.Presentation
        assertEquals("raw-input-requires-explicit-confirmation", problem.problem.code)

        val confirmed = ContractInputSubmissionPolicy.prepare(
            contract(raw),
            drafts = listOf(draft(raw, "created_at", supplied = true, edited = true, rawConfirmed = true)),
            acceptRetained = false,
        )
        assertTrue(confirmed is ContractInputAdapterResult.Success)
    }

    @Test
    fun `retained raw interpolation remains forbidden even when presentation says accepted`() {
        val raw = rawRequirement("column")
        val result = ContractInputSubmissionPolicy.prepare(
            contract(raw),
            drafts = listOf(
                draft(
                    raw,
                    text = "created_at",
                    supplied = true,
                    edited = false,
                    retained = true,
                    rawConfirmed = true,
                ),
            ),
            acceptRetained = true,
        )

        val failure = result.failuresOrFail().single() as ContractInputAdapterFailure.Environment
        assertEquals(InputEnvironmentFailureKind.RAW_RETAINED_INPUT_FORBIDDEN, failure.failure.kind)
    }

    @Test
    fun `blocked contract refuses drafts before decoding`() {
        val unknown = InputRequirement(
            id = InputRequirementId("unknown"),
            kind = InputKind.BOUND,
            expectedType = ExpectedInputType(InputShape.UNKNOWN),
            requiredness = InputRequiredness.REQUIRED,
            provenance = provenance("unknown", InputKind.BOUND),
        )
        val blocker = InputContractProblem(
            kind = InputContractProblemKind.UNKNOWN,
            code = "unproven-shape",
            requirementId = unknown.id,
            provenance = unknown.provenance,
        )
        val result = ContractInputSubmissionPolicy.prepare(
            contract(unknown, problems = listOf(blocker)),
            drafts = listOf(draft(unknown, "123", supplied = true, edited = true)),
            acceptRetained = false,
        )

        val failure = result.failuresOrFail().single() as ContractInputAdapterFailure.Presentation
        assertEquals(ContractInputPresentationProblemKind.CONTRACT_UNKNOWN, failure.problem.kind)
        assertEquals("unproven-shape", failure.problem.code)
    }

    @Test
    fun `missing required draft fails closed instead of relying on placeholder text`() {
        val id = integerRequirement("id")
        val result = ContractInputSubmissionPolicy.prepare(contract(id), drafts = emptyList(), acceptRetained = false)

        val failure = result.failuresOrFail().single() as ContractInputAdapterFailure.Presentation
        assertEquals("required-input-not-supplied", failure.problem.code)
        assertFalse(result is ContractInputAdapterResult.Success)
    }

    private fun integerRequirement(id: String): InputRequirement = InputRequirement(
        id = InputRequirementId(id),
        kind = InputKind.BOUND,
        expectedType = ExpectedInputType(InputShape.SCALAR, InputScalarType.INTEGER),
        requiredness = InputRequiredness.REQUIRED,
        provenance = provenance(id, InputKind.BOUND),
    )

    private fun stringRequirement(id: String, requiredness: InputRequiredness): InputRequirement = InputRequirement(
        id = InputRequirementId(id),
        kind = InputKind.BOUND,
        expectedType = ExpectedInputType(InputShape.SCALAR, InputScalarType.STRING),
        requiredness = requiredness,
        provenance = provenance(id, InputKind.BOUND),
    )

    private fun rawRequirement(id: String): InputRequirement = InputRequirement(
        id = InputRequirementId(id),
        kind = InputKind.RAW_INTERPOLATION,
        expectedType = ExpectedInputType(InputShape.RAW_TEXT, InputScalarType.STRING),
        requiredness = InputRequiredness.REQUIRED,
        provenance = provenance(id, InputKind.RAW_INTERPOLATION),
    )

    private fun provenance(expression: String, kind: InputKind): InputProvenance = InputProvenance(
        listOf(InputEvidence.Placeholder(kind, expression, SourceEvidence(FILE, REVISION, null))),
    )

    private fun contract(
        vararg requirements: InputRequirement,
        problems: List<InputContractProblem> = emptyList(),
    ): ParameterContract = ParameterContract(
        statementId = STATEMENT,
        requirements = requirements.toList(),
        aliases = emptyList(),
        internalBindings = emptyList(),
        blockingProblems = problems,
        sourceRevisions = mapOf(FILE to REVISION),
    )

    private fun draft(
        requirement: InputRequirement,
        text: String,
        supplied: Boolean,
        edited: Boolean,
        retained: Boolean = false,
        rawConfirmed: Boolean = false,
    ): ContractInputDraft = ContractInputDraft(
        requirementId = requirement.id,
        text = text,
        supplied = supplied,
        edited = edited,
        retained = retained,
        rawConfirmed = rawConfirmed,
    )

    private fun ContractInputAdapterResult.failuresOrFail(): List<ContractInputAdapterFailure> {
        assertTrue(this is ContractInputAdapterResult.Failure)
        return (this as ContractInputAdapterResult.Failure).failures
    }

    private companion object {
        val FILE = SourceFileId("src/example/Mapper.java")
        val REVISION = SourceRevision("revision-1")
        val STATEMENT = JavaStatementId(
            sourceFileId = FILE,
            qualifiedMapperType = "example.Mapper",
            methodSignature = MethodSignature("find", listOf(JavaTypeIdentity("java.lang.Long"))),
        )
    }
}
