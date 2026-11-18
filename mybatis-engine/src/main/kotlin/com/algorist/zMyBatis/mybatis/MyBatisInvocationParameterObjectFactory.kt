package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.InputRequirementId
import com.algorist.zMyBatis.core.input.InputShape
import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationFailure
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import com.algorist.zMyBatis.core.preparation.PreparationSource
import com.algorist.zMyBatis.core.preparation.XmlMapperPreparationSource
import com.algorist.zMyBatis.core.source.JavaMethodParameterMetadata
import org.apache.ibatis.binding.MapperMethod

/**
 * Builds the invocation object MyBatis receives from caller authority only.
 *
 * Dynamic SQL structure never participates in this decision. The output mirrors the relevant
 * ParamNameResolver rules while remaining usable by the isolated XML MyBatis runtime through the
 * bootstrap java.util.Map interface.
 */
internal object MyBatisInvocationParameterObjectFactory {
    private const val VALUE_MISSING = "invocation-parameter-value-missing"
    private const val VALUE_UNSUPPORTED = "invocation-parameter-value-unsupported"
    private const val CONTRACT_INVALID = "invocation-parameter-contract-invalid"
    private const val XML_PARAMETER_OBJECT_ID = "xml-parameter-object"
    private const val ROW_BOUNDS = "org.apache.ibatis.session.RowBounds"
    private const val RESULT_HANDLER = "org.apache.ibatis.session.ResultHandler"

    data class Ready(
        val parameterObject: Any?,
        val parameterType: MyBatisParameterType,
        val aliasToRequirementId: Map<String, InputRequirementId>,
        val directRequirementId: InputRequirementId?,
    )

    sealed interface Result {
        data class Success(val payload: Ready) : Result
        data class Failed(val failure: PreparationFailure) : Result
    }

    fun create(request: MyBatisPreparationRequest): Result = when (val source = request.source) {
        is PreparationSource.JavaAnnotation ->
            fromMapperMethod(request, source.capture.parameters)
        is XmlMapperPreparationSource ->
            source.mapperMethod?.let { mapper ->
                fromMapperMethod(request, mapper.parameters)
            } ?: fromXmlOnly(request)
    }

    private fun fromXmlOnly(request: MyBatisPreparationRequest): Result {
        val requirement = request.parameterContract.requirements.singleOrNull()
        if (requirement == null) {
            if (request.parameterContract.requirements.isNotEmpty()) return invalid()
            return Result.Success(Ready(null, MyBatisParameterType.None, emptyMap(), null))
        }
        if (
            requirement.id.value != XML_PARAMETER_OBJECT_ID ||
            requirement.expectedType.shape != InputShape.OBJECT ||
            request.parameterContract.aliases.isNotEmpty()
        ) {
            return invalid()
        }

        val provided = request.inputEnvironment.value(requirement.id)
            ?: return Result.Success(
                Ready(
                    parameterObject = linkedMapOf<String, Any?>(),
                    parameterType = MyBatisParameterType.Single(Map::class.java),
                    aliasToRequirementId = emptyMap(),
                    directRequirementId = requirement.id,
                ),
            )

        val converted = MyBatisValueConversion.invocationRuntimeValue(
            provided.value,
            requirement.expectedType,
        )
        return when (converted) {
            is MyBatisValueConversion.RuntimeValueResult.Ready -> {
                val parameterObject = converted.value
                if (parameterObject !is Map<*, *>) {
                    failed(VALUE_UNSUPPORTED)
                } else {
                    Result.Success(
                        Ready(
                            parameterObject = parameterObject,
                            parameterType = MyBatisParameterType.Single(Map::class.java),
                            aliasToRequirementId = emptyMap(),
                            directRequirementId = requirement.id,
                        ),
                    )
                }
            }
            is MyBatisValueConversion.RuntimeValueResult.Failed -> Result.Failed(converted.failure)
        }
    }

    private fun fromMapperMethod(
        request: MyBatisPreparationRequest,
        declaredParameters: List<JavaMethodParameterMetadata>,
    ): Result {
        val parameters = declaredParameters.filterNot(::isSpecialParameter)
        val requirementsByIndex = request.parameterContract.requirements
            .associateBy { requirement ->
                requirement.provenance.evidence
                    .filterIsInstance<com.algorist.zMyBatis.core.input.InputEvidence.MapperMethodParameter>()
                    .map { it.index }
                    .distinct()
                    .singleOrNull()
                    ?: return invalid()
            }
        if (requirementsByIndex.size != parameters.size) return invalid()

        val values = linkedMapOf<Int, Any?>()
        for ((index) in parameters) {
            val requirement = requirementsByIndex[index] ?: return invalid()
            val provided = request.inputEnvironment.value(requirement.id)
                ?: return failed(VALUE_MISSING)
            when (
                val converted = MyBatisValueConversion.invocationRuntimeValue(
                    provided.value,
                    requirement.expectedType,
                )
            ) {
                is MyBatisValueConversion.RuntimeValueResult.Ready ->
                    values[index] = converted.value
                is MyBatisValueConversion.RuntimeValueResult.Failed ->
                    return Result.Failed(converted.failure)
            }
        }

        val resolvedParameterType = MyBatisValueConversion.resolveParameterType(
            parameters.map { it.typeIdentity },
        )
        val hasExplicitParam = parameters.any { it.myBatisParamAlias != null }
        if (parameters.size == 1 && !hasExplicitParam) {
            val parameter = parameters.single()
            val requirement = requirementsByIndex.getValue(parameter.index)
            val value = values.getValue(parameter.index)
            val rawType = parameter.typeIdentity.value.substringBefore('<').trim()
            val wrapper = when {
                parameter.typeIdentity.value.trim().endsWith("[]") ->
                    linkedMapOf("array" to value)
                rawType == "java.util.List" ->
                    linkedMapOf("collection" to value, "list" to value)
                rawType == "java.util.Collection" ->
                    linkedMapOf("collection" to value)
                else -> null
            }
            if (wrapper == null) {
                return Result.Success(
                    Ready(
                        parameterObject = value,
                        parameterType = resolvedParameterType
                            ?: MyBatisParameterType.Single(Map::class.java),
                        aliasToRequirementId = emptyMap(),
                        directRequirementId = requirement.id,
                    ),
                )
            }
            parameter.sourceName?.let { wrapper.putIfAbsent(it, value) }
            return Result.Success(
                Ready(
                    parameterObject = wrapper,
                    parameterType = resolvedParameterType
                        ?: MyBatisParameterType.Single(Map::class.java),
                    aliasToRequirementId = wrapper.keys.associateWith { requirement.id },
                    directRequirementId = null,
                ),
            )
        }

        val primaryNames = parameters.associateWith { parameter ->
            parameter.myBatisParamAlias ?: parameter.sourceName ?: parameter.index.toString()
        }
        val reservedPrimary = primaryNames.values.toSet()
        val parameterMap = MapperMethod.ParamMap<Any?>()
        val aliases = linkedMapOf<String, InputRequirementId>()
        parameters.forEachIndexed { ordinal, parameter ->
            val requirement = requirementsByIndex.getValue(parameter.index)
            val value = values.getValue(parameter.index)
            val primary = primaryNames.getValue(parameter)
            parameterMap[primary] = value
            aliases[primary] = requirement.id

            val generic = "param${ordinal + 1}"
            if (generic !in reservedPrimary) {
                parameterMap[generic] = value
                aliases[generic] = requirement.id
            }
        }
        return Result.Success(
            Ready(
                parameterObject = parameterMap,
                parameterType = MyBatisParameterType.Multi,
                aliasToRequirementId = aliases,
                directRequirementId = null,
            ),
        )
    }

    private fun isSpecialParameter(parameter: JavaMethodParameterMetadata): Boolean =
        parameter.typeIdentity.value.substringBefore('<').trim() in setOf(ROW_BOUNDS, RESULT_HANDLER)

    private fun invalid(): Result.Failed = failed(CONTRACT_INVALID)

    private fun failed(code: String): Result.Failed = Result.Failed(
        PreparationFailure(
            PreparationFailureKind.INPUT_ENVIRONMENT_MISMATCH,
            code,
        ),
    )
}
