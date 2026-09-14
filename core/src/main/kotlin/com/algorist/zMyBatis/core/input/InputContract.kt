package com.algorist.zMyBatis.core.input

import com.algorist.zMyBatis.core.source.JavaTypeIdentity
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.StatementId

@JvmInline
value class InputRequirementId(val value: String) {
    init {
        require(value.isNotBlank()) { "input requirement id must not be blank" }
    }
}

enum class InputKind {
    BOUND,
    RAW_INTERPOLATION,
}

enum class InputRequiredness {
    REQUIRED,
    OPTIONAL,
}

enum class InputShape {
    SCALAR,
    OBJECT,
    LIST,
    ARRAY,
    MAP,
    TEMPORAL,
    RAW_TEXT,
    UNKNOWN,
}

enum class InputScalarType {
    STRING,
    BOOLEAN,
    INTEGER,
    DECIMAL,
    DATE,
    TIME,
    DATE_TIME,
    INSTANT,
    UUID,
    UNKNOWN,
}

enum class InputNullability {
    NON_NULL,
    NULLABLE,
    UNKNOWN,
}

data class ExpectedInputType(
    val shape: InputShape,
    val scalarType: InputScalarType = InputScalarType.UNKNOWN,
    val javaTypeIdentity: JavaTypeIdentity? = null,
    val nullability: InputNullability = InputNullability.UNKNOWN,
) {
    init {
        val temporalTypes = setOf(
            InputScalarType.DATE,
            InputScalarType.TIME,
            InputScalarType.DATE_TIME,
            InputScalarType.INSTANT,
        )
        when (shape) {
            InputShape.SCALAR -> require(scalarType !in temporalTypes) {
                "temporal scalar types require TEMPORAL shape"
            }
            InputShape.TEMPORAL -> require(scalarType in temporalTypes || scalarType == InputScalarType.UNKNOWN) {
                "TEMPORAL shape requires a temporal scalar type"
            }
            InputShape.RAW_TEXT -> require(scalarType in setOf(InputScalarType.STRING, InputScalarType.UNKNOWN)) {
                "raw text input cannot declare a non-string scalar type"
            }
            InputShape.OBJECT,
            InputShape.LIST,
            InputShape.ARRAY,
            InputShape.MAP,
            InputShape.UNKNOWN,
            -> require(scalarType == InputScalarType.UNKNOWN) {
                "structured or unknown input shapes cannot declare a scalar type"
            }
        }
    }
}

data class SourceEvidence(
    val sourceFileId: SourceFileId,
    val sourceRange: SourceRange?,
)

sealed interface InputEvidence {
    data class MapperMethodParameter(
        val index: Int,
        val sourceName: String?,
        val typeIdentity: JavaTypeIdentity,
        val source: SourceEvidence,
    ) : InputEvidence {
        init {
            require(index >= 0) { "mapper method parameter index must not be negative" }
            require(sourceName == null || sourceName.isNotBlank()) {
                "mapper method parameter source name must be null or non-blank"
            }
        }
    }

    data class ExplicitParamAlias(
        val parameterIndex: Int,
        val alias: String,
        val source: SourceEvidence,
    ) : InputEvidence {
        init {
            require(parameterIndex >= 0) { "@Param parameter index must not be negative" }
            require(alias.isNotBlank()) { "@Param alias must not be blank" }
        }
    }

    data class GeneratedAlias(
        val parameterIndex: Int,
        val alias: String,
        val ruleId: String,
    ) : InputEvidence {
        init {
            require(parameterIndex >= 0) { "generated alias parameter index must not be negative" }
            require(alias.isNotBlank()) { "generated alias must not be blank" }
            require(ruleId.isNotBlank()) { "generated alias rule id must not be blank" }
        }
    }

    data class Placeholder(
        val kind: InputKind,
        val expression: String,
        val source: SourceEvidence,
    ) : InputEvidence {
        init {
            require(expression.isNotBlank()) { "placeholder expression must not be blank" }
        }
    }

    data class OgnlExpression(
        val expression: String,
        val source: SourceEvidence,
    ) : InputEvidence {
        init {
            require(expression.isNotBlank()) { "OGNL expression must not be blank" }
        }
    }

    data class ForeachCollection(
        val expression: String,
        val source: SourceEvidence,
    ) : InputEvidence {
        init {
            require(expression.isNotBlank()) { "foreach collection expression must not be blank" }
        }
    }

    data class ForeachLocal(
        val name: String,
        val role: ForeachLocalRole,
        val source: SourceEvidence,
    ) : InputEvidence {
        init {
            require(name.isNotBlank()) { "foreach local name must not be blank" }
        }
    }

    data class BindLocal(
        val name: String,
        val expression: String,
        val source: SourceEvidence,
    ) : InputEvidence {
        init {
            require(name.isNotBlank()) { "bind local name must not be blank" }
            require(expression.isNotBlank()) { "bind expression must not be blank" }
        }
    }
}

enum class ForeachLocalRole {
    ITEM,
    INDEX,
}

class InputProvenance(evidence: List<InputEvidence>) {
    private val evidenceSnapshot = evidence.toList()

    init {
        require(evidenceSnapshot.isNotEmpty()) { "input provenance must contain evidence" }
    }

    val evidence: List<InputEvidence>
        get() = evidenceSnapshot.toList()

    override fun equals(other: Any?): Boolean =
        this === other || (other is InputProvenance && evidenceSnapshot == other.evidenceSnapshot)

    override fun hashCode(): Int = evidenceSnapshot.hashCode()
}

data class InputRequirement(
    val id: InputRequirementId,
    val kind: InputKind,
    val expectedType: ExpectedInputType,
    val requiredness: InputRequiredness,
    val provenance: InputProvenance,
) {
    init {
        require((kind == InputKind.RAW_INTERPOLATION) == (expectedType.shape == InputShape.RAW_TEXT)) {
            "RAW_TEXT shape and RAW_INTERPOLATION kind must be used together"
        }
    }
}

enum class InternalBindingKind {
    MYBATIS_CONTEXT,
    GENERATED_ALIAS,
    FOREACH_ITEM,
    FOREACH_INDEX,
    BIND,
    ADDITIONAL_PARAMETER,
}

data class InternalBinding(
    val name: String,
    val kind: InternalBindingKind,
    val provenance: InputProvenance,
) {
    init {
        require(name.isNotBlank()) { "internal binding name must not be blank" }
    }
}

enum class InputContractProblemKind {
    UNKNOWN,
    AMBIGUOUS,
    UNSUPPORTED,
}

data class InputContractProblem(
    val kind: InputContractProblemKind,
    val code: String,
    val requirementId: InputRequirementId?,
    val provenance: InputProvenance?,
) {
    init {
        require(code.isNotBlank()) { "input contract problem code must not be blank" }
    }
}

class ParameterContract(
    val statementId: StatementId,
    requirements: List<InputRequirement>,
    internalBindings: List<InternalBinding>,
    blockingProblems: List<InputContractProblem>,
) {
    private val requirementSnapshot = requirements.toList()
    private val internalBindingSnapshot = internalBindings.toList()
    private val problemSnapshot = blockingProblems.toList()

    init {
        require(requirementSnapshot.map { it.id }.distinct().size == requirementSnapshot.size) {
            "parameter contract must not contain duplicate requirement ids"
        }
        require(internalBindingSnapshot.map { it.name to it.kind }.distinct().size == internalBindingSnapshot.size) {
            "parameter contract must not contain duplicate internal bindings of the same kind"
        }
        val requirementIds = requirementSnapshot.map { it.id }.toSet()
        require(problemSnapshot.all { it.requirementId == null || it.requirementId in requirementIds }) {
            "input contract problem must reference an existing requirement or the contract as a whole"
        }
        require(
            requirementSnapshot
                .filter { it.expectedType.shape == InputShape.UNKNOWN }
                .all { requirement -> problemSnapshot.any { it.requirementId == requirement.id } },
        ) {
            "unknown input shape must have an explicit blocking problem"
        }
    }

    val requirements: List<InputRequirement>
        get() = requirementSnapshot.toList()

    val internalBindings: List<InternalBinding>
        get() = internalBindingSnapshot.toList()

    val blockingProblems: List<InputContractProblem>
        get() = problemSnapshot.toList()

    val isPreparationBlocked: Boolean
        get() = problemSnapshot.isNotEmpty()

    fun requirement(id: InputRequirementId): InputRequirement? = requirementSnapshot.firstOrNull { it.id == id }
}
