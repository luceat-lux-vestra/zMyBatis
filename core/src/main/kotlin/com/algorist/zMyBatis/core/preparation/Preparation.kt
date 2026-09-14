package com.algorist.zMyBatis.core.preparation

import com.algorist.zMyBatis.core.input.ExecutionInputOrigin
import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.InputEnvironmentResult
import com.algorist.zMyBatis.core.input.InputKind
import com.algorist.zMyBatis.core.input.InputProvenance
import com.algorist.zMyBatis.core.input.InputRequirementId
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.source.JavaAnnotationStatementCapture
import com.algorist.zMyBatis.core.source.JavaTypeIdentity
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.StatementId
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.StatementSourceGraph

sealed interface PreparationSource {
    val sourceGraph: StatementSourceGraph

    data class JavaAnnotation(
        val capture: JavaAnnotationStatementCapture,
    ) : PreparationSource {
        override val sourceGraph: StatementSourceGraph
            get() = capture.sourceGraph
    }
}

enum class PreparationFailureKind {
    INPUT_CONTRACT_BLOCKED,
    INPUT_ENVIRONMENT_MISMATCH,
    STATEMENT_ID_MISMATCH,
    SOURCE_REVISION_MISMATCH,
    UNSUPPORTED_SEMANTIC,
    MYBATIS_PARSE,
    OGNL,
    BINDING_RESOLUTION,
    UNSUPPORTED_BINDING_VALUE,
    UNSUPPORTED_TYPE_HANDLER,
    PARAMETER_MAPPING_MISMATCH,
    PREPARATION_INVARIANT,
}

data class PreparationFailure(
    val kind: PreparationFailureKind,
    val code: String,
    val bindingProperty: String? = null,
    val diagnosticType: String? = null,
) {
    init {
        require(code.isNotBlank()) { "preparation failure code must not be blank" }
        require(bindingProperty == null || bindingProperty.isNotBlank()) {
            "binding property must be null or non-blank"
        }
        require(diagnosticType == null || diagnosticType.isNotBlank()) {
            "diagnostic type must be null or non-blank"
        }
    }
}

sealed interface PreparationRequestResult {
    data class Ready(val request: MyBatisPreparationRequest) : PreparationRequestResult
    data class Failed(val failure: PreparationFailure) : PreparationRequestResult
}

class MyBatisPreparationRequest private constructor(
    val source: PreparationSource,
    val parameterContract: ParameterContract,
    val inputEnvironment: InputEnvironment,
) {
    val statementId: StatementId
        get() = source.sourceGraph.rootStatement.id

    val statementKind: StatementKind
        get() = source.sourceGraph.rootStatement.kind

    val sourceRevisions: Map<SourceFileId, SourceRevision>
        get() = source.sourceGraph.sourceSnapshots.associate { it.fileId to it.revision }

    companion object {
        private const val BLOCKED_CONTRACT = "preparation-input-contract-blocked"
        private const val STATEMENT_DRIFT = "preparation-statement-identity-drift"
        private const val REVISION_DRIFT = "preparation-source-revision-drift"
        private const val ENVIRONMENT_DRIFT = "preparation-input-environment-drift"
        private const val JAVA_DEPENDENCY_UNSUPPORTED = "java-annotation-preparation-dependency-unsupported"
        private const val JAVA_DYNAMIC_SCRIPT_UNSUPPORTED = "java-annotation-dynamic-script-preparation-unsupported"

        fun create(
            source: PreparationSource,
            parameterContract: ParameterContract,
            inputEnvironment: InputEnvironment,
        ): PreparationRequestResult {
            if (parameterContract.isPreparationBlocked) {
                return PreparationRequestResult.Failed(
                    PreparationFailure(PreparationFailureKind.INPUT_CONTRACT_BLOCKED, BLOCKED_CONTRACT),
                )
            }

            val statementId = source.sourceGraph.rootStatement.id
            if (parameterContract.statementId != statementId || inputEnvironment.statementId != statementId) {
                return PreparationRequestResult.Failed(
                    PreparationFailure(PreparationFailureKind.STATEMENT_ID_MISMATCH, STATEMENT_DRIFT),
                )
            }

            val sourceRevisions = source.sourceGraph.sourceSnapshots.associate { it.fileId to it.revision }
            if (
                parameterContract.sourceRevisions != sourceRevisions ||
                inputEnvironment.sourceRevisions != sourceRevisions
            ) {
                return PreparationRequestResult.Failed(
                    PreparationFailure(PreparationFailureKind.SOURCE_REVISION_MISMATCH, REVISION_DRIFT),
                )
            }

            val environmentValidation = InputEnvironment.validate(
                parameterContract,
                inputEnvironment.values.values.toList(),
            )
            if (
                environmentValidation !is InputEnvironmentResult.Success ||
                environmentValidation.environment.statementId != inputEnvironment.statementId ||
                environmentValidation.environment.sourceRevisions != inputEnvironment.sourceRevisions ||
                environmentValidation.environment.aliases != inputEnvironment.aliases ||
                environmentValidation.environment.values != inputEnvironment.values
            ) {
                return PreparationRequestResult.Failed(
                    PreparationFailure(PreparationFailureKind.INPUT_ENVIRONMENT_MISMATCH, ENVIRONMENT_DRIFT),
                )
            }

            when (source) {
                is PreparationSource.JavaAnnotation -> {
                    if (source.sourceGraph.dependencies.isNotEmpty()) {
                        return PreparationRequestResult.Failed(
                            PreparationFailure(
                                PreparationFailureKind.UNSUPPORTED_SEMANTIC,
                                JAVA_DEPENDENCY_UNSUPPORTED,
                            ),
                        )
                    }
                    if (source.capture.sqlSegments.any(::containsDynamicScript)) {
                        return PreparationRequestResult.Failed(
                            PreparationFailure(
                                PreparationFailureKind.UNSUPPORTED_SEMANTIC,
                                JAVA_DYNAMIC_SCRIPT_UNSUPPORTED,
                            ),
                        )
                    }
                }
            }

            return PreparationRequestResult.Ready(
                MyBatisPreparationRequest(source, parameterContract, inputEnvironment),
            )
        }

        private fun containsDynamicScript(sql: String): Boolean = sql.indexOf("<script", ignoreCase = true) >= 0
    }
}

data class PreparedBindingMetadata(
    val declaredJavaTypeIdentity: JavaTypeIdentity?,
    val mappingJavaTypeIdentity: String?,
    val jdbcTypeIdentity: String?,
    val typeHandlerIdentity: String,
    val parameterMode: String,
    val numericScale: Int?,
    val additionalParameter: Boolean,
) {
    init {
        require(mappingJavaTypeIdentity == null || mappingJavaTypeIdentity.isNotBlank()) {
            "mapping Java type identity must be null or non-blank"
        }
        require(jdbcTypeIdentity == null || jdbcTypeIdentity.isNotBlank()) {
            "JDBC type identity must be null or non-blank"
        }
        require(typeHandlerIdentity.isNotBlank()) { "type handler identity must not be blank" }
        require(parameterMode.isNotBlank()) { "parameter mode must not be blank" }
    }
}

data class PreparedBinding(
    val index: Int,
    val property: String,
    val requirementId: InputRequirementId?,
    val value: InputValue,
    val provenance: InputProvenance?,
    val metadata: PreparedBindingMetadata,
) {
    init {
        require(index >= 0) { "prepared binding index must not be negative" }
        require(property.isNotBlank()) { "prepared binding property must not be blank" }
        require(value !is InputValue.RawText) { "raw interpolation cannot become a bound parameter value" }
        require(requirementId == null || provenance != null) {
            "caller-backed prepared bindings must preserve input provenance"
        }
    }
}

data class PreparedRawInterpolation(
    val requirementId: InputRequirementId,
    val provenance: InputProvenance,
    val origin: ExecutionInputOrigin,
)

data class PreparationMetadata(
    val engineIdentity: String,
    val engineVersion: String,
    val languageDriverIdentity: String,
) {
    init {
        require(engineIdentity.isNotBlank()) { "preparation engine identity must not be blank" }
        require(engineVersion.isNotBlank()) { "preparation engine version must not be blank" }
        require(languageDriverIdentity.isNotBlank()) { "language driver identity must not be blank" }
    }
}

class PreparedExecution(
    val statementId: StatementId,
    val statementKind: StatementKind,
    sourceRevisions: Map<SourceFileId, SourceRevision>,
    val sqlWithPlaceholders: String,
    orderedBindings: List<PreparedBinding>,
    rawInterpolations: List<PreparedRawInterpolation>,
    val preparationMetadata: PreparationMetadata,
) {
    private val sourceRevisionSnapshot = LinkedHashMap(
        sourceRevisions.entries.sortedBy { it.key.value }.associate { it.toPair() },
    )
    private val bindingSnapshot = orderedBindings.toList()
    private val rawInterpolationSnapshot = rawInterpolations.toList()

    init {
        require(statementId.sourceFileId in sourceRevisionSnapshot) {
            "prepared execution must include its root source revision"
        }
        require(sqlWithPlaceholders.isNotBlank()) { "prepared SQL must not be blank" }
        require(bindingSnapshot.indices.all { index -> bindingSnapshot[index].index == index }) {
            "prepared bindings must be contiguous and ordered"
        }
        require(rawInterpolationSnapshot.map { it.requirementId }.distinct().size == rawInterpolationSnapshot.size) {
            "raw interpolation requirements must not be duplicated"
        }
        val rawRequirementIds = rawInterpolationSnapshot.mapTo(linkedSetOf()) { it.requirementId }
        require(bindingSnapshot.none { it.requirementId in rawRequirementIds }) {
            "one input requirement cannot be both raw interpolation and a bound mapping"
        }
    }

    val sourceRevisions: Map<SourceFileId, SourceRevision>
        get() = LinkedHashMap(sourceRevisionSnapshot)

    val orderedBindings: List<PreparedBinding>
        get() = bindingSnapshot.toList()

    val rawInterpolations: List<PreparedRawInterpolation>
        get() = rawInterpolationSnapshot.toList()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PreparedExecution) return false

        return statementId == other.statementId &&
            statementKind == other.statementKind &&
            sourceRevisionSnapshot == other.sourceRevisionSnapshot &&
            sqlWithPlaceholders == other.sqlWithPlaceholders &&
            bindingSnapshot == other.bindingSnapshot &&
            rawInterpolationSnapshot == other.rawInterpolationSnapshot &&
            preparationMetadata == other.preparationMetadata
    }

    override fun hashCode(): Int {
        var result = statementId.hashCode()
        result = 31 * result + statementKind.hashCode()
        result = 31 * result + sourceRevisionSnapshot.hashCode()
        result = 31 * result + sqlWithPlaceholders.hashCode()
        result = 31 * result + bindingSnapshot.hashCode()
        result = 31 * result + rawInterpolationSnapshot.hashCode()
        result = 31 * result + preparationMetadata.hashCode()
        return result
    }
}

sealed interface PreparationResult {
    data class Success(val execution: PreparedExecution) : PreparationResult
    data class Failed(val failure: PreparationFailure) : PreparationResult
}

fun ParameterContract.rawRequirements() = requirements.filter { it.kind == InputKind.RAW_INTERPOLATION }
