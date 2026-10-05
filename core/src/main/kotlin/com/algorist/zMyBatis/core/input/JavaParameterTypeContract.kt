package com.algorist.zMyBatis.core.input

import com.algorist.zMyBatis.core.source.JavaTypeIdentity

internal object JavaParameterTypeContract {
    private val PARAMETER_OBJECT_TYPE_HANDLER_TYPES = setOf(
        "boolean",
        "byte",
        "short",
        "int",
        "long",
        "float",
        "double",
        "java.lang.Boolean",
        "java.lang.Byte",
        "java.lang.Short",
        "java.lang.Integer",
        "java.lang.Long",
        "java.lang.Float",
        "java.lang.Double",
        "java.lang.String",
        "java.math.BigInteger",
        "java.math.BigDecimal",
        "java.time.Instant",
        "java.time.LocalDate",
        "java.time.LocalDateTime",
        "java.time.LocalTime",
    )

    fun expectedType(
        type: JavaTypeIdentity,
        kind: InputKind,
    ): ExpectedInputType {
        val nullability = if (isPrimitive(type)) InputNullability.NON_NULL else InputNullability.UNKNOWN
        if (kind == InputKind.RAW_INTERPOLATION) {
            return ExpectedInputType(
                shape = InputShape.RAW_TEXT,
                scalarType = InputScalarType.STRING,
                javaTypeIdentity = type,
                nullability = nullability,
            )
        }

        val canonical = type.value.trim()
        if (canonical.endsWith("[]")) {
            return ExpectedInputType(
                shape = InputShape.ARRAY,
                javaTypeIdentity = type,
                nullability = nullability,
            )
        }

        val rawType = canonical.substringBefore('<').trim()
        val scalarType = when (rawType) {
            "java.lang.String" -> InputScalarType.STRING
            "boolean", "java.lang.Boolean" -> InputScalarType.BOOLEAN
            "byte", "short", "int", "long",
            "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long",
            "java.math.BigInteger",
            -> InputScalarType.INTEGER
            "float", "double", "java.lang.Float", "java.lang.Double", "java.math.BigDecimal" ->
                InputScalarType.DECIMAL
            "java.util.UUID" -> InputScalarType.UUID
            else -> null
        }
        if (scalarType != null) {
            return ExpectedInputType(
                shape = InputShape.SCALAR,
                scalarType = scalarType,
                javaTypeIdentity = type,
                nullability = nullability,
            )
        }

        val temporalType = when (rawType) {
            "java.time.LocalDate" -> InputScalarType.DATE
            "java.time.LocalTime" -> InputScalarType.TIME
            "java.time.LocalDateTime" -> InputScalarType.DATE_TIME
            "java.time.Instant" -> InputScalarType.INSTANT
            else -> null
        }
        if (temporalType != null) {
            return ExpectedInputType(
                shape = InputShape.TEMPORAL,
                scalarType = temporalType,
                javaTypeIdentity = type,
                nullability = nullability,
            )
        }

        val shape = when (rawType) {
            "java.util.List", "java.util.Collection" -> InputShape.LIST
            "java.util.Map" -> InputShape.MAP
            else -> InputShape.UNKNOWN
        }
        return ExpectedInputType(
            shape = shape,
            javaTypeIdentity = type,
            nullability = nullability,
        )
    }

    fun isString(type: JavaTypeIdentity): Boolean = type.value == "java.lang.String"

    fun supportsStockParameterObjectTypeHandler(type: JavaTypeIdentity): Boolean {
        val canonical = type.value.trim()
        if ('<' in canonical || '>' in canonical || canonical.endsWith("[]")) return false
        return canonical in PARAMETER_OBJECT_TYPE_HANDLER_TYPES
    }

    fun stringKeyedMapValueType(type: JavaTypeIdentity): JavaTypeIdentity? {
        val canonical = type.value.trim()
        val genericStart = canonical.indexOf('<')
        if (genericStart <= 0 || !canonical.endsWith('>')) return null
        if (canonical.substring(0, genericStart).trim() != "java.util.Map") return null

        val arguments = splitTopLevelArguments(
            canonical.substring(genericStart + 1, canonical.length - 1),
        ) ?: return null
        if (arguments.size != 2 || arguments[0] != "java.lang.String") return null

        val valueType = JavaTypeIdentity(arguments[1])
        if (isPrimitive(valueType)) return null
        val expected = expectedType(valueType, InputKind.BOUND)
        if (expected.shape !in setOf(InputShape.SCALAR, InputShape.TEMPORAL)) return null
        return valueType.takeIf(::supportsStockParameterObjectTypeHandler)
    }

    private fun splitTopLevelArguments(text: String): List<String>? {
        if (text.isBlank()) return null
        val arguments = mutableListOf<String>()
        var depth = 0
        var start = 0
        text.forEachIndexed { index, ch ->
            when (ch) {
                '<' -> depth++
                '>' -> {
                    depth--
                    if (depth < 0) return null
                }
                ',' -> if (depth == 0) {
                    val argument = text.substring(start, index).trim()
                    if (argument.isEmpty()) return null
                    arguments += argument
                    start = index + 1
                }
            }
        }
        if (depth != 0) return null
        val last = text.substring(start).trim()
        if (last.isEmpty()) return null
        arguments += last
        return arguments
    }

    private fun isPrimitive(type: JavaTypeIdentity): Boolean = type.value in setOf(
        "boolean", "byte", "short", "int", "long", "float", "double", "char",
    )
}
