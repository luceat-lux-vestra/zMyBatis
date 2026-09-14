package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.InputAlias
import com.algorist.zMyBatis.core.input.InputKind
import com.algorist.zMyBatis.core.input.InputRequirement
import com.algorist.zMyBatis.core.input.InputShape
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationFailure
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import com.algorist.zMyBatis.core.preparation.PreparationMetadata
import com.algorist.zMyBatis.core.preparation.PreparationResult
import com.algorist.zMyBatis.core.preparation.PreparationSource
import com.algorist.zMyBatis.core.preparation.PreparedBinding
import com.algorist.zMyBatis.core.preparation.PreparedBindingMetadata
import com.algorist.zMyBatis.core.preparation.PreparedExecution
import com.algorist.zMyBatis.core.preparation.PreparedRawInterpolation
import com.algorist.zMyBatis.core.preparation.rawRequirements
import com.algorist.zMyBatis.core.source.JavaTypeIdentity
import org.apache.ibatis.binding.BindingException
import org.apache.ibatis.binding.MapperMethod
import org.apache.ibatis.builder.BuilderException
import org.apache.ibatis.mapping.BoundSql
import org.apache.ibatis.mapping.ParameterMapping
import org.apache.ibatis.mapping.ParameterMode
import org.apache.ibatis.reflection.ReflectionException
import org.apache.ibatis.session.Configuration
import org.apache.ibatis.session.ResultHandler
import org.apache.ibatis.session.RowBounds
import java.lang.reflect.Array as ReflectArray
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

object MyBatisPreparationEngine {
    private const val ENGINE_ID = "org.mybatis:mybatis"
    private const val ENGINE_VERSION = "3.5.19"
    private const val UNSUPPORTED_SOURCE = "preparation-source-kind-unsupported"
    private const val UNSUPPORTED_PARAMETER_TYPE = "java-annotation-parameter-type-unavailable"
    private const val UNSUPPORTED_PARAMETER_MODE = "mybatis-parameter-mode-unsupported"
    private const val UNSUPPORTED_TYPE_HANDLER = "mybatis-custom-type-handler-unsupported"
    private const val EXPLICIT_JAVA_TYPE = "mybatis-explicit-java-type-unsupported"
    private const val MISSING_MAPPING_PROPERTY = "mybatis-parameter-mapping-property-missing"
    private const val UNRESOLVED_MAPPING = "mybatis-parameter-mapping-unresolved"
    private const val RAW_INPUT_MISSING = "raw-interpolation-input-missing"
    private const val RAW_BOUND_CONFLATION = "raw-interpolation-bound-mapping-conflation"
    private const val UNSUPPORTED_VALUE = "mybatis-binding-value-unsupported"
    private const val VALUE_OUT_OF_RANGE = "java-parameter-value-out-of-range"
    private const val VALUE_TYPE_MISMATCH = "java-parameter-value-type-mismatch"
    private const val EMPTY_SQL = "mybatis-prepared-sql-empty"
    private const val PARSE_FAILURE = "mybatis-sql-source-parse-failure"
    private const val OGNL_FAILURE = "mybatis-ognl-evaluation-failure"
    private const val BINDING_FAILURE = "mybatis-binding-resolution-failure"
    private const val INVARIANT_FAILURE = "mybatis-preparation-invariant-failure"

    private val explicitRuntimeClassOption = Regex(
        pattern = "#\\{[^}]*\\b(typeHandler|javaType)\\s*=",
        option = RegexOption.IGNORE_CASE,
    )

    fun prepare(request: MyBatisPreparationRequest): PreparationResult {
        val source = request.source as? PreparationSource.JavaAnnotation
            ?: return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, UNSUPPORTED_SOURCE)

        val explicitClassOption = source.capture.sqlSegments.firstNotNullOfOrNull(::runtimeClassOption)
        if (explicitClassOption != null) {
            return if (explicitClassOption.equals("typeHandler", ignoreCase = true)) {
                failed(PreparationFailureKind.UNSUPPORTED_TYPE_HANDLER, UNSUPPORTED_TYPE_HANDLER)
            } else {
                failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, EXPLICIT_JAVA_TYPE)
            }
        }

        val configuration = Configuration()
        val languageDriver = configuration.defaultScriptingLanguageInstance
        val parameterType = resolveAnnotationParameterType(source.capture.parameters.map { it.typeIdentity })
            ?: return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, UNSUPPORTED_PARAMETER_TYPE)
        val parameterObjectResult = buildParameterObject(request)
        if (parameterObjectResult is ParameterObjectResult.Failed) {
            return PreparationResult.Failed(parameterObjectResult.failure)
        }
        val parameterObject = (parameterObjectResult as ParameterObjectResult.Ready).value
        val script = source.capture.sqlSegments.joinToString(separator = " ").trim()

        val boundSql = try {
            val sqlSource = languageDriver.createSqlSource(configuration, script, parameterType.type)
            sqlSource.getBoundSql(parameterObject)
        } catch (failure: RuntimeException) {
            return PreparationResult.Failed(classifyMyBatisFailure(failure))
        }

        if (boundSql.sql.isBlank()) {
            return failed(PreparationFailureKind.PREPARATION_INVARIANT, EMPTY_SQL)
        }

        val rawInterpolations = captureRawInterpolations(request)
        if (rawInterpolations is RawInterpolationResult.Failed) {
            return PreparationResult.Failed(rawInterpolations.failure)
        }

        val bindingResult = captureBindings(configuration, boundSql, parameterObject, request)
        if (bindingResult is BindingCaptureResult.Failed) {
            return PreparationResult.Failed(bindingResult.failure)
        }
        val bindings = (bindingResult as BindingCaptureResult.Ready).bindings
        if (bindings.size != boundSql.parameterMappings.size) {
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
                        languageDriverIdentity = languageDriver.javaClass.name,
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

    private fun runtimeClassOption(sql: String): String? =
        explicitRuntimeClassOption.find(sql)?.groupValues?.get(1)

    private fun resolveAnnotationParameterType(parameterTypes: List<JavaTypeIdentity>): ParameterTypeResolution? {
        val resolved = mutableListOf<Class<*>>()
        for (typeIdentity in parameterTypes) {
            val type = resolveJavaType(typeIdentity) ?: return null
            if (RowBounds::class.java.isAssignableFrom(type) || ResultHandler::class.java.isAssignableFrom(type)) {
                continue
            }
            resolved += type
        }
        return ParameterTypeResolution(
            when (resolved.size) {
                0 -> null
                1 -> resolved.single()
                else -> MapperMethod.ParamMap::class.java
            },
        )
    }

    private fun resolveJavaType(typeIdentity: JavaTypeIdentity): Class<*>? {
        val canonical = typeIdentity.value.trim()
        if (canonical.endsWith("[]")) {
            val component = resolveJavaType(JavaTypeIdentity(canonical.removeSuffix("[]"))) ?: return null
            return ReflectArray.newInstance(component, 0).javaClass
        }
        return when (val rawType = canonical.substringBefore('<').trim()) {
            "boolean" -> Boolean::class.javaPrimitiveType
            "byte" -> Byte::class.javaPrimitiveType
            "short" -> Short::class.javaPrimitiveType
            "int" -> Int::class.javaPrimitiveType
            "long" -> Long::class.javaPrimitiveType
            "float" -> Float::class.javaPrimitiveType
            "double" -> Double::class.javaPrimitiveType
            "char" -> Char::class.javaPrimitiveType
            else -> runCatching {
                Class.forName(rawType, false, MyBatisPreparationEngine::class.java.classLoader)
            }.getOrNull()
        }
    }

    private fun buildParameterObject(request: MyBatisPreparationRequest): ParameterObjectResult {
        if (request.inputEnvironment.values.isEmpty()) return ParameterObjectResult.Ready(null)

        val params = MapperMethod.ParamMap<Any?>()
        for (alias in request.inputEnvironment.aliases) {
            val provided = request.inputEnvironment.value(alias.requirementId) ?: continue
            val requirement = request.parameterContract.requirement(alias.requirementId)
                ?: return ParameterObjectResult.Failed(
                    PreparationFailure(
                        PreparationFailureKind.PREPARATION_INVARIANT,
                        INVARIANT_FAILURE,
                        bindingProperty = alias.name,
                    ),
                )
            val converted = toRuntimeValue(provided.value, requirement.expectedType.javaTypeIdentity)
            if (converted is RuntimeValueResult.Failed) {
                return ParameterObjectResult.Failed(
                    converted.failure.copy(bindingProperty = alias.name),
                )
            }
            params[alias.name] = (converted as RuntimeValueResult.Ready).value
        }
        return ParameterObjectResult.Ready(params)
    }

    private fun captureRawInterpolations(request: MyBatisPreparationRequest): RawInterpolationResult {
        val interpolations = mutableListOf<PreparedRawInterpolation>()
        for (requirement in request.parameterContract.rawRequirements()) {
            val provided = request.inputEnvironment.value(requirement.id)
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
            interpolations += PreparedRawInterpolation(requirement.id, requirement.provenance, provided.origin)
        }
        return RawInterpolationResult.Ready(interpolations)
    }

    private fun captureBindings(
        configuration: Configuration,
        boundSql: BoundSql,
        parameterObject: Any?,
        request: MyBatisPreparationRequest,
    ): BindingCaptureResult {
        val aliasesByName = request.inputEnvironment.aliases.associateBy(InputAlias::name)
        val bindings = mutableListOf<PreparedBinding>()

        for ((index, mapping) in boundSql.parameterMappings.withIndex()) {
            if (mapping.mode != ParameterMode.IN) {
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

            val additional = boundSql.hasAdditionalParameter(property)
            val alias = aliasesByName[property.substringBefore('.')]
            val requirement = alias?.let { request.parameterContract.requirement(it.requirementId) }
            if (requirement?.kind == InputKind.RAW_INTERPOLATION) {
                return bindingFailure(
                    PreparationFailureKind.PARAMETER_MAPPING_MISMATCH,
                    RAW_BOUND_CONFLATION,
                    property,
                )
            }
            if (!additional && requirement == null) {
                return bindingFailure(
                    PreparationFailureKind.BINDING_RESOLUTION,
                    UNRESOLVED_MAPPING,
                    property,
                )
            }
            if (!additional && requirement != null && !pathExists(request, alias!!, requirement, property)) {
                return bindingFailure(
                    PreparationFailureKind.BINDING_RESOLUTION,
                    UNRESOLVED_MAPPING,
                    property,
                )
            }

            val typeHandler = mapping.typeHandler
            val typeHandlerName = typeHandler?.javaClass?.name
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

            val runtimeValue = try {
                resolveMappingValue(configuration, boundSql, parameterObject, mapping)
            } catch (failure: RuntimeException) {
                return BindingCaptureResult.Failed(classifyBindingFailure(failure, property))
            }
            val coreValue = toCoreValue(runtimeValue, requirement, property, alias)
                ?: return bindingFailure(
                    PreparationFailureKind.UNSUPPORTED_BINDING_VALUE,
                    UNSUPPORTED_VALUE,
                    property,
                    runtimeValue?.javaClass?.name,
                )

            bindings += PreparedBinding(
                index = index,
                property = property,
                requirementId = requirement?.id,
                value = coreValue,
                provenance = requirement?.provenance,
                metadata = PreparedBindingMetadata(
                    declaredJavaTypeIdentity = requirement?.expectedType?.javaTypeIdentity,
                    mappingJavaTypeIdentity = mapping.javaType?.name,
                    jdbcTypeIdentity = mapping.jdbcType?.name,
                    typeHandlerIdentity = typeHandlerName,
                    parameterMode = mapping.mode.name,
                    numericScale = mapping.numericScale,
                    additionalParameter = additional,
                ),
            )
        }
        return BindingCaptureResult.Ready(bindings)
    }

    private fun resolveMappingValue(
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

    private fun pathExists(
        request: MyBatisPreparationRequest,
        alias: InputAlias,
        requirement: InputRequirement,
        property: String,
    ): Boolean {
        val provided = request.inputEnvironment.value(alias.requirementId) ?: return false
        if (requirement.id != provided.requirementId) return false
        val path = property.split('.')
        if (path.firstOrNull() != alias.name) return false
        var current: InputValue = provided.value
        for (segment in path.drop(1)) {
            current = when (current) {
                is InputValue.ObjectValue -> current.entries[segment]
                is InputValue.MapValue -> current.entries[segment]
                else -> null
            } ?: return false
        }
        return true
    }

    private fun toRuntimeValue(value: InputValue, declaredType: JavaTypeIdentity?): RuntimeValueResult {
        if (value is InputValue.NullValue) return RuntimeValueResult.Ready(null)
        val typeName = declaredType?.value?.trim().orEmpty()
        val rawTypeName = typeName.substringBefore('<').trim()

        return when (value) {
            InputValue.NullValue -> RuntimeValueResult.Ready(null)
            is InputValue.Text -> if (rawTypeName == "java.lang.String" || rawTypeName == "String") {
                RuntimeValueResult.Ready(value.value)
            } else {
                typeMismatch()
            }
            is InputValue.RawText -> if (rawTypeName == "java.lang.String" || rawTypeName == "String") {
                RuntimeValueResult.Ready(value.value)
            } else {
                typeMismatch()
            }
            is InputValue.BooleanValue -> when (rawTypeName) {
                "boolean", "java.lang.Boolean" -> RuntimeValueResult.Ready(value.value)
                else -> typeMismatch()
            }
            is InputValue.IntegerValue -> integerRuntimeValue(value.value, rawTypeName)
            is InputValue.DecimalValue -> decimalRuntimeValue(value.value, rawTypeName)
            is InputValue.DateValue -> when (rawTypeName) {
                "java.time.LocalDate" -> RuntimeValueResult.Ready(value.value)
                else -> typeMismatch()
            }
            is InputValue.TimeValue -> when (rawTypeName) {
                "java.time.LocalTime" -> RuntimeValueResult.Ready(value.value)
                else -> typeMismatch()
            }
            is InputValue.DateTimeValue -> when (rawTypeName) {
                "java.time.LocalDateTime" -> RuntimeValueResult.Ready(value.value)
                else -> typeMismatch()
            }
            is InputValue.InstantValue -> when (rawTypeName) {
                "java.time.Instant" -> RuntimeValueResult.Ready(value.value)
                else -> typeMismatch()
            }
            is InputValue.UuidValue -> when (rawTypeName) {
                "java.util.UUID" -> RuntimeValueResult.Ready(value.value)
                else -> typeMismatch()
            }
            is InputValue.ObjectValue -> RuntimeValueResult.Ready(
                value.entries.mapValuesTo(linkedMapOf()) { toUntypedRuntimeValue(it.value) },
            )
            is InputValue.MapValue -> RuntimeValueResult.Ready(
                value.entries.mapValuesTo(linkedMapOf()) { toUntypedRuntimeValue(it.value) },
            )
            is InputValue.ListValue -> RuntimeValueResult.Ready(value.elements.map(::toUntypedRuntimeValue))
            is InputValue.ArrayValue -> arrayRuntimeValue(value, rawTypeName)
        }
    }

    private fun integerRuntimeValue(value: BigInteger, rawTypeName: String): RuntimeValueResult = try {
        when (rawTypeName) {
            "byte", "java.lang.Byte" -> RuntimeValueResult.Ready(value.byteValueExact())
            "short", "java.lang.Short" -> RuntimeValueResult.Ready(value.shortValueExact())
            "int", "java.lang.Integer" -> RuntimeValueResult.Ready(value.intValueExact())
            "long", "java.lang.Long" -> RuntimeValueResult.Ready(value.longValueExact())
            "java.math.BigInteger" -> RuntimeValueResult.Ready(value)
            else -> typeMismatch()
        }
    } catch (_: ArithmeticException) {
        outOfRange()
    }

    private fun decimalRuntimeValue(value: BigDecimal, rawTypeName: String): RuntimeValueResult = when (rawTypeName) {
        "float", "java.lang.Float" -> {
            val converted = value.toFloat()
            if (converted.isFinite()) RuntimeValueResult.Ready(converted) else outOfRange()
        }
        "double", "java.lang.Double" -> {
            val converted = value.toDouble()
            if (converted.isFinite()) RuntimeValueResult.Ready(converted) else outOfRange()
        }
        "java.math.BigDecimal" -> RuntimeValueResult.Ready(value)
        else -> typeMismatch()
    }

    private fun arrayRuntimeValue(value: InputValue.ArrayValue, rawTypeName: String): RuntimeValueResult {
        if (!rawTypeName.endsWith("[]")) return typeMismatch()
        val componentIdentity = JavaTypeIdentity(rawTypeName.removeSuffix("[]"))
        val componentClass = resolveJavaType(componentIdentity)
            ?: return RuntimeValueResult.Failed(
                PreparationFailure(PreparationFailureKind.UNSUPPORTED_BINDING_VALUE, UNSUPPORTED_VALUE),
            )
        val target = ReflectArray.newInstance(componentClass, value.elements.size)
        for ((index, element) in value.elements.withIndex()) {
            val converted = toRuntimeValue(element, componentIdentity)
            if (converted is RuntimeValueResult.Failed) return converted
            try {
                ReflectArray.set(target, index, (converted as RuntimeValueResult.Ready).value)
            } catch (_: IllegalArgumentException) {
                return typeMismatch()
            }
        }
        return RuntimeValueResult.Ready(target)
    }

    private fun toUntypedRuntimeValue(value: InputValue): Any? = when (value) {
        InputValue.NullValue -> null
        is InputValue.Text -> value.value
        is InputValue.RawText -> value.value
        is InputValue.BooleanValue -> value.value
        is InputValue.IntegerValue -> value.value
        is InputValue.DecimalValue -> value.value
        is InputValue.DateValue -> value.value
        is InputValue.TimeValue -> value.value
        is InputValue.DateTimeValue -> value.value
        is InputValue.InstantValue -> value.value
        is InputValue.UuidValue -> value.value
        is InputValue.ObjectValue -> value.entries.mapValuesTo(linkedMapOf()) { toUntypedRuntimeValue(it.value) }
        is InputValue.MapValue -> value.entries.mapValuesTo(linkedMapOf()) { toUntypedRuntimeValue(it.value) }
        is InputValue.ListValue -> value.elements.map(::toUntypedRuntimeValue)
        is InputValue.ArrayValue -> value.elements.map(::toUntypedRuntimeValue).toTypedArray()
    }

    private fun typeMismatch() = RuntimeValueResult.Failed(
        PreparationFailure(PreparationFailureKind.UNSUPPORTED_BINDING_VALUE, VALUE_TYPE_MISMATCH),
    )

    private fun outOfRange() = RuntimeValueResult.Failed(
        PreparationFailure(PreparationFailureKind.UNSUPPORTED_BINDING_VALUE, VALUE_OUT_OF_RANGE),
    )

    private fun toCoreValue(
        value: Any?,
        requirement: InputRequirement?,
        property: String,
        alias: InputAlias?,
    ): InputValue? {
        if (value == null) return InputValue.NullValue
        return when (value) {
            is String -> InputValue.Text(value)
            is Boolean -> InputValue.BooleanValue(value)
            is BigInteger -> InputValue.IntegerValue(value)
            is Byte -> InputValue.IntegerValue(BigInteger.valueOf(value.toLong()))
            is Short -> InputValue.IntegerValue(BigInteger.valueOf(value.toLong()))
            is Int -> InputValue.IntegerValue(BigInteger.valueOf(value.toLong()))
            is Long -> InputValue.IntegerValue(BigInteger.valueOf(value))
            is BigDecimal -> InputValue.DecimalValue(value)
            is Float -> InputValue.DecimalValue(BigDecimal(value.toString()))
            is Double -> InputValue.DecimalValue(BigDecimal(value.toString()))
            is LocalDate -> InputValue.DateValue(value)
            is LocalTime -> InputValue.TimeValue(value)
            is LocalDateTime -> InputValue.DateTimeValue(value)
            is Instant -> InputValue.InstantValue(value)
            is UUID -> InputValue.UuidValue(value)
            is Map<*, *> -> {
                val entries = linkedMapOf<String, InputValue>()
                for ((key, item) in value) {
                    if (key !is String) return null
                    entries[key] = toCoreValue(item, null, property, null) ?: return null
                }
                if (
                    requirement?.expectedType?.shape == InputShape.OBJECT &&
                    alias != null &&
                    property == alias.name
                ) {
                    InputValue.ObjectValue(entries)
                } else {
                    InputValue.MapValue(entries)
                }
            }
            is List<*> -> {
                val elements = mutableListOf<InputValue>()
                for (item in value) {
                    elements += toCoreValue(item, null, property, null) ?: return null
                }
                InputValue.ListValue(elements)
            }
            else -> {
                if (!value.javaClass.isArray) return null
                val elements = mutableListOf<InputValue>()
                for (index in 0 until ReflectArray.getLength(value)) {
                    elements += toCoreValue(ReflectArray.get(value, index), null, property, null) ?: return null
                }
                InputValue.ArrayValue(elements)
            }
        }
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

    private fun classifyBindingFailure(failure: RuntimeException, property: String): PreparationFailure {
        val chain = generateSequence<Throwable>(failure) { it.cause }.toList()
        return if (chain.any { it is BindingException || it is ReflectionException }) {
            PreparationFailure(
                PreparationFailureKind.BINDING_RESOLUTION,
                BINDING_FAILURE,
                bindingProperty = property,
                diagnosticType = failure.javaClass.name,
            )
        } else {
            PreparationFailure(
                PreparationFailureKind.PREPARATION_INVARIANT,
                INVARIANT_FAILURE,
                bindingProperty = property,
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

    private data class ParameterTypeResolution(val type: Class<*>?)

    private sealed interface ParameterObjectResult {
        data class Ready(val value: Any?) : ParameterObjectResult
        data class Failed(val failure: PreparationFailure) : ParameterObjectResult
    }

    private sealed interface RuntimeValueResult {
        data class Ready(val value: Any?) : RuntimeValueResult
        data class Failed(val failure: PreparationFailure) : RuntimeValueResult
    }

    private sealed interface RawInterpolationResult {
        data class Ready(val interpolations: List<PreparedRawInterpolation>) : RawInterpolationResult
        data class Failed(val failure: PreparationFailure) : RawInterpolationResult
    }

    private sealed interface BindingCaptureResult {
        data class Ready(val bindings: List<PreparedBinding>) : BindingCaptureResult
        data class Failed(val failure: PreparationFailure) : BindingCaptureResult
    }
}
