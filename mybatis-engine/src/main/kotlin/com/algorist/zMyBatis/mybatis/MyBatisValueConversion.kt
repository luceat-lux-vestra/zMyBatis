package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.InputAlias
import com.algorist.zMyBatis.core.input.InputRequirement
import com.algorist.zMyBatis.core.input.InputShape
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationFailure
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import com.algorist.zMyBatis.core.source.JavaTypeIdentity
import java.lang.reflect.Array as ReflectArray
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.ArrayDeque
import java.util.UUID

internal object MyBatisValueConversion {
    private const val ROW_BOUNDS_TYPE = "org.apache.ibatis.session.RowBounds"
    private const val RESULT_HANDLER_TYPE = "org.apache.ibatis.session.ResultHandler"
    private const val UNSUPPORTED_VALUE = "mybatis-binding-value-unsupported"
    private const val VALUE_OUT_OF_RANGE = "java-parameter-value-out-of-range"
    private const val VALUE_TYPE_MISMATCH = "java-parameter-value-type-mismatch"
    private const val VALUE_TOO_COMPLEX = "mybatis-binding-value-too-complex"
    private const val INVARIANT_FAILURE = "mybatis-preparation-invariant-failure"
    private const val MAX_INPUT_DEPTH = 128
    private const val MAX_INPUT_NODES = 65_536

    fun resolveParameterType(parameterTypes: List<JavaTypeIdentity>): MyBatisParameterType? {
        val resolved = mutableListOf<Class<*>>()
        for (typeIdentity in parameterTypes) {
            if (isMyBatisSpecialParameter(typeIdentity)) continue
            resolved += resolveSafeJavaType(typeIdentity) ?: return null
        }
        return when (resolved.size) {
            0 -> MyBatisParameterType.None
            1 -> MyBatisParameterType.Single(resolved.single())
            else -> MyBatisParameterType.Multi
        }
    }

    fun parameterValues(request: MyBatisPreparationRequest): ParameterValuesResult {
        if (!inputComplexityIsBounded(request.inputEnvironment.values.values.map { it.value })) {
            return ParameterValuesResult.Failed(
                PreparationFailure(
                    PreparationFailureKind.UNSUPPORTED_BINDING_VALUE,
                    VALUE_TOO_COMPLEX,
                ),
            )
        }

        if (request.inputEnvironment.values.isEmpty()) {
            return ParameterValuesResult.Ready(linkedMapOf())
        }

        val values = linkedMapOf<String, Any?>()
        for ((name, requirementId) in request.inputEnvironment.aliases) {
            val provided = request.inputEnvironment.value(requirementId) ?: continue
            val requirement = request.parameterContract.requirement(requirementId)
                ?: return ParameterValuesResult.Failed(
                    PreparationFailure(
                        PreparationFailureKind.PREPARATION_INVARIANT,
                        INVARIANT_FAILURE,
                        bindingProperty = name,
                    ),
                )
            val converted = toRuntimeValue(provided.value, requirement.expectedType.javaTypeIdentity)
            if (converted is RuntimeValueResult.Failed) {
                return ParameterValuesResult.Failed(converted.failure.copy(bindingProperty = name))
            }
            values[name] = (converted as RuntimeValueResult.Ready).value
        }
        return ParameterValuesResult.Ready(values)
    }

    fun pathExists(
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

    fun toCoreValue(
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
            is Float -> if (value.isFinite()) {
                InputValue.DecimalValue(BigDecimal(value.toString()))
            } else {
                null
            }
            is Double -> if (value.isFinite()) {
                InputValue.DecimalValue(BigDecimal(value.toString()))
            } else {
                null
            }
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

    private fun inputComplexityIsBounded(values: Collection<InputValue>): Boolean {
        val pending = ArrayDeque<Pair<InputValue, Int>>()
        values.forEach { pending.addLast(it to 0) }
        var visitedNodes = 0

        while (pending.isNotEmpty()) {
            if (++visitedNodes > MAX_INPUT_NODES) return false
            val (value, depth) = pending.removeLast()
            if (depth > MAX_INPUT_DEPTH) return false

            when (value) {
                is InputValue.ObjectValue -> value.entries.values.forEach { pending.addLast(it to depth + 1) }
                is InputValue.MapValue -> value.entries.values.forEach { pending.addLast(it to depth + 1) }
                is InputValue.ListValue -> value.elements.forEach { pending.addLast(it to depth + 1) }
                is InputValue.ArrayValue -> value.elements.forEach { pending.addLast(it to depth + 1) }
                InputValue.NullValue,
                is InputValue.Text,
                is InputValue.RawText,
                is InputValue.BooleanValue,
                is InputValue.IntegerValue,
                is InputValue.DecimalValue,
                is InputValue.DateValue,
                is InputValue.TimeValue,
                is InputValue.DateTimeValue,
                is InputValue.InstantValue,
                is InputValue.UuidValue,
                -> Unit
            }
        }
        return true
    }

    private fun isMyBatisSpecialParameter(typeIdentity: JavaTypeIdentity): Boolean =
        typeIdentity.value.substringBefore('<').trim() in setOf(ROW_BOUNDS_TYPE, RESULT_HANDLER_TYPE)

    private fun resolveSafeJavaType(typeIdentity: JavaTypeIdentity): Class<*>? {
        val canonical = typeIdentity.value.trim()
        if (canonical.endsWith("[]")) {
            val component = resolveSafeJavaType(JavaTypeIdentity(canonical.removeSuffix("[]"))) ?: return null
            return ReflectArray.newInstance(component, 0).javaClass
        }
        return when (canonical.substringBefore('<').trim()) {
            "boolean" -> Boolean::class.javaPrimitiveType
            "byte" -> Byte::class.javaPrimitiveType
            "short" -> Short::class.javaPrimitiveType
            "int" -> Int::class.javaPrimitiveType
            "long" -> Long::class.javaPrimitiveType
            "float" -> Float::class.javaPrimitiveType
            "double" -> Double::class.javaPrimitiveType
            "char" -> Char::class.javaPrimitiveType
            "java.lang.Boolean" -> Boolean::class.javaObjectType
            "java.lang.Byte" -> Byte::class.javaObjectType
            "java.lang.Short" -> Short::class.javaObjectType
            "java.lang.Integer" -> Int::class.javaObjectType
            "java.lang.Long" -> Long::class.javaObjectType
            "java.lang.Float" -> Float::class.javaObjectType
            "java.lang.Double" -> Double::class.javaObjectType
            "java.lang.Character" -> Char::class.javaObjectType
            "java.lang.String" -> String::class.java
            "java.math.BigInteger" -> BigInteger::class.java
            "java.math.BigDecimal" -> BigDecimal::class.java
            "java.util.UUID" -> UUID::class.java
            "java.time.LocalDate" -> LocalDate::class.java
            "java.time.LocalTime" -> LocalTime::class.java
            "java.time.LocalDateTime" -> LocalDateTime::class.java
            "java.time.Instant" -> Instant::class.java
            "java.util.List" -> List::class.java
            "java.util.Collection" -> Collection::class.java
            "java.util.Map" -> Map::class.java
            else -> null
        }
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
            is InputValue.ListValue -> RuntimeValueResult.Ready(
                value.elements.mapTo(ArrayList(value.elements.size), ::toUntypedRuntimeValue),
            )
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
        val componentClass = resolveSafeJavaType(componentIdentity)
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
        is InputValue.ListValue -> value.elements.mapTo(ArrayList(value.elements.size), ::toUntypedRuntimeValue)
        is InputValue.ArrayValue -> value.elements.map(::toUntypedRuntimeValue).toTypedArray()
    }

    private fun typeMismatch() = RuntimeValueResult.Failed(
        PreparationFailure(PreparationFailureKind.UNSUPPORTED_BINDING_VALUE, VALUE_TYPE_MISMATCH),
    )

    private fun outOfRange() = RuntimeValueResult.Failed(
        PreparationFailure(PreparationFailureKind.UNSUPPORTED_BINDING_VALUE, VALUE_OUT_OF_RANGE),
    )

    sealed interface ParameterValuesResult {
        data class Ready(
            val values: Map<String, Any?>,
        ) : ParameterValuesResult

        data class Failed(
            val failure: PreparationFailure,
        ) : ParameterValuesResult
    }

    private sealed interface RuntimeValueResult {
        data class Ready(
            val value: Any?,
        ) : RuntimeValueResult

        data class Failed(
            val failure: PreparationFailure,
        ) : RuntimeValueResult
    }
}
