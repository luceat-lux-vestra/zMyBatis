package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputProvenance
import com.algorist.zMyBatis.core.input.InternalBinding
import com.algorist.zMyBatis.core.input.InternalBindingKind
import com.algorist.zMyBatis.core.input.SourceEvidence
import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationFailure
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import com.algorist.zMyBatis.core.preparation.PreparedBinding
import com.algorist.zMyBatis.core.preparation.PreparedBindingMetadata
import com.algorist.zMyBatis.core.preparation.PreparedBindingOrigin

/**
 * Captures stock MyBatis parameter mappings without syntax-specific foreach/bind admission.
 */
internal object MyBatisInvocationBindingCapture {
    private const val PROPERTY_MISSING = "mybatis-parameter-mapping-property-missing"
    private const val ALIAS_UNRESOLVED = "mybatis-parameter-mapping-unresolved"
    private const val PARAMETER_MODE_UNSUPPORTED = "mybatis-parameter-mode-unsupported"
    private const val TYPE_HANDLER_UNSUPPORTED = "mybatis-custom-type-handler-unsupported"
    private const val VALUE_UNSUPPORTED = "mybatis-binding-value-unsupported"
    private const val INVARIANT_FAILURE = "mybatis-preparation-invariant-failure"

    sealed interface Result {
        data class Ready(val bindings: List<PreparedBinding>) : Result
        data class Failed(val failure: PreparationFailure) : Result
    }

    fun capture(
        snapshots: List<MyBatisParameterMappingSnapshot>,
        request: MyBatisPreparationRequest,
        payload: MyBatisInvocationParameterObjectFactory.Ready,
    ): Result {
        val rootStatement = request.source.sourceGraph.rootStatement
        val revision = request.sourceRevisions[rootStatement.id.sourceFileId]
            ?: return invariant()
        val rootSource = SourceEvidence(
            rootStatement.id.sourceFileId,
            revision,
            rootStatement.sourceRange,
        )
        val bindings = mutableListOf<PreparedBinding>()

        for ((index, mapping) in snapshots.withIndex()) {
            val property = mapping.property?.takeIf(String::isNotBlank)
                ?: return failed(PreparationFailureKind.PARAMETER_MAPPING_MISMATCH, PROPERTY_MISSING)

            if (mapping.parameterMode != "IN") {
                return failed(
                    PreparationFailureKind.UNSUPPORTED_SEMANTIC,
                    PARAMETER_MODE_UNSUPPORTED,
                    property,
                )
            }

            val runtimeValue = when (val value = mapping.runtimeValue) {
                is MyBatisRuntimeValueSnapshot.Ready -> value.value
                is MyBatisRuntimeValueSnapshot.Failed ->
                    return failed(
                        if (value.bindingFailure) {
                            PreparationFailureKind.BINDING_RESOLUTION
                        } else {
                            PreparationFailureKind.PREPARATION_INVARIANT
                        },
                        if (value.bindingFailure) ALIAS_UNRESOLVED else INVARIANT_FAILURE,
                        property,
                        value.diagnosticType,
                    )
            }

            val rootProperty = property.substringBefore('.')
            // MyBatis 3.6 captures runtime values in ParameterMapping for ordinary caller
            // parameters as well. BoundSql additional parameters, however, are reserved for
            // dynamic-context values such as <bind> and foreach locals.
            val generated = mapping.additionalParameter || mapping.foreachLocal != null
            val callerRequirement = if (generated) {
                null
            } else {
                val requirementId = payload.aliasToRequirementId[rootProperty]
                    ?: payload.directRequirementId
                    ?: return failed(
                        PreparationFailureKind.BINDING_RESOLUTION,
                        ALIAS_UNRESOLVED,
                        property,
                    )
                request.parameterContract.requirement(requirementId) ?: return invariant(property)
            }

            val origin = if (generated) {
                val internalBinding = mapping.foreachLocal?.let { foreachLocal ->
                    request.parameterContract.internalBindings.singleOrNull { candidate ->
                        candidate.name == foreachLocal.sourceLocalName &&
                            candidate.kind == foreachLocal.kind
                    } ?: return invariant(property)
                } ?: InternalBinding(
                    name = rootProperty,
                    kind = InternalBindingKind.ADDITIONAL_PARAMETER,
                    provenance = InputProvenance(
                        listOf(InputEvidence.RuntimeGeneratedBinding(property, rootSource)),
                    ),
                )
                PreparedBindingOrigin.MyBatisAdditional(internalBinding)
            } else {
                PreparedBindingOrigin.CallerInput(
                    requireNotNull(callerRequirement).id,
                    callerRequirement.provenance,
                )
            }

            val typeHandlerName = mapping.typeHandlerIdentity
                ?: return failed(
                    PreparationFailureKind.UNSUPPORTED_TYPE_HANDLER,
                    TYPE_HANDLER_UNSUPPORTED,
                    property,
                )
            if (!typeHandlerName.startsWith("org.apache.ibatis.type.")) {
                return failed(
                    PreparationFailureKind.UNSUPPORTED_TYPE_HANDLER,
                    TYPE_HANDLER_UNSUPPORTED,
                    property,
                    typeHandlerName,
                )
            }

            val alias = payload.aliasToRequirementId[rootProperty]?.let { requirementId ->
                request.parameterContract.aliases.singleOrNull {
                    it.name == rootProperty && it.requirementId == requirementId
                }
            }
            val coreValue = MyBatisValueConversion.toCoreValue(
                runtimeValue,
                callerRequirement,
                property,
                alias,
            ) ?: return failed(
                PreparationFailureKind.UNSUPPORTED_BINDING_VALUE,
                VALUE_UNSUPPORTED,
                property,
                runtimeValue?.javaClass?.name,
            )

            bindings += PreparedBinding(
                index = index,
                property = property,
                value = coreValue,
                origin = origin,
                metadata = PreparedBindingMetadata(
                    declaredJavaTypeIdentity = callerRequirement?.expectedType?.javaTypeIdentity,
                    mappingJavaTypeIdentity = mapping.mappingJavaTypeIdentity,
                    jdbcTypeIdentity = mapping.jdbcTypeIdentity,
                    effectiveJdbcTypeIdentity = mapping.effectiveJdbcTypeIdentity,
                    typeHandlerIdentity = typeHandlerName,
                    parameterMode = mapping.parameterMode,
                    numericScale = mapping.numericScale,
                ),
            )
        }

        return Result.Ready(bindings)
    }

    private fun invariant(property: String? = null): Result.Failed =
        failed(PreparationFailureKind.PREPARATION_INVARIANT, INVARIANT_FAILURE, property)

    private fun failed(
        kind: PreparationFailureKind,
        code: String,
        property: String? = null,
        diagnosticType: String? = null,
    ): Result.Failed = Result.Failed(
        PreparationFailure(
            kind = kind,
            code = code,
            bindingProperty = property,
            diagnosticType = diagnosticType,
        ),
    )
}
