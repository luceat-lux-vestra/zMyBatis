package com.algorist.zMyBatis.core.input

import com.algorist.zMyBatis.core.source.JavaStatementId
import com.algorist.zMyBatis.core.source.JavaTypeIdentity
import com.algorist.zMyBatis.core.source.MethodSignature
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceRevision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.time.LocalDate

class InputContractTest {
    private val statementId = JavaStatementId(
        SourceFileId("src/main/java/com/acme/UserMapper.java"),
        "com.acme.UserMapper",
        MethodSignature("find", listOf(JavaTypeIdentity("java.lang.Long"))),
    )
    private val sourceRevision = SourceRevision("document:42")
    private val source = SourceEvidence(statementId.sourceFileId, sourceRevision, SourceRange(10, 20))
    private val sourceRevisions = mapOf(statementId.sourceFileId to sourceRevision)

    @Test
    fun contractDefensivelyCopiesCallerOwnedCollections() {
        val requirement = boundRequirement("id", InputShape.SCALAR, InputScalarType.INTEGER)
        val requirements = mutableListOf(requirement)
        val internalBindings = mutableListOf(
            InternalBinding(
                "_parameter",
                InternalBindingKind.MYBATIS_CONTEXT,
                provenance(InputEvidence.MapperMethodParameter(0, "id", JavaTypeIdentity("java.lang.Long"), source)),
            ),
        )
        val problems = mutableListOf<InputContractProblem>()
        val revisions = linkedMapOf(statementId.sourceFileId to sourceRevision)
        val contract = ParameterContract(statementId, requirements, internalBindings, problems, revisions)

        requirements.clear()
        internalBindings.clear()
        problems += InputContractProblem(InputContractProblemKind.UNKNOWN, "late-mutation", null, null)
        revisions.clear()

        assertEquals(listOf(requirement), contract.requirements)
        assertEquals(1, contract.internalBindings.size)
        assertTrue(contract.blockingProblems.isEmpty())
        assertEquals(sourceRevisions, contract.sourceRevisions)
    }

    @Test
    fun unknownRequirementShapeMustCarryExplicitBlockingProblem() {
        val requirement = boundRequirement("mystery", InputShape.UNKNOWN)

        assertThrows(IllegalArgumentException::class.java) {
            contract(listOf(requirement))
        }

        val blocked = contract(
            requirements = listOf(requirement),
            blockingProblems = listOf(
                InputContractProblem(
                    InputContractProblemKind.UNKNOWN,
                    "unproven-input-shape",
                    requirement.id,
                    requirement.provenance,
                ),
            ),
        )

        assertTrue(blocked.isPreparationBlocked)
        assertEquals(
            InputEnvironmentResult.Failure(
                listOf(InputEnvironmentFailure(InputEnvironmentFailureKind.CONTRACT_BLOCKED, null)),
            ),
            InputEnvironment.validate(blocked, emptyList()),
        )
    }

    @Test
    fun explicitTypedValueCanResolveAProvenScalarShapeWithoutGuessingSubtype() {
        val requirement = boundRequirement("value", InputShape.SCALAR, InputScalarType.UNKNOWN)
        val contract = contract(listOf(requirement))
        val provided = ProvidedInput(
            requirement.id,
            InputValue.IntegerValue(BigInteger.valueOf(42)),
            ExecutionInputOrigin.USER_ENTERED,
        )

        val result = InputEnvironment.validate(contract, listOf(provided)) as InputEnvironmentResult.Success

        assertEquals(provided, result.environment.value(requirement.id))
    }

    @Test
    fun environmentRejectsMissingDuplicateAndUnknownInputs() {
        val id = boundRequirement("id", InputShape.SCALAR, InputScalarType.INTEGER)
        val contract = contract(listOf(id))
        val duplicate = ProvidedInput(
            id.id,
            InputValue.IntegerValue(BigInteger.ONE),
            ExecutionInputOrigin.USER_ENTERED,
        )
        val unknown = ProvidedInput(
            InputRequirementId("other"),
            InputValue.IntegerValue(BigInteger.TWO),
            ExecutionInputOrigin.USER_ENTERED,
        )

        val result = InputEnvironment.validate(contract, listOf(duplicate, duplicate, unknown))
        val failures = (result as InputEnvironmentResult.Failure).failures

        assertTrue(failures.contains(InputEnvironmentFailure(InputEnvironmentFailureKind.DUPLICATE_INPUT, id.id)))
        assertTrue(
            failures.contains(
                InputEnvironmentFailure(InputEnvironmentFailureKind.UNKNOWN_REQUIREMENT, unknown.requirementId),
            ),
        )

        val missing = InputEnvironment.validate(contract, emptyList()) as InputEnvironmentResult.Failure
        assertEquals(
            listOf(InputEnvironmentFailure(InputEnvironmentFailureKind.MISSING_REQUIRED_INPUT, id.id)),
            missing.failures,
        )
    }

    @Test
    fun environmentRejectsWrongScalarAndTemporalSubtypesEvenWhenShapeMatches() {
        val integer = boundRequirement("id", InputShape.SCALAR, InputScalarType.INTEGER)
        val date = boundRequirement("date", InputShape.TEMPORAL, InputScalarType.DATE)
        val contract = contract(listOf(integer, date))

        val result = InputEnvironment.validate(
            contract,
            listOf(
                ProvidedInput(integer.id, InputValue.Text("42"), ExecutionInputOrigin.USER_ENTERED),
                ProvidedInput(
                    date.id,
                    InputValue.DateTimeValue(LocalDate.parse("2026-09-14").atStartOfDay()),
                    ExecutionInputOrigin.USER_ENTERED,
                ),
            ),
        ) as InputEnvironmentResult.Failure

        assertTrue(
            result.failures.contains(
                InputEnvironmentFailure(InputEnvironmentFailureKind.TYPE_MISMATCH, integer.id),
            ),
        )
        assertTrue(
            result.failures.contains(
                InputEnvironmentFailure(InputEnvironmentFailureKind.TYPE_MISMATCH, date.id),
            ),
        )
    }

    @Test
    fun successfulEnvironmentIsBoundToCanonicalStatementIdentityAndSourceRevisions() {
        val id = boundRequirement("id", InputShape.SCALAR, InputScalarType.INTEGER)
        val contract = contract(listOf(id))
        val provided = ProvidedInput(
            id.id,
            InputValue.IntegerValue(BigInteger.valueOf(42)),
            ExecutionInputOrigin.USER_ENTERED,
        )

        val environment = (InputEnvironment.validate(contract, listOf(provided)) as InputEnvironmentResult.Success).environment

        assertEquals(statementId, environment.statementId)
        assertEquals(sourceRevisions, environment.sourceRevisions)
        assertEquals(provided, environment.value(id.id))
    }

    @Test
    fun provenanceRevisionMustMatchContractRevisionSet() {
        val requirement = boundRequirement("id", InputShape.SCALAR, InputScalarType.INTEGER)

        assertThrows(IllegalArgumentException::class.java) {
            ParameterContract(
                statementId,
                listOf(requirement),
                emptyList(),
                emptyList(),
                mapOf(statementId.sourceFileId to SourceRevision("document:43")),
            )
        }
    }

    @Test
    fun requirementSpecificProblemsMustCarryProvenance() {
        val requirement = boundRequirement("id", InputShape.UNKNOWN)

        assertThrows(IllegalArgumentException::class.java) {
            InputContractProblem(
                InputContractProblemKind.UNKNOWN,
                "unproven-input-shape",
                requirement.id,
                null,
            )
        }
    }

    @Test
    fun rawAndBoundInputsCannotBeConflatedOrSilentlyRestored() {
        val raw = InputRequirement(
            InputRequirementId("orderBy"),
            InputKind.RAW_INTERPOLATION,
            ExpectedInputType(InputShape.RAW_TEXT, InputScalarType.STRING, nullability = InputNullability.NON_NULL),
            InputRequiredness.REQUIRED,
            provenance(InputEvidence.Placeholder(InputKind.RAW_INTERPOLATION, "orderBy", source)),
        )
        val bound = boundRequirement("id", InputShape.SCALAR, InputScalarType.INTEGER)
        val contract = contract(listOf(raw, bound))

        val result = InputEnvironment.validate(
            contract,
            listOf(
                ProvidedInput(raw.id, InputValue.Text("created_at"), ExecutionInputOrigin.USER_ENTERED),
                ProvidedInput(
                    bound.id,
                    InputValue.RawText("42"),
                    ExecutionInputOrigin.USER_ENTERED,
                ),
            ),
        ) as InputEnvironmentResult.Failure

        assertTrue(
            result.failures.contains(InputEnvironmentFailure(InputEnvironmentFailureKind.KIND_MISMATCH, raw.id)),
        )
        assertTrue(
            result.failures.contains(InputEnvironmentFailure(InputEnvironmentFailureKind.KIND_MISMATCH, bound.id)),
        )

        val retainedRaw = InputEnvironment.validate(
            contract,
            listOf(
                ProvidedInput(
                    raw.id,
                    InputValue.RawText("created_at"),
                    ExecutionInputOrigin.USER_ACCEPTED_RETAINED,
                ),
                ProvidedInput(
                    bound.id,
                    InputValue.IntegerValue(BigInteger.ONE),
                    ExecutionInputOrigin.USER_ENTERED,
                ),
            ),
        ) as InputEnvironmentResult.Failure

        assertTrue(
            retainedRaw.failures.contains(
                InputEnvironmentFailure(InputEnvironmentFailureKind.RAW_RETAINED_INPUT_FORBIDDEN, raw.id),
            ),
        )
    }

    @Test
    fun failureResultsDefensivelyCopyCallerOwnedLists() {
        val callerOwned = mutableListOf(
            InputEnvironmentFailure(InputEnvironmentFailureKind.CONTRACT_BLOCKED, null),
        )
        val result = InputEnvironmentResult.Failure(callerOwned)

        callerOwned.clear()

        assertEquals(
            listOf(InputEnvironmentFailure(InputEnvironmentFailureKind.CONTRACT_BLOCKED, null)),
            result.failures,
        )
    }

    @Test
    fun duplicateRequirementIdsAreRejected() {
        val first = boundRequirement("id", InputShape.SCALAR, InputScalarType.INTEGER)
        val second = boundRequirement("id", InputShape.SCALAR, InputScalarType.STRING)

        assertThrows(IllegalArgumentException::class.java) {
            contract(listOf(first, second))
        }
    }

    private fun contract(
        requirements: List<InputRequirement>,
        internalBindings: List<InternalBinding> = emptyList(),
        blockingProblems: List<InputContractProblem> = emptyList(),
    ): ParameterContract = ParameterContract(
        statementId,
        requirements,
        internalBindings,
        blockingProblems,
        sourceRevisions,
    )

    private fun boundRequirement(
        id: String,
        shape: InputShape,
        scalarType: InputScalarType = InputScalarType.UNKNOWN,
    ): InputRequirement = InputRequirement(
        InputRequirementId(id),
        InputKind.BOUND,
        ExpectedInputType(shape, scalarType),
        InputRequiredness.REQUIRED,
        provenance(InputEvidence.Placeholder(InputKind.BOUND, id, source)),
    )

    private fun provenance(vararg evidence: InputEvidence): InputProvenance = InputProvenance(evidence.toList())
}
