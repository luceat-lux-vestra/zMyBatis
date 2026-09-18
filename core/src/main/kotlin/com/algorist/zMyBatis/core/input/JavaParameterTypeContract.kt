package com.algorist.zMyBatis.core.input

import com.algorist.zMyBatis.core.source.JavaTypeIdentity

internal object JavaParameterTypeContract {
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

    private fun isPrimitive(type: JavaTypeIdentity): Boolean = type.value in setOf(
        "boolean", "byte", "short", "int", "long", "float", "double", "char",
    )
}
