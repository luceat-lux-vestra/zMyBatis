package com.algorist.zMyBatis.core.input

import com.algorist.zMyBatis.core.source.StatementId
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

sealed interface InputValue {
    data object NullValue : InputValue

    data class Text(val value: String) : InputValue

    data class RawText(val value: String) : InputValue

    data class BooleanValue(val value: Boolean) : InputValue

    data class IntegerValue(val value: BigInteger) : InputValue

    data class DecimalValue(val value: BigDecimal) : InputValue

    data class DateValue(val value: LocalDate) : InputValue

    data class TimeValue(val value: LocalTime) : InputValue

    data class DateTimeValue(val value: LocalDateTime) : InputValue

    data class InstantValue(val value: Instant) : InputValue

    data class UuidValue(val value: UUID) : InputValue

    class ObjectValue(entries: Map<String, InputValue>) : InputValue {
        private val entrySnapshot = LinkedHashMap(entries)

        val entries: Map<String, InputValue>
            get() = LinkedHashMap(entrySnapshot)

        override fun equals(other: Any?): Boolean =
            this === other || (other is ObjectValue && entrySnapshot == other.entrySnapshot)

        override fun hashCode(): Int = entrySnapshot.hashCode()
    }

    class MapValue(entries: Map<String, InputValue>) : InputValue {
        private val entrySnapshot = LinkedHashMap(entries)

        val entries: Map<String, InputValue>
            get() = LinkedHashMap(entrySnapshot)

        override fun equals(other: Any?): Boolean =
            this === other || (other is MapValue && entrySnapshot == other.entrySnapshot)

        override fun hashCode(): Int = entrySnapshot.hashCode()
    }

    class ListValue(elements: List<InputValue>) : InputValue {
        private val elementSnapshot = elements.toList()

        val elements: List<InputValue>
            get() = elementSnapshot.toList()

        override fun equals(other: Any?): Boolean =
            this === other || (other is ListValue && elementSnapshot == other.elementSnapshot)

        override fun hashCode(): Int = elementSnapshot.hashCode()
    }

    class ArrayValue(elements: List<InputValue>) : InputValue {
        private val elementSnapshot = elements.toList()

        val elements: List<InputValue>
            get() = elementSnapshot.toList()

        override fun equals(other: Any?): Boolean =
            this === other || (other is ArrayValue && elementSnapshot == other.elementSnapshot)

        override fun hashCode(): Int = elementSnapshot.hashCode()
    }
}

enum class ExecutionInputOrigin {
    USER_ENTERED,
    USER_ACCEPTED_RETAINED,
}

data class ProvidedInput(
    val requirementId: InputRequirementId,
    val value: InputValue,
    val origin: ExecutionInputOrigin,
)

enum class InputEnvironmentFailureKind {
    CONTRACT_BLOCKED,
    DUPLICATE_INPUT,
    UNKNOWN_REQUIREMENT,
    MISSING_REQUIRED_INPUT,
    KIND_MISMATCH,
    SHAPE_MISMATCH,
    RAW_RETAINED_INPUT_FORBIDDEN,
    NULL_NOT_ALLOWED,
}

data class InputEnvironmentFailure(
    val kind: InputEnvironmentFailureKind,
    val requirementId: InputRequirementId?,
)

sealed interface InputEnvironmentResult {
    data class Success(val environment: InputEnvironment) : InputEnvironmentResult

    data class Failure(val failures: List<InputEnvironmentFailure>) : InputEnvironmentResult {
        init {
            require(failures.isNotEmpty()) { "input environment failure must contain at least one failure" }
        }
    }
}

class InputEnvironment private constructor(
    val statementId: StatementId,
    values: Map<InputRequirementId, ProvidedInput>,
) {
    private val valueSnapshot = LinkedHashMap(values)

    val values: Map<InputRequirementId, ProvidedInput>
        get() = LinkedHashMap(valueSnapshot)

    fun value(id: InputRequirementId): ProvidedInput? = valueSnapshot[id]

    companion object {
        fun validate(
            contract: ParameterContract,
            providedInputs: List<ProvidedInput>,
        ): InputEnvironmentResult {
            if (contract.isPreparationBlocked) {
                return InputEnvironmentResult.Failure(
                    listOf(InputEnvironmentFailure(InputEnvironmentFailureKind.CONTRACT_BLOCKED, null)),
                )
            }

            val failures = mutableListOf<InputEnvironmentFailure>()
            val grouped = providedInputs.groupBy { it.requirementId }
            grouped.filterValues { it.size > 1 }.keys.forEach { id ->
                failures += InputEnvironmentFailure(InputEnvironmentFailureKind.DUPLICATE_INPUT, id)
            }

            val uniqueInputs = LinkedHashMap<InputRequirementId, ProvidedInput>()
            providedInputs.forEach { input ->
                if (input.requirementId !in uniqueInputs) {
                    uniqueInputs[input.requirementId] = input
                }
            }

            uniqueInputs.forEach { (id, provided) ->
                val requirement = contract.requirement(id)
                if (requirement == null) {
                    failures += InputEnvironmentFailure(InputEnvironmentFailureKind.UNKNOWN_REQUIREMENT, id)
                    return@forEach
                }

                if (requirement.kind == InputKind.RAW_INTERPOLATION) {
                    if (provided.value !is InputValue.RawText) {
                        failures += InputEnvironmentFailure(InputEnvironmentFailureKind.KIND_MISMATCH, id)
                    }
                    if (provided.origin == ExecutionInputOrigin.USER_ACCEPTED_RETAINED) {
                        failures += InputEnvironmentFailure(
                            InputEnvironmentFailureKind.RAW_RETAINED_INPUT_FORBIDDEN,
                            id,
                        )
                    }
                } else if (provided.value is InputValue.RawText) {
                    failures += InputEnvironmentFailure(InputEnvironmentFailureKind.KIND_MISMATCH, id)
                }

                if (provided.value is InputValue.NullValue &&
                    requirement.expectedType.nullability == InputNullability.NON_NULL
                ) {
                    failures += InputEnvironmentFailure(InputEnvironmentFailureKind.NULL_NOT_ALLOWED, id)
                } else if (provided.value !is InputValue.NullValue &&
                    !matchesShape(provided.value, requirement.expectedType.shape)
                ) {
                    failures += InputEnvironmentFailure(InputEnvironmentFailureKind.SHAPE_MISMATCH, id)
                }
            }

            contract.requirements
                .filter { it.requiredness == InputRequiredness.REQUIRED }
                .filterNot { it.id in uniqueInputs }
                .forEach { requirement ->
                    failures += InputEnvironmentFailure(
                        InputEnvironmentFailureKind.MISSING_REQUIRED_INPUT,
                        requirement.id,
                    )
                }

            if (failures.isNotEmpty()) {
                return InputEnvironmentResult.Failure(failures.toList())
            }

            return InputEnvironmentResult.Success(
                InputEnvironment(contract.statementId, uniqueInputs),
            )
        }

        private fun matchesShape(value: InputValue, shape: InputShape): Boolean = when (shape) {
            InputShape.SCALAR -> value is InputValue.Text ||
                value is InputValue.BooleanValue ||
                value is InputValue.IntegerValue ||
                value is InputValue.DecimalValue ||
                value is InputValue.UuidValue
            InputShape.OBJECT -> value is InputValue.ObjectValue
            InputShape.LIST -> value is InputValue.ListValue
            InputShape.ARRAY -> value is InputValue.ArrayValue
            InputShape.MAP -> value is InputValue.MapValue
            InputShape.TEMPORAL -> value is InputValue.DateValue ||
                value is InputValue.TimeValue ||
                value is InputValue.DateTimeValue ||
                value is InputValue.InstantValue
            InputShape.RAW_TEXT -> value is InputValue.RawText
            InputShape.UNKNOWN -> false
        }
    }
}
