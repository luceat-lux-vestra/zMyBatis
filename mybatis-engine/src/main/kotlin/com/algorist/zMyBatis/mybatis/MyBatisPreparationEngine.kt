package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.InputKind
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.input.InternalBindingKind
import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationFailure
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import com.algorist.zMyBatis.core.preparation.PreparationMetadata
import com.algorist.zMyBatis.core.preparation.PreparationResult
import com.algorist.zMyBatis.core.preparation.PreparationSource
import com.algorist.zMyBatis.core.preparation.PreparedBinding
import com.algorist.zMyBatis.core.preparation.PreparedBindingMetadata
import com.algorist.zMyBatis.core.preparation.PreparedBindingOrigin
import com.algorist.zMyBatis.core.preparation.PreparedExecution
import com.algorist.zMyBatis.core.preparation.PreparedRawInterpolation
import com.algorist.zMyBatis.core.preparation.rawRequirements
import org.apache.ibatis.binding.BindingException
import org.apache.ibatis.binding.MapperMethod
import org.apache.ibatis.builder.BuilderException
import org.apache.ibatis.mapping.BoundSql
import org.apache.ibatis.mapping.ParameterMapping
import org.apache.ibatis.reflection.ReflectionException
import org.apache.ibatis.session.Configuration

object MyBatisPreparationEngine {
    private const val ENGINE_ID = "org.mybatis:mybatis"
    private const val ENGINE_VERSION = "3.5.19"
    private const val UNSUPPORTED_SOURCE = "preparation-source-kind-unsupported"
    private const val UNSUPPORTED_PARAMETER_TYPE = "java-annotation-parameter-type-unavailable"
    private const val UNSUPPORTED_PARAMETER_MODE = "mybatis-parameter-mode-unsupported"
    private const val UNSUPPORTED_TYPE_HANDLER = "mybatis-custom-type-handler-unsupported"
    private const val EXPLICIT_JAVA_TYPE = "mybatis-explicit-java-type-unsupported"
    private const val DYNAMIC_RAW_INTERPOLATION = "java-annotation-dynamic-raw-interpolation-unsupported"
    private const val DYNAMIC_NUMERIC_CHARACTER_REFERENCE =
        "java-annotation-dynamic-numeric-character-reference-unsupported"
    private const val DYNAMIC_OGNL_UNSUPPORTED = "java-annotation-dynamic-ognl-node-unsupported"
    private const val DYNAMIC_OGNL_PROPERTY_UNPROVEN = "java-annotation-dynamic-ognl-property-unproven"
    private const val DYNAMIC_OGNL_PARSE_FAILURE = "java-annotation-dynamic-ognl-parse-failure"
    private const val BIND_SOURCE_PROVENANCE_MISMATCH = "mybatis-bind-source-provenance-mismatch"
    private const val MISSING_MAPPING_PROPERTY = "mybatis-parameter-mapping-property-missing"
    private const val UNRESOLVED_MAPPING = "mybatis-parameter-mapping-unresolved"
    private const val ADDITIONAL_PROVENANCE_MISSING = "mybatis-additional-parameter-provenance-missing"
    private const val ADDITIONAL_PROVENANCE_AMBIGUOUS = "mybatis-additional-parameter-provenance-ambiguous"
    private const val ADDITIONAL_KIND_UNSUPPORTED = "mybatis-additional-parameter-kind-unsupported"
    private const val RAW_INPUT_MISSING = "raw-interpolation-input-missing"
    private const val RAW_BOUND_CONFLATION = "raw-interpolation-bound-mapping-conflation"
    private const val UNSUPPORTED_VALUE = "mybatis-binding-value-unsupported"
    private const val EMPTY_SQL = "mybatis-prepared-sql-empty"
    private const val PARSE_FAILURE = "mybatis-sql-source-parse-failure"
    private const val OGNL_FAILURE = "mybatis-ognl-evaluation-failure"
    private const val BINDING_FAILURE = "mybatis-binding-resolution-failure"
    private const val INVARIANT_FAILURE = "mybatis-preparation-invariant-failure"

    private val rawInterpolationPrefix = String(charArrayOf('$', '{'))
    private val explicitRuntimeClassOption = Regex(
        pattern = "#\\{[^}]*\\b(typeHandler|javaType)\\s*=",
        option = RegexOption.IGNORE_CASE,
    )

    fun prepare(request: MyBatisPreparationRequest): PreparationResult {
        val source = request.source as? PreparationSource.JavaAnnotation
            ?: return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, UNSUPPORTED_SOURCE)

        val script = source.capture.sqlSegments.joinToString(separator = " ").trim()
        val dynamicScript = containsDynamicScript(script)
        if (dynamicScript && script.contains("&#")) {
            return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, DYNAMIC_NUMERIC_CHARACTER_REFERENCE)
        }
        if (dynamicScript && script.contains(rawInterpolationPrefix)) {
            return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, DYNAMIC_RAW_INTERPOLATION)
        }

        val explicitClassOption = runtimeClassOption(script)
        if (explicitClassOption != null) {
            return if (explicitClassOption.equals("typeHandler", ignoreCase = true)) {
                failed(PreparationFailureKind.UNSUPPORTED_TYPE_HANDLER, UNSUPPORTED_TYPE_HANDLER)
            } else {
                failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, EXPLICIT_JAVA_TYPE)
            }
        }

        if (!dynamicScript) {
            val staticRawFailure = StaticRawSubstitutionAdmission.failureOrNull(script, request)
            if (staticRawFailure != null) return PreparationResult.Failed(staticRawFailure)
        }

        if (dynamicScript) {
            when (
                val admission = DynamicOgnlAdmission.inspect(
                    script,
                    dynamicOgnlRootProperties(request),
                    request.parameterContract.internalBindings,
                )
            ) {
                DynamicOgnlAdmission.Result.Admitted -> Unit
                is DynamicOgnlAdmission.Result.Unsupported ->
                    return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, DYNAMIC_OGNL_UNSUPPORTED)
                is DynamicOgnlAdmission.Result.UnprovenProperty ->
                    return PreparationResult.Failed(
                        PreparationFailure(
                            kind = PreparationFailureKind.UNSUPPORTED_SEMANTIC,
                            code = DYNAMIC_OGNL_PROPERTY_UNPROVEN,
                            bindingProperty = admission.property,
                        ),
                    )
                is DynamicOgnlAdmission.Result.BindAuthority -> {
                    val kind = when (admission.problem) {
                        DynamicOgnlAdmission.BindAuthorityProblem.KIND_UNSUPPORTED ->
                            PreparationFailureKind.UNSUPPORTED_SEMANTIC
                        DynamicOgnlAdmission.BindAuthorityProblem.MISSING,
                        DynamicOgnlAdmission.BindAuthorityProblem.AMBIGUOUS,
                        DynamicOgnlAdmission.BindAuthorityProblem.SOURCE_CONTRACT_MISMATCH,
                        -> PreparationFailureKind.BINDING_RESOLUTION
                    }
                    val code = when (admission.problem) {
                        DynamicOgnlAdmission.BindAuthorityProblem.MISSING -> ADDITIONAL_PROVENANCE_MISSING
                        DynamicOgnlAdmission.BindAuthorityProblem.AMBIGUOUS -> ADDITIONAL_PROVENANCE_AMBIGUOUS
                        DynamicOgnlAdmission.BindAuthorityProblem.KIND_UNSUPPORTED -> ADDITIONAL_KIND_UNSUPPORTED
                        DynamicOgnlAdmission.BindAuthorityProblem.SOURCE_CONTRACT_MISMATCH ->
                            BIND_SOURCE_PROVENANCE_MISMATCH
                    }
                    return PreparationResult.Failed(
                        PreparationFailure(kind, code, bindingProperty = admission.name),
                    )
                }
                is DynamicOgnlAdmission.Result.MalformedScript ->
                    return failed(
                        PreparationFailureKind.MYBATIS_PARSE,
                        PARSE_FAILURE,
                        diagnosticType = admission.failure.javaClass.name,
                    )
                is DynamicOgnlAdmission.Result.MalformedExpression ->
                    return failed(
                        PreparationFailureKind.OGNL,
                        DYNAMIC_OGNL_PARSE_FAILURE,
                        diagnosticType = admission.failure.javaClass.name,
                    )
            }
        }

        val parameterType = MyBatisValueConversion.resolveParameterType(
            source.capture.parameters.map { it.typeIdentity },
        ) ?: return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, UNSUPPORTED_PARAMETER_TYPE)

        val parameterValuesResult = MyBatisValueConversion.parameterValues(request)
        if (parameterValuesResult is MyBatisValueConversion.ParameterValuesResult.Failed) {
            return PreparationResult.Failed(parameterValuesResult.failure)
        }
        val parameterValues =
            (parameterValuesResult as MyBatisValueConversion.ParameterValuesResult.Ready).values

        val requiresIsolatedDynamicRuntime =
            dynamicScript || request.parameterContract.rawRequirements().isNotEmpty()
        val boundSql = if (requiresIsolatedDynamicRuntime) {
            when (
                val isolated = IsolatedDynamicMyBatisPreparation.prepare(
                    script,
                    parameterType,
                    parameterValues,
                )
            ) {
                is IsolatedDynamicMyBatisPreparation.Result.Ready -> isolated.boundSql
                is IsolatedDynamicMyBatisPreparation.Result.Failed ->
                    return PreparationResult.Failed(isolated.failure)
            }
        } else {
            when (val static = prepareStaticBoundSql(script, parameterType, parameterValues)) {
                is StaticBoundSqlResult.Ready -> static.boundSql
                is StaticBoundSqlResult.Failed -> return PreparationResult.Failed(static.failure)
            }
        }

        if (boundSql.sql.isBlank()) {
            return failed(PreparationFailureKind.PREPARATION_INVARIANT, EMPTY_SQL)
        }

        val rawInterpolations = captureRawInterpolations(request)
        if (rawInterpolations is RawInterpolationResult.Failed) {
            return PreparationResult.Failed(rawInterpolations.failure)
        }

        val bindingResult = captureBindings(boundSql, request)
        if (bindingResult is BindingCaptureResult.Failed) {
            return PreparationResult.Failed(bindingResult.failure)
        }
        val bindings = (bindingResult as BindingCaptureResult.Ready).bindings
        if (bindings.size != boundSql.mappings.size) {
            return failed(
                PreparationFailureKind.PARAMETER_MAPPING_MISMATCH,
                "mybatis-parameter-mapping-cardinality-mismatch",
            )
        }

        return try {
            PreparationResult.Success(
                PreparedExecution(
                    statementId = request.statementId,
                    statementKind = request.statementKind,
                    sourceRevisions = request.sourceRevisions,
                    sqlWithPlaceholders = boundSql.sql,
                    orderedBindings = bindings,
                    rawInterpolations = (rawInterpolations as RawInterpolationResult.Ready).interpolations,
                    preparationMetadata = PreparationMetadata(
                        engineIdentity = ENGINE_ID,
                        engineVersion = ENGINE_VERSION,
                        languageDriverIdentity = boundSql.languageDriverIdentity,
                    ),
                ),
            )
        } catch (failure: IllegalArgumentException) {
            failed(
                PreparationFailureKind.PREPARATION_INVARIANT,
                INVARIANT_FAILURE,
                diagnosticType = failure.javaClass.name,
            )
        }
    }

    private fun containsDynamicScript(sql: String): Boolean = sql.startsWith("<script>")

    private fun runtimeClassOption(sql: String): String? =
        explicitRuntimeClassOption.find(sql)?.groupValues?.get(1)

    private fun dynamicOgnlRootProperties(request: MyBatisPreparationRequest): Set<String> =
        request.inputEnvironment.aliases.mapTo(linkedSetOf()) { it.name }

    private fun prepareStaticBoundSql(
        script: String,
        parameterType: MyBatisParameterType,
        parameterValues: Map<String, Any?>,
    ): StaticBoundSqlResult {
        val configuration = Configuration()
        val languageDriver = configuration.defaultScriptingLanguageInstance
        val parameterObject = parentParameterObject(parameterValues)
        val resolvedParameterType = when (parameterType) {
            MyBatisParameterType.None -> null
            is MyBatisParameterType.Single -> parameterType.type
            MyBatisParameterType.Multi -> MapperMethod.ParamMap::class.java
        }

        val boundSql = try {
            val sqlSource = languageDriver.createSqlSource(configuration, script, resolvedParameterType)
            sqlSource.getBoundSql(parameterObject)
        } catch (failure: RuntimeException) {
            return StaticBoundSqlResult.Failed(classifyMyBatisFailure(failure))
        }

        val mappings = boundSql.parameterMappings.map { mapping ->
            snapshotStaticMapping(configuration, boundSql, parameterObject, mapping)
        }
        return StaticBoundSqlResult.Ready(
            MyBatisBoundSqlSnapshot(
                sql = boundSql.sql,
                mappings = mappings,
                languageDriverIdentity = languageDriver.javaClass.name,
            ),
        )
    }

    private fun parentParameterObject(parameterValues: Map<String, Any?>): Any? {
        if (parameterValues.isEmpty()) return null
        return MapperMethod.ParamMap<Any?>().apply { putAll(parameterValues) }
    }

    private fun snapshotStaticMapping(
        configuration: Configuration,
        boundSql: BoundSql,
        parameterObject: Any?,
        mapping: ParameterMapping,
    ): MyBatisParameterMappingSnapshot {
        val property = mapping.property
        val additional = property?.let(boundSql::hasAdditionalParameter) ?: false
        val runtimeValue = if (property == null) {
            MyBatisRuntimeValueSnapshot.Ready(null)
        } else {
            try {
                MyBatisRuntimeValueSnapshot.Ready(
                    resolveStaticMappingValue(configuration, boundSql, parameterObject, mapping),
                )
            } catch (failure: RuntimeException) {
                val chain = generateSequence<Throwable>(failure) { it.cause }.toList()
                MyBatisRuntimeValueSnapshot.Failed(
                    bindingFailure = chain.any { it is BindingException || it is ReflectionException },
                    diagnosticType = failure.javaClass.name,
                )
            }
        }

        return MyBatisParameterMappingSnapshot(
            property = property,
            additionalParameter = additional,
            runtimeValue = runtimeValue,
            mappingJavaTypeIdentity = mapping.javaType?.name,
            jdbcTypeIdentity = mapping.jdbcType?.name,
            typeHandlerIdentity = mapping.typeHandler?.javaClass?.name,
            parameterMode = mapping.mode?.name,
            numericScale = mapping.numericScale,
        )
    }

    private fun resolveStaticMappingValue(
        configuration: Configuration,
        boundSql: BoundSql,
        parameterObject: Any?,
        mapping: ParameterMapping,
    ): Any? {
        val property = mapping.property
        return when {
            boundSql.hasAdditionalParameter(property) -> boundSql.getAdditionalParameter(property)
            parameterObject == null -> null
            configuration.typeHandlerRegistry.hasTypeHandler(parameterObject.javaClass) -> parameterObject
            else -> configuration.newMetaObject(parameterObject).getValue(property)
        }
    }

    private fun captureRawInterpolations(request: MyBatisPreparationRequest): RawInterpolationResult {
        val interpolations = mutableListOf<PreparedRawInterpolation>()
        for ((id, _, _, _, provenance) in request.parameterContract.rawRequirements()) {
            val provided = request.inputEnvironment.value(id)
                ?: return RawInterpolationResult.Failed(
                    PreparationFailure(
                        PreparationFailureKind.PREPARATION_INVARIANT,
                        RAW_INPUT_MISSING,
                    ),
                )
            if (provided.value !is InputValue.RawText) {
                return RawInterpolationResult.Failed(
                    PreparationFailure(
                        PreparationFailureKind.PREPARATION_INVARIANT,
                        RAW_INPUT_MISSING,
                    ),
                )
            }
            interpolations += PreparedRawInterpolation(id, provenance, provided.origin)
        }
        return RawInterpolationResult.Ready(interpolations)
    }

    private fun captureBindings(
        boundSql: MyBatisBoundSqlSnapshot,
        request: MyBatisPreparationRequest,
    ): BindingCaptureResult {
        val aliasesByName = request.inputEnvironment.aliases.groupBy { it.name }
        val bindings = mutableListOf<PreparedBinding>()

        for ((index, mapping) in boundSql.mappings.withIndex()) {
            if (mapping.parameterMode != "IN") {
                return bindingFailure(
                    PreparationFailureKind.UNSUPPORTED_SEMANTIC,
                    UNSUPPORTED_PARAMETER_MODE,
                    mapping.property,
                )
            }
            val property = mapping.property?.takeIf { it.isNotBlank() }
                ?: return bindingFailure(
                    PreparationFailureKind.PARAMETER_MAPPING_MISMATCH,
                    MISSING_MAPPING_PROPERTY,
                    null,
                )
            val rootProperty = property.substringBefore('.')
            val aliasCandidates = aliasesByName[rootProperty].orEmpty()
            if (aliasCandidates.size > 1) {
                return bindingFailure(
                    PreparationFailureKind.BINDING_RESOLUTION,
                    UNRESOLVED_MAPPING,
                    property,
                )
            }
            val alias = aliasCandidates.singleOrNull()
            val requirement = alias?.let { request.parameterContract.requirement(it.requirementId) }

            val origin = if (mapping.additionalParameter) {
                val candidates = request.parameterContract.internalBindings.filter { it.name == rootProperty }
                if (candidates.isEmpty()) {
                    return bindingFailure(
                        PreparationFailureKind.BINDING_RESOLUTION,
                        ADDITIONAL_PROVENANCE_MISSING,
                        property,
                    )
                }
                if (candidates.size != 1) {
                    return bindingFailure(
                        PreparationFailureKind.BINDING_RESOLUTION,
                        ADDITIONAL_PROVENANCE_AMBIGUOUS,
                        property,
                    )
                }
                val internalBinding = candidates.single()
                if (internalBinding.kind != InternalBindingKind.BIND) {
                    return bindingFailure(
                        PreparationFailureKind.UNSUPPORTED_SEMANTIC,
                        ADDITIONAL_KIND_UNSUPPORTED,
                        property,
                    )
                }
                PreparedBindingOrigin.MyBatisAdditional(internalBinding)
            } else {
                if (requirement?.kind == InputKind.RAW_INTERPOLATION) {
                    return bindingFailure(
                        PreparationFailureKind.PARAMETER_MAPPING_MISMATCH,
                        RAW_BOUND_CONFLATION,
                        property,
                    )
                }
                if (requirement == null || alias == null) {
                    return bindingFailure(
                        PreparationFailureKind.BINDING_RESOLUTION,
                        UNRESOLVED_MAPPING,
                        property,
                    )
                }
                if (!MyBatisValueConversion.pathExists(request, alias, requirement, property)) {
                    return bindingFailure(
                        PreparationFailureKind.BINDING_RESOLUTION,
                        UNRESOLVED_MAPPING,
                        property,
                    )
                }
                PreparedBindingOrigin.CallerInput(requirement.id, requirement.provenance)
            }

            val typeHandlerName = mapping.typeHandlerIdentity
                ?: return bindingFailure(
                    PreparationFailureKind.UNSUPPORTED_TYPE_HANDLER,
                    UNSUPPORTED_TYPE_HANDLER,
                    property,
                )
            if (!typeHandlerName.startsWith("org.apache.ibatis.type.")) {
                return bindingFailure(
                    PreparationFailureKind.UNSUPPORTED_TYPE_HANDLER,
                    UNSUPPORTED_TYPE_HANDLER,
                    property,
                    typeHandlerName,
                )
            }

            val runtimeValue = when (val value = mapping.runtimeValue) {
                is MyBatisRuntimeValueSnapshot.Ready -> value.value
                is MyBatisRuntimeValueSnapshot.Failed ->
                    return bindingFailure(
                        if (value.bindingFailure) {
                            PreparationFailureKind.BINDING_RESOLUTION
                        } else {
                            PreparationFailureKind.PREPARATION_INVARIANT
                        },
                        if (value.bindingFailure) BINDING_FAILURE else INVARIANT_FAILURE,
                        property,
                        value.diagnosticType,
                    )
            }

            val callerRequirement = (origin as? PreparedBindingOrigin.CallerInput)?.let { requirement }
            val callerAlias = if (callerRequirement != null) alias else null
            val coreValue = MyBatisValueConversion.toCoreValue(runtimeValue, callerRequirement, property, callerAlias)
                ?: return bindingFailure(
                    PreparationFailureKind.UNSUPPORTED_BINDING_VALUE,
                    UNSUPPORTED_VALUE,
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
                    typeHandlerIdentity = typeHandlerName,
                    parameterMode = mapping.parameterMode,
                    numericScale = mapping.numericScale,
                ),
            )
        }
        return BindingCaptureResult.Ready(bindings)
    }

    private fun classifyMyBatisFailure(failure: RuntimeException): PreparationFailure {
        val chain = generateSequence<Throwable>(failure) { it.cause }.toList()
        return when {
            chain.any { it.javaClass.name.contains(".ognl.") } -> PreparationFailure(
                PreparationFailureKind.OGNL,
                OGNL_FAILURE,
                diagnosticType = failure.javaClass.name,
            )
            chain.any { it is BindingException || it is ReflectionException } -> PreparationFailure(
                PreparationFailureKind.BINDING_RESOLUTION,
                BINDING_FAILURE,
                diagnosticType = failure.javaClass.name,
            )
            chain.any { it is BuilderException } -> PreparationFailure(
                PreparationFailureKind.MYBATIS_PARSE,
                PARSE_FAILURE,
                diagnosticType = failure.javaClass.name,
            )
            else -> PreparationFailure(
                PreparationFailureKind.PREPARATION_INVARIANT,
                INVARIANT_FAILURE,
                diagnosticType = failure.javaClass.name,
            )
        }
    }

    private fun bindingFailure(
        kind: PreparationFailureKind,
        code: String,
        property: String?,
        diagnosticType: String? = null,
    ) = BindingCaptureResult.Failed(PreparationFailure(kind, code, property, diagnosticType))

    private fun failed(
        kind: PreparationFailureKind,
        code: String,
        diagnosticType: String? = null,
    ) = PreparationResult.Failed(PreparationFailure(kind, code, diagnosticType = diagnosticType))

    private sealed interface StaticBoundSqlResult {
        data class Ready(
            val boundSql: MyBatisBoundSqlSnapshot,
        ) : StaticBoundSqlResult

        data class Failed(
            val failure: PreparationFailure,
        ) : StaticBoundSqlResult
    }

    private sealed interface RawInterpolationResult {
        data class Ready(
            val interpolations: List<PreparedRawInterpolation>,
        ) : RawInterpolationResult

        data class Failed(
            val failure: PreparationFailure,
        ) : RawInterpolationResult
    }

    private sealed interface BindingCaptureResult {
        data class Ready(
            val bindings: List<PreparedBinding>,
        ) : BindingCaptureResult

        data class Failed(
            val failure: PreparationFailure,
        ) : BindingCaptureResult
    }
}
