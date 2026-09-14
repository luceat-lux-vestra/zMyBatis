package com.algorist.zMyBatis.input

import com.algorist.zMyBatis.core.input.ExpectedInputType
import com.algorist.zMyBatis.core.input.InputCodecFailureKind
import com.algorist.zMyBatis.core.input.InputContractProblem
import com.algorist.zMyBatis.core.input.InputContractProblemKind
import com.algorist.zMyBatis.core.input.InputEnvironmentFailureKind
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputKind
import com.algorist.zMyBatis.core.input.InputNullability
import com.algorist.zMyBatis.core.input.InputProvenance
import com.algorist.zMyBatis.core.input.InputRequiredness
import com.algorist.zMyBatis.core.input.InputRequirement
import com.algorist.zMyBatis.core.input.InputRequirementId
import com.algorist.zMyBatis.core.input.InputScalarType
import com.algorist.zMyBatis.core.input.InputShape
import com.algorist.zMyBatis.core.input.InputValue
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
import org.junit.Assert.fail
import org.junit.Test

class ContractInputAdapterTest {
    @Test
    fun `presentation selects editors only from proven contract type and raw policy`() {
        val integer = requirement(
            id = "id",
            kind = InputKind.BOUND,
            type = ExpectedInputType(
                shape = InputShape.SCALAR,
                scalarType = InputScalarType.INTEGER,
                nullability = InputNullability.NON_NULL,
            ),
        )
        val payload = requirement(
            id = "payload",
            kind = InputKind.BOUND,
            type = ExpectedInputType(shape = InputShape.OBJECT),
        )
        val raw = requirement(
            id = "column",
            kind = InputKind.RAW_INTERPOLATION,
            type = ExpectedInputType(
                shape = InputShape.RAW_TEXT,
                scalarType = InputScalarType.STRING,
            ),
        )

        val presentation = ContractInputPresentationFactory.create(contract(integer, payload, raw))

        assertTrue(presentation.canSubmit)
        assertEquals(3, presentation.fields.size)
        assertEquals(ContractInputEditorKind.INTEGER, presentation.fields[0].editorKind)
        assertEquals("42", presentation.fields[0].exampleText)
        assertEquals(ContractInputEditorKind.JSON_OBJECT, presentation.fields[1].editorKind)
        assertEquals("{\"key\": \"value\"}", presentation.fields[1].exampleText)
        assertEquals(ContractInputEditorKind.RAW_TEXT, presentation.fields[2].editorKind)
        assertEquals(ContractInputRetentionPolicy.NEVER_RETAIN, presentation.fields[2].retentionPolicy)
        assertTrue(presentation.fields[2].requiresExplicitRawConfirmation)
        assertNull(presentation.fields[2].exampleText)
    }

    @Test
    fun `contract blockers stay explicit and never become guessed editable fields`() {
        val unknown = requirement(
            id = "value",
            kind = InputKind.BOUND,
            type = ExpectedInputType(shape = InputShape.UNKNOWN),
        )
        val problem = InputContractProblem(
            kind = InputContractProblemKind.UNKNOWN,
            code = "unproven-shape",
            requirementId = unknown.id,
            provenance = unknown.provenance,
        )

        val presentation = ContractInputPresentationFactory.create(contract(unknown, problems = listOf(problem)))

        assertFalse(presentation.canSubmit)
        assertTrue(presentation.fields.isEmpty())
        assertEquals(1, presentation.problems.size)
        assertEquals(ContractInputPresentationProblemKind.CONTRACT_UNKNOWN, presentation.problems.single().kind)
        assertEquals("unproven-shape", presentation.problems.single().code)
    }

    @Test
    fun `text adapter refuses scalar subtype guessing even when core contract allows explicit typed input`() {
        val unknownScalar = requirement(
            id = "value",
            kind = InputKind.BOUND,
            type = ExpectedInputType(shape = InputShape.SCALAR, scalarType = InputScalarType.UNKNOWN),
        )

        val presentation = ContractInputPresentationFactory.create(contract(unknownScalar))

        assertFalse(presentation.canSubmit)
        assertTrue(presentation.fields.isEmpty())
        assertEquals(
            ContractInputPresentationProblemKind.PRESENTATION_UNSUPPORTED,
            presentation.problems.single().kind,
        )
        assertEquals("text-editor-unsupported-expectation", presentation.problems.single().code)
    }

    @Test
    fun `example scaffolding is not executable input`() {
        val integer = requirement(
            id = "id",
            kind = InputKind.BOUND,
            type = ExpectedInputType(
                shape = InputShape.SCALAR,
                scalarType = InputScalarType.INTEGER,
                nullability = InputNullability.NON_NULL,
            ),
        )
        val contract = contract(integer)
        assertEquals("42", ContractInputPresentationFactory.create(contract).fields.single().exampleText)

        val result = ContractInputAdapter.prepare(contract, emptyList())
        val failures = result.failuresOrFail()

        assertEquals(1, failures.size)
        val environment = failures.single() as ContractInputAdapterFailure.Environment
        assertEquals(InputEnvironmentFailureKind.MISSING_REQUIRED_INPUT, environment.failure.kind)
        assertEquals(integer.id, environment.failure.requirementId)
    }

    @Test
    fun `core codec owns typed validation instead of dialog parsing heuristics`() {
        val integer = requirement(
            id = "id",
            kind = InputKind.BOUND,
            type = ExpectedInputType(
                shape = InputShape.SCALAR,
                scalarType = InputScalarType.INTEGER,
            ),
        )
        val result = ContractInputAdapter.prepare(
            contract(integer),
            listOf(entry(integer, "not-an-integer")),
        )

        val codec = result.failuresOrFail().single() as ContractInputAdapterFailure.Codec
        assertEquals(integer.id, codec.requirementId)
        assertEquals(InputCodecFailureKind.INVALID_INTEGER, codec.failure.kind)
    }

    @Test
    fun `retained raw interpolation is rejected by the authoritative input environment`() {
        val raw = requirement(
            id = "column",
            kind = InputKind.RAW_INTERPOLATION,
            type = ExpectedInputType(
                shape = InputShape.RAW_TEXT,
                scalarType = InputScalarType.STRING,
            ),
        )
        val result = ContractInputAdapter.prepare(
            contract(raw),
            listOf(
                ContractInputTextEntry(
                    requirementId = raw.id,
                    text = "created_at",
                    origin = ContractInputTextOrigin.USER_ACCEPTED_RETAINED,
                ),
            ),
        )

        val environment = result.failuresOrFail().single() as ContractInputAdapterFailure.Environment
        assertEquals(InputEnvironmentFailureKind.RAW_RETAINED_INPUT_FORBIDDEN, environment.failure.kind)
        assertEquals(raw.id, environment.failure.requirementId)
    }

    @Test
    fun `valid explicit text becomes typed environment without identity drift`() {
        val integer = requirement(
            id = "id",
            kind = InputKind.BOUND,
            type = ExpectedInputType(
                shape = InputShape.SCALAR,
                scalarType = InputScalarType.INTEGER,
                nullability = InputNullability.NON_NULL,
            ),
        )
        val optional = requirement(
            id = "note",
            kind = InputKind.BOUND,
            type = ExpectedInputType(
                shape = InputShape.SCALAR,
                scalarType = InputScalarType.STRING,
            ),
            requiredness = InputRequiredness.OPTIONAL,
        )
        val contract = contract(integer, optional)

        val result = ContractInputAdapter.prepare(contract, listOf(entry(integer, "9007199254740993")))
        val success = result as? ContractInputAdapterResult.Success ?: fail("expected successful environment")

        assertEquals(contract.statementId, success.environment.statementId)
        assertEquals(contract.sourceRevisions, success.environment.sourceRevisions)
        assertEquals(1, success.environment.values.size)
        val value = success.environment.value(integer.id)?.value as InputValue.IntegerValue
        assertEquals("9007199254740993", value.value.toString())
        assertNull(success.environment.value(optional.id))
    }

    @Test
    fun `duplicate and unknown entries fail before execution environment can be produced`() {
        val integer = requirement(
            id = "id",
            kind = InputKind.BOUND,
            type = ExpectedInputType(shape = InputShape.SCALAR, scalarType = InputScalarType.INTEGER),
        )
        val result = ContractInputAdapter.prepare(
            contract(integer),
            listOf(
                entry(integer, "1"),
                entry(integer, "2"),
                ContractInputTextEntry(
                    requirementId = InputRequirementId("ghost"),
                    text = "3",
                    origin = ContractInputTextOrigin.USER_ENTERED,
                ),
            ),
        )

        val kinds = result.failuresOrFail()
            .filterIsInstance<ContractInputAdapterFailure.Environment>()
            .map { it.failure.kind }
            .toSet()
        assertEquals(
            setOf(InputEnvironmentFailureKind.DUPLICATE_INPUT, InputEnvironmentFailureKind.UNKNOWN_REQUIREMENT),
            kinds,
        )
    }

    private fun requirement(
        id: String,
        kind: InputKind,
        type: ExpectedInputType,
        requiredness: InputRequiredness = InputRequiredness.REQUIRED,
    ): InputRequirement {
        val requirementId = InputRequirementId(id)
        val evidence = SourceEvidence(FILE, REVISION, sourceRange = null)
        return InputRequirement(
            id = requirementId,
            kind = kind,
            expectedType = type,
            requiredness = requiredness,
            provenance = InputProvenance(
                listOf(
                    InputEvidence.Placeholder(
                        kind = kind,
                        expression = id,
                        source = evidence,
                    ),
                ),
            ),
        )
    }

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

    private fun entry(requirement: InputRequirement, text: String): ContractInputTextEntry =
        ContractInputTextEntry(
            requirementId = requirement.id,
            text = text,
            origin = ContractInputTextOrigin.USER_ENTERED,
        )

    private fun ContractInputAdapterResult.failuresOrFail(): List<ContractInputAdapterFailure> =
        (this as? ContractInputAdapterResult.Failure)?.failures ?: fail("expected adapter failure")

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
