package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationFailure
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import com.algorist.zMyBatis.core.preparation.PreparedBinding
import com.algorist.zMyBatis.core.preparation.PreparedBindingMetadata
import com.algorist.zMyBatis.core.preparation.PreparedBindingOrigin

/** Captures only direct generated locals proved by both the XML admission and stock MyBatis. */
internal object XmlForeachAdditionalBindingCapture {
    fun capture(
        index: Int,
        mapping: MyBatisParameterMappingSnapshot,
        request: MyBatisPreparationRequest,
        admission: XmlForeachPreparationAdmission.Result.Admitted,
    ): Result {
        val property = mapping.property?.takeIf { it.isNotBlank() }
            ?: return failed(PreparationFailureKind.PARAMETER_MAPPING_MISMATCH, "xml-foreach-mapping-property-missing")
        val generated = mapping.generatedLocal
        if (
            !mapping.additionalParameter || generated == null ||
            '.' in property || '[' in property || ']' in property ||
            admission.locals[generated.sourceLocalName] != generated.kind
        ) {
            return failed(PreparationFailureKind.BINDING_RESOLUTION, "xml-foreach-additional-authority-unproven", property)
        }
        val binding = request.parameterContract.internalBindings.singleOrNull {
            it.name == generated.sourceLocalName && it.kind == generated.kind
        } ?: return failed(PreparationFailureKind.BINDING_RESOLUTION, "xml-foreach-additional-authority-unproven", property)
        if (mapping.parameterMode != "IN") {
            return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, "xml-preparation-parameter-mode-unsupported", property)
        }
        val handler = mapping.typeHandlerIdentity?.takeIf { it.startsWith("org.apache.ibatis.type.") }
            ?: return failed(PreparationFailureKind.UNSUPPORTED_TYPE_HANDLER, "xml-preparation-type-handler-unsupported", property)
        val runtimeValue = when (val value = mapping.runtimeValue) {
            is MyBatisRuntimeValueSnapshot.Ready -> value.value
            is MyBatisRuntimeValueSnapshot.Failed -> return Result.Failed(
                PreparationFailure(
                    if (value.bindingFailure) PreparationFailureKind.BINDING_RESOLUTION else PreparationFailureKind.UNSUPPORTED_BINDING_VALUE,
                    "xml-foreach-additional-value-unavailable",
                    property,
                    value.diagnosticType,
                ),
            )
        }
        val value = MyBatisValueConversion.toCoreValue(runtimeValue, null, property, null)
            ?: return failed(PreparationFailureKind.UNSUPPORTED_BINDING_VALUE, "xml-preparation-binding-value-unsupported", property)
        return Result.Ready(
            PreparedBinding(
                index = index,
                property = property,
                value = value,
                origin = PreparedBindingOrigin.MyBatisAdditional(binding),
                metadata = PreparedBindingMetadata(
                    declaredJavaTypeIdentity = null,
                    mappingJavaTypeIdentity = mapping.mappingJavaTypeIdentity,
                    jdbcTypeIdentity = mapping.jdbcTypeIdentity,
                    typeHandlerIdentity = handler,
                    parameterMode = mapping.parameterMode,
                    numericScale = mapping.numericScale,
                ),
            ),
        )
    }

    private fun failed(kind: PreparationFailureKind, code: String, property: String? = null) =
        Result.Failed(PreparationFailure(kind, code, property))

    sealed interface Result {
        data class Ready(val binding: PreparedBinding) : Result
        data class Failed(val failure: PreparationFailure) : Result
    }
}
