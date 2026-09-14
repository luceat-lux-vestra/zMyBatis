package com.algorist.zMyBatis.input

import com.algorist.zMyBatis.core.input.ExecutionInputOrigin
import com.algorist.zMyBatis.core.input.ExpectedInputType
import com.algorist.zMyBatis.core.input.InputCodec
import com.algorist.zMyBatis.core.input.InputCodecFailure
import com.algorist.zMyBatis.core.input.InputContractProblem
import com.algorist.zMyBatis.core.input.InputContractProblemKind
import com.algorist.zMyBatis.core.input.InputDecodeResult
import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.InputEnvironmentFailure
import com.algorist.zMyBatis.core.input.InputEnvironmentFailureKind
import com.algorist.zMyBatis.core.input.InputEnvironmentResult
import com.algorist.zMyBatis.core.input.InputKind
import com.algorist.zMyBatis.core.input.InputProvenance
import com.algorist.zMyBatis.core.input.InputRequiredness
import com.algorist.zMyBatis.core.input.InputRequirement
import com.algorist.zMyBatis.core.input.InputRequirementId
import com.algorist.zMyBatis.core.input.InputScalarType
import com.algorist.zMyBatis.core.input.InputShape
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.input.ProvidedInput
import com.algorist.zMyBatis.core.source.StatementId

/**
 * Presentation-only description derived from the authoritative [ParameterContract].
 *
 * This layer may choose an editor for an already-proven input shape/type, but it must not
 * rediscover parameter meaning from names, SQL text, PSI, or legacy heuristics.
 */
enum class ContractInputEditorKind {
    TEXT,
    BOOLEAN,
    INTEGER,
    DECIMAL,
    UUID,
    DATE,
    TIME,
    DATE_TIME,
    INSTANT,
    JSON_OBJECT,
    JSON_LIST,
    JSON_ARRAY,
    JSON_MAP,
    RAW_TEXT,
}

enum class ContractInputRetentionPolicy {
    RETAINABLE,
    NEVER_RETAIN,
}

data class ContractInputField(
    val requirementId: InputRequirementId,
    val inputKind: InputKind,
    val requiredness: InputRequiredness,
    val expectedType: ExpectedInputType,
    val editorKind: ContractInputEditorKind,
    val provenance: InputProvenance,
    val retentionPolicy: ContractInputRetentionPolicy,
    val requiresExplicitRawConfirmation: Boolean,
    /**
     * Presentation-only scaffold. It is never converted to a [ProvidedInput] automatically.
     */
    val exampleText: String?,
)

enum class ContractInputPresentationProblemKind {
    CONTRACT_UNKNOWN,
    CONTRACT_AMBIGUOUS,
    CONTRACT_UNSUPPORTED,
    PRESENTATION_UNSUPPORTED,
}

data class ContractInputPresentationProblem(
    val kind: ContractInputPresentationProblemKind,
    val code: String,
    val requirementId: InputRequirementId?,
    val provenance: InputProvenance?,
)

class ContractInputPresentation(
    val statementId: StatementId,
    fields: List<ContractInputField>,
    problems: List<ContractInputPresentationProblem>,
) {
    private val fieldSnapshot = fields.toList()
    private val problemSnapshot = problems.toList()

    val fields: List<ContractInputField>
        get() = fieldSnapshot.toList()

    val problems: List<ContractInputPresentationProblem>
        get() = problemSnapshot.toList()

    val canSubmit: Boolean
        get() = problemSnapshot.isEmpty()
}

object ContractInputPresentationFactory {
    fun create(contract: ParameterContract): ContractInputPresentation {
        val problems = contract.blockingProblems.map(::contractProblem)
        val blockedRequirementIds = contract.blockingProblems.mapNotNull { it.requirementId }.toSet()
        val presentationProblems = problems.toMutableList()

        val fields = contract.requirements.mapNotNull { requirement ->
            if (requirement.id in blockedRequirementIds) {
                return@mapNotNull null
            }

            val editorKind = editorKind(requirement)
            if (editorKind == null) {
                presentationProblems += ContractInputPresentationProblem(
                    kind = ContractInputPresentationProblemKind.PRESENTATION_UNSUPPORTED,
                    code = "text-editor-unsupported-expectation",
                    requirementId = requirement.id,
                    provenance = requirement.provenance,
                )
                return@mapNotNull null
            }

            ContractInputField(
                requirementId = requirement.id,
                inputKind = requirement.kind,
                requiredness = requirement.requiredness,
                expectedType = requirement.expectedType,
                editorKind = editorKind,
                provenance = requirement.provenance,
                retentionPolicy = if (requirement.kind == InputKind.RAW_INTERPOLATION) {
                    ContractInputRetentionPolicy.NEVER_RETAIN
                } else {
                    ContractInputRetentionPolicy.RETAINABLE
                },
                requiresExplicitRawConfirmation = requirement.kind == InputKind.RAW_INTERPOLATION,
                exampleText = exampleText(requirement),
            )
        }

        return ContractInputPresentation(
            statementId = contract.statementId,
            fields = fields,
            problems = presentationProblems,
        )
    }

    private fun editorKind(requirement: InputRequirement): ContractInputEditorKind? {
        if (requirement.kind == InputKind.RAW_INTERPOLATION) {
            return ContractInputEditorKind.RAW_TEXT
        }

        val expected = requirement.expectedType
        return when (expected.shape) {
            InputShape.SCALAR -> when (expected.scalarType) {
                InputScalarType.STRING -> ContractInputEditorKind.TEXT
                InputScalarType.BOOLEAN -> ContractInputEditorKind.BOOLEAN
                InputScalarType.INTEGER -> ContractInputEditorKind.INTEGER
                InputScalarType.DECIMAL -> ContractInputEditorKind.DECIMAL
                InputScalarType.UUID -> ContractInputEditorKind.UUID
                InputScalarType.UNKNOWN -> null
                InputScalarType.DATE,
                InputScalarType.TIME,
                InputScalarType.DATE_TIME,
                InputScalarType.INSTANT,
                -> null
            }
            InputShape.TEMPORAL -> when (expected.scalarType) {
                InputScalarType.DATE -> ContractInputEditorKind.DATE
                InputScalarType.TIME -> ContractInputEditorKind.TIME
                InputScalarType.DATE_TIME -> ContractInputEditorKind.DATE_TIME
                InputScalarType.INSTANT -> ContractInputEditorKind.INSTANT
                InputScalarType.UNKNOWN -> null
                InputScalarType.STRING,
                InputScalarType.BOOLEAN,
                InputScalarType.INTEGER,
                InputScalarType.DECIMAL,
                InputScalarType.UUID,
                -> null
            }
            InputShape.OBJECT -> ContractInputEditorKind.JSON_OBJECT
            InputShape.LIST -> ContractInputEditorKind.JSON_LIST
            InputShape.ARRAY -> ContractInputEditorKind.JSON_ARRAY
            InputShape.MAP -> ContractInputEditorKind.JSON_MAP
            InputShape.RAW_TEXT -> ContractInputEditorKind.RAW_TEXT
            InputShape.UNKNOWN -> null
        }
    }

    private fun exampleText(requirement: InputRequirement): String? = when (requirement.expectedType.shape) {
        InputShape.SCALAR -> when (requirement.expectedType.scalarType) {
            InputScalarType.STRING -> "text"
            InputScalarType.BOOLEAN -> "true"
            InputScalarType.INTEGER -> "42"
            InputScalarType.DECIMAL -> "12.34"
            InputScalarType.UUID -> "123e4567-e89b-12d3-a456-426614174000"
            InputScalarType.DATE,
            InputScalarType.TIME,
            InputScalarType.DATE_TIME,
            InputScalarType.INSTANT,
            InputScalarType.UNKNOWN,
            -> null
        }
        InputShape.TEMPORAL -> when (requirement.expectedType.scalarType) {
            InputScalarType.DATE -> "2026-09-14"
            InputScalarType.TIME -> "12:34:56"
            InputScalarType.DATE_TIME -> "2026-09-14T12:34:56"
            InputScalarType.INSTANT -> "2026-09-14T03:34:56Z"
            else -> null
        }
        InputShape.OBJECT,
        InputShape.MAP,
        -> "{\"key\": \"value\"}"
        InputShape.LIST,
        InputShape.ARRAY,
        -> "[1, 2, 3]"
        InputShape.RAW_TEXT -> null
        InputShape.UNKNOWN -> null
    }

    private fun contractProblem(problem: InputContractProblem): ContractInputPresentationProblem =
        ContractInputPresentationProblem(
            kind = when (problem.kind) {
                InputContractProblemKind.UNKNOWN -> ContractInputPresentationProblemKind.CONTRACT_UNKNOWN
                InputContractProblemKind.AMBIGUOUS -> ContractInputPresentationProblemKind.CONTRACT_AMBIGUOUS
                InputContractProblemKind.UNSUPPORTED -> ContractInputPresentationProblemKind.CONTRACT_UNSUPPORTED
            },
            code = problem.code,
            requirementId = problem.requirementId,
            provenance = problem.provenance,
        )
}

enum class ContractInputTextOrigin {
    USER_ENTERED,
    USER_ACCEPTED_RETAINED,
}

data class ContractInputTextEntry(
    val requirementId: InputRequirementId,
    val text: String,
    val origin: ContractInputTextOrigin,
)

sealed interface ContractInputAdapterFailure {
    data class Codec(
        val requirementId: InputRequirementId,
        val failure: InputCodecFailure,
    ) : ContractInputAdapterFailure

    data class Environment(
        val failure: InputEnvironmentFailure,
    ) : ContractInputAdapterFailure

    data class Presentation(
        val problem: ContractInputPresentationProblem,
    ) : ContractInputAdapterFailure
}

sealed interface ContractInputAdapterResult {
    data class Success(val environment: InputEnvironment) : ContractInputAdapterResult

    class Failure(failures: List<ContractInputAdapterFailure>) : ContractInputAdapterResult {
        private val failureSnapshot = failures.toList()

        init {
            require(failureSnapshot.isNotEmpty()) { "contract input adapter failure must not be empty" }
        }

        val failures: List<ContractInputAdapterFailure>
            get() = failureSnapshot.toList()
    }
}

/**
 * Converts explicit user/accepted-retained text into the same core [InputEnvironment] used by
 * later preparation. No example text, placeholder text, or history value is injected implicitly.
 */
object ContractInputAdapter {
    fun prepare(
        contract: ParameterContract,
        entries: List<ContractInputTextEntry>,
    ): ContractInputAdapterResult {
        val presentation = ContractInputPresentationFactory.create(contract)
        if (!presentation.canSubmit) {
            return ContractInputAdapterResult.Failure(
                presentation.problems.map { ContractInputAdapterFailure.Presentation(it) },
            )
        }

        val failures = mutableListOf<ContractInputAdapterFailure>()
        val grouped = entries.groupBy { it.requirementId }
        grouped.filterValues { it.size > 1 }.keys.forEach { requirementId ->
            failures += ContractInputAdapterFailure.Environment(
                InputEnvironmentFailure(InputEnvironmentFailureKind.DUPLICATE_INPUT, requirementId),
            )
        }

        val provided = mutableListOf<ProvidedInput>()
        grouped.forEach { (requirementId, duplicates) ->
            val entry = duplicates.first()
            val requirement = contract.requirement(requirementId)
            if (requirement == null) {
                failures += ContractInputAdapterFailure.Environment(
                    InputEnvironmentFailure(InputEnvironmentFailureKind.UNKNOWN_REQUIREMENT, requirementId),
                )
                return@forEach
            }

            when (val decoded = InputCodec.decode(entry.text, requirement)) {
                is InputDecodeResult.Success -> provided += ProvidedInput(
                    requirementId = requirementId,
                    value = decoded.value,
                    origin = when (entry.origin) {
                        ContractInputTextOrigin.USER_ENTERED -> ExecutionInputOrigin.USER_ENTERED
                        ContractInputTextOrigin.USER_ACCEPTED_RETAINED ->
                            ExecutionInputOrigin.USER_ACCEPTED_RETAINED
                    },
                )
                is InputDecodeResult.Failure -> failures += ContractInputAdapterFailure.Codec(
                    requirementId = requirementId,
                    failure = decoded.failure,
                )
            }
        }

        if (failures.isNotEmpty()) {
            return ContractInputAdapterResult.Failure(failures)
        }

        return when (val environment = InputEnvironment.validate(contract, provided)) {
            is InputEnvironmentResult.Success -> ContractInputAdapterResult.Success(environment.environment)
            is InputEnvironmentResult.Failure -> ContractInputAdapterResult.Failure(
                environment.failures.map { ContractInputAdapterFailure.Environment(it) },
            )
        }
    }
}
