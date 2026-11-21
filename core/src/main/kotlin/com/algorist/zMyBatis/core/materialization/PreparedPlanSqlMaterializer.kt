package com.algorist.zMyBatis.core.materialization

import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.PreparedExecutionPlan
import com.algorist.zMyBatis.core.execution.PreparedExecutionPlanFingerprint
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.preparation.PreparedBinding
import com.algorist.zMyBatis.core.source.ConventionalMyBatisDatabaseIds
import java.math.BigInteger

enum class PreparedPlanSqlMaterializationFailureKind {
    DIALECT_UNSUPPORTED,
    BINDING_METADATA_UNSUPPORTED,
    BINDING_VALUE_UNSUPPORTED,
    PLACEHOLDER_TOPOLOGY_UNSUPPORTED,
}

data class PreparedPlanSqlMaterializationFailure(
    val kind: PreparedPlanSqlMaterializationFailureKind,
    val code: String,
) {
    init {
        require(code.isNotBlank()) { "prepared-plan SQL materialization failure code must not be blank" }
    }
}

data class PreparedPlanSqlArtifact(
    val executionSql: String,
    val executionTargetId: ExecutionTargetId,
    val databaseId: String?,
    val planFingerprint: PreparedExecutionPlanFingerprint,
) {
    init {
        require(executionSql.isNotBlank()) { "prepared-plan SQL artifact must not be blank" }
    }
}

sealed interface PreparedPlanSqlMaterializationResult {
    data class Ready(
        val artifact: PreparedPlanSqlArtifact,
    ) : PreparedPlanSqlMaterializationResult

    data class Failed(
        val failure: PreparedPlanSqlMaterializationFailure,
    ) : PreparedPlanSqlMaterializationResult
}

/**
 * P5 bridge from the immutable stock-MyBatis execution plan to the native Database Tools
 * SQL-text/result path.
 *
 * Zero-binding plans remain DB-neutral exact text. Bound plans are admitted only for a dialect/type
 * envelope with explicit literal rules. There is no fallback to a prepared/modal result backend.
 */
object NativeExecutionPlanMaterializer {
    fun materialize(plan: PreparedExecutionPlan): PreparedPlanSqlMaterializationResult {
        if (plan.orderedBindings.isEmpty()) {
            return ready(plan, plan.sqlWithPlaceholders)
        }

        if (plan.databaseId != ConventionalMyBatisDatabaseIds.POSTGRESQL) {
            return failed(
                PreparedPlanSqlMaterializationFailureKind.DIALECT_UNSUPPORTED,
                DIALECT_UNSUPPORTED,
            )
        }

        return PostgreSqlPreparedPlanSqlMaterializer.materialize(plan)
    }

    internal fun ready(
        plan: PreparedExecutionPlan,
        executionSql: String,
    ): PreparedPlanSqlMaterializationResult.Ready =
        PreparedPlanSqlMaterializationResult.Ready(
            PreparedPlanSqlArtifact(
                executionSql = executionSql,
                executionTargetId = plan.executionTargetId,
                databaseId = plan.databaseId,
                planFingerprint = plan.fingerprint,
            ),
        )

    internal fun failed(
        kind: PreparedPlanSqlMaterializationFailureKind,
        code: String,
    ): PreparedPlanSqlMaterializationResult.Failed =
        PreparedPlanSqlMaterializationResult.Failed(
            PreparedPlanSqlMaterializationFailure(kind, code),
        )

    private const val DIALECT_UNSUPPORTED = "prepared-plan-sql-dialect-unsupported"
}

private object PostgreSqlPreparedPlanSqlMaterializer {
    fun materialize(plan: PreparedExecutionPlan): PreparedPlanSqlMaterializationResult {
        val markers = PostgreSqlBindMarkerScanner.locate(plan.sqlWithPlaceholders)
            ?: return NativeExecutionPlanMaterializer.failed(
                PreparedPlanSqlMaterializationFailureKind.PLACEHOLDER_TOPOLOGY_UNSUPPORTED,
                PLACEHOLDER_TOPOLOGY_UNSUPPORTED,
            )
        if (markers.size != plan.orderedBindings.size) {
            return NativeExecutionPlanMaterializer.failed(
                PreparedPlanSqlMaterializationFailureKind.PLACEHOLDER_TOPOLOGY_UNSUPPORTED,
                PLACEHOLDER_TOPOLOGY_UNSUPPORTED,
            )
        }

        val literals = ArrayList<String>(plan.orderedBindings.size)
        for (binding in plan.orderedBindings) {
            when (val rendered = render(binding)) {
                is BindingRenderResult.Ready -> literals += rendered.literal
                BindingRenderResult.MetadataUnsupported ->
                    return NativeExecutionPlanMaterializer.failed(
                        PreparedPlanSqlMaterializationFailureKind.BINDING_METADATA_UNSUPPORTED,
                        BINDING_METADATA_UNSUPPORTED,
                    )
                BindingRenderResult.ValueUnsupported ->
                    return NativeExecutionPlanMaterializer.failed(
                        PreparedPlanSqlMaterializationFailureKind.BINDING_VALUE_UNSUPPORTED,
                        BINDING_VALUE_UNSUPPORTED,
                    )
            }
        }

        val sql = buildString(plan.sqlWithPlaceholders.length + literals.sumOf { it.length }) {
            var cursor = 0
            markers.forEachIndexed { index, marker ->
                append(plan.sqlWithPlaceholders, cursor, marker)
                append(literals[index])
                cursor = marker + 1
            }
            append(plan.sqlWithPlaceholders, cursor, plan.sqlWithPlaceholders.length)
        }
        return NativeExecutionPlanMaterializer.ready(plan, sql)
    }

    private fun render(binding: PreparedBinding): BindingRenderResult {
        val metadata = binding.metadata
        if (metadata.parameterMode != "IN" || metadata.numericScale != null) {
            return BindingRenderResult.MetadataUnsupported
        }
        val jdbcType = metadata.effectiveJdbcTypeIdentity ?: metadata.jdbcTypeIdentity
            ?: return BindingRenderResult.MetadataUnsupported

        if (binding.value == InputValue.NullValue) {
            return renderTypedNull(
                mappingType = metadata.mappingJavaTypeIdentity,
                handler = metadata.typeHandlerIdentity,
                jdbcType = jdbcType,
            )
        }

        return when (val value = binding.value) {
            is InputValue.IntegerValue -> renderInteger(binding, jdbcType, value.value)
            is InputValue.DecimalValue -> renderDecimal(binding, jdbcType, value.value.toPlainString())
            is InputValue.BooleanValue ->
                if (matchesStockBinding(binding, BOOLEAN_SPEC, jdbcType)) {
                    BindingRenderResult.Ready(if (value.value) "TRUE" else "FALSE")
                } else {
                    BindingRenderResult.MetadataUnsupported
                }
            is InputValue.Text ->
                if (matchesStockBinding(binding, VARCHAR_SPEC, jdbcType)) {
                    postgreSqlTextLiteral(value.value)
                        ?.let(BindingRenderResult::Ready)
                        ?: BindingRenderResult.ValueUnsupported
                } else {
                    BindingRenderResult.MetadataUnsupported
                }
            is InputValue.ArrayValue -> renderBinary(binding, jdbcType, value)
            is InputValue.DateValue ->
                if (matchesStockBinding(binding, DATE_SPEC, jdbcType)) {
                    BindingRenderResult.Ready("DATE '${value.value}'")
                } else {
                    BindingRenderResult.MetadataUnsupported
                }
            is InputValue.TimeValue ->
                if (matchesStockBinding(binding, TIME_SPEC, jdbcType)) {
                    BindingRenderResult.Ready("TIME '${value.value}'")
                } else {
                    BindingRenderResult.MetadataUnsupported
                }
            is InputValue.DateTimeValue ->
                if (matchesStockBinding(binding, TIMESTAMP_SPEC, jdbcType)) {
                    BindingRenderResult.Ready(
                        "TIMESTAMP '${value.value.toString().replace('T', ' ')}'",
                    )
                } else {
                    BindingRenderResult.MetadataUnsupported
                }
            else -> BindingRenderResult.MetadataUnsupported
        }
    }

    private fun renderInteger(
        binding: PreparedBinding,
        jdbcType: String,
        value: BigInteger,
    ): BindingRenderResult {
        val spec = INTEGER_SPECS[jdbcType] ?: return BindingRenderResult.MetadataUnsupported
        if (!matchesStockBinding(binding, spec, jdbcType)) {
            return BindingRenderResult.MetadataUnsupported
        }
        return if (value in spec.minInteger!!..spec.maxInteger!!) {
            BindingRenderResult.Ready(value.toString())
        } else {
            BindingRenderResult.ValueUnsupported
        }
    }

    private fun renderDecimal(
        binding: PreparedBinding,
        jdbcType: String,
        literal: String,
    ): BindingRenderResult {
        val spec = DECIMAL_SPECS[jdbcType] ?: return BindingRenderResult.MetadataUnsupported
        return if (matchesStockBinding(binding, spec, jdbcType)) {
            BindingRenderResult.Ready(literal)
        } else {
            BindingRenderResult.MetadataUnsupported
        }
    }

    private fun renderBinary(
        binding: PreparedBinding,
        jdbcType: String,
        value: InputValue.ArrayValue,
    ): BindingRenderResult {
        if (!matchesStockBinding(binding, BINARY_SPEC, jdbcType)) {
            return BindingRenderResult.MetadataUnsupported
        }

        val hex = StringBuilder(value.elements.size * 2)
        for (element in value.elements) {
            val integer = (element as? InputValue.IntegerValue)?.value
                ?: return BindingRenderResult.ValueUnsupported
            if (integer !in BYTE_MIN..BYTE_MAX) {
                return BindingRenderResult.ValueUnsupported
            }
            val byte = integer.toInt() and 0xff
            hex.append(HEX_DIGITS[byte ushr 4])
            hex.append(HEX_DIGITS[byte and 0x0f])
        }
        return BindingRenderResult.Ready("decode('${hex}', 'hex')")
    }

    private fun matchesStockBinding(
        binding: PreparedBinding,
        spec: BindingSpec,
        jdbcType: String,
    ): Boolean {
        if (jdbcType !in spec.jdbcTypes) return false
        val metadata = binding.metadata
        val mappingType = metadata.mappingJavaTypeIdentity
        val handler = metadata.typeHandlerIdentity
        return (mappingType in spec.javaTypes && handler in spec.handlers) ||
            (
                mappingType == OBJECT_TYPE &&
                    handler == UNKNOWN_HANDLER &&
                    metadata.declaredJavaTypeIdentity?.value in spec.javaTypes
                )
    }

    private fun renderTypedNull(
        mappingType: String?,
        handler: String?,
        jdbcType: String,
    ): BindingRenderResult {
        val spec = NULL_SPECS[jdbcType] ?: return BindingRenderResult.MetadataUnsupported
        val supported =
            (mappingType in spec.javaTypes && handler in spec.handlers) ||
                (
                    mappingType == OBJECT_TYPE &&
                        handler in setOf(UNKNOWN_HANDLER, OBJECT_HANDLER)
                    )
        return if (supported) {
            BindingRenderResult.Ready("CAST(NULL AS ${spec.postgreSqlType})")
        } else {
            BindingRenderResult.MetadataUnsupported
        }
    }

    /**
     * E-strings make backslash semantics independent of the session's standard_conforming_strings
     * setting. Every input backslash is doubled and every quote is SQL-escaped. PostgreSQL text
     * cannot contain NUL. Invalid/unpaired UTF-16 surrogates are also rejected before the IDE/JDBC
     * encoding boundary.
     */
    private fun postgreSqlTextLiteral(value: String): String? {
        if ('\u0000' in value || !isWellFormedUtf16(value)) return null
        return buildString(value.length + 3) {
            append("E'")
            value.forEach { c ->
                when (c) {
                    '\\' -> append("\\\\")
                    '\'' -> append("''")
                    else -> append(c)
                }
            }
            append('\'')
        }
    }

    private fun isWellFormedUtf16(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val current = value[index]
            when {
                current.isHighSurrogate() -> {
                    if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return false
                    index += 2
                }
                current.isLowSurrogate() -> return false
                else -> index += 1
            }
        }
        return true
    }

    private data class BindingSpec(
        val jdbcTypes: Set<String>,
        val javaTypes: Set<String>,
        val handlers: Set<String>,
        val postgreSqlType: String,
        val minInteger: BigInteger? = null,
        val maxInteger: BigInteger? = null,
    )

    private sealed interface BindingRenderResult {
        data class Ready(val literal: String) : BindingRenderResult
        data object MetadataUnsupported : BindingRenderResult
        data object ValueUnsupported : BindingRenderResult
    }

    private val BYTE_MIN = BigInteger.valueOf(Byte.MIN_VALUE.toLong())
    private val BYTE_MAX = BigInteger.valueOf(Byte.MAX_VALUE.toLong())
    private val SHORT_MIN = BigInteger.valueOf(Short.MIN_VALUE.toLong())
    private val SHORT_MAX = BigInteger.valueOf(Short.MAX_VALUE.toLong())
    private val INT_MIN = BigInteger.valueOf(Int.MIN_VALUE.toLong())
    private val INT_MAX = BigInteger.valueOf(Int.MAX_VALUE.toLong())
    private val LONG_MIN: BigInteger = BigInteger.valueOf(Long.MIN_VALUE)
    private val LONG_MAX: BigInteger = BigInteger.valueOf(Long.MAX_VALUE)

    private val TINYINT_SPEC = BindingSpec(
        jdbcTypes = setOf("TINYINT"),
        javaTypes = setOf("byte", "java.lang.Byte"),
        handlers = setOf("org.apache.ibatis.type.ByteTypeHandler"),
        postgreSqlType = "smallint",
        minInteger = BYTE_MIN,
        maxInteger = BYTE_MAX,
    )
    private val SMALLINT_SPEC = BindingSpec(
        jdbcTypes = setOf("SMALLINT"),
        javaTypes = setOf("short", "java.lang.Short"),
        handlers = setOf("org.apache.ibatis.type.ShortTypeHandler"),
        postgreSqlType = "smallint",
        minInteger = SHORT_MIN,
        maxInteger = SHORT_MAX,
    )
    private val INTEGER_SPEC = BindingSpec(
        jdbcTypes = setOf("INTEGER"),
        javaTypes = setOf("int", "java.lang.Integer"),
        handlers = setOf("org.apache.ibatis.type.IntegerTypeHandler"),
        postgreSqlType = "integer",
        minInteger = INT_MIN,
        maxInteger = INT_MAX,
    )
    private val BIGINT_SPEC = BindingSpec(
        jdbcTypes = setOf("BIGINT"),
        javaTypes = setOf("long", "java.lang.Long"),
        handlers = setOf("org.apache.ibatis.type.LongTypeHandler"),
        postgreSqlType = "bigint",
        minInteger = LONG_MIN,
        maxInteger = LONG_MAX,
    )
    private val REAL_SPEC = BindingSpec(
        jdbcTypes = setOf("REAL"),
        javaTypes = setOf("float", "java.lang.Float"),
        handlers = setOf("org.apache.ibatis.type.FloatTypeHandler"),
        postgreSqlType = "real",
    )
    private val DOUBLE_SPEC = BindingSpec(
        jdbcTypes = setOf("DOUBLE"),
        javaTypes = setOf("double", "java.lang.Double"),
        handlers = setOf("org.apache.ibatis.type.DoubleTypeHandler"),
        postgreSqlType = "double precision",
    )
    private val DECIMAL_SPEC = BindingSpec(
        jdbcTypes = setOf("DECIMAL"),
        javaTypes = setOf("java.math.BigDecimal"),
        handlers = setOf("org.apache.ibatis.type.BigDecimalTypeHandler"),
        postgreSqlType = "numeric",
    )
    private val BOOLEAN_SPEC = BindingSpec(
        jdbcTypes = setOf("BOOLEAN"),
        javaTypes = setOf("boolean", "java.lang.Boolean"),
        handlers = setOf("org.apache.ibatis.type.BooleanTypeHandler"),
        postgreSqlType = "boolean",
    )
    private val VARCHAR_SPEC = BindingSpec(
        jdbcTypes = setOf("VARCHAR"),
        javaTypes = setOf("java.lang.String"),
        handlers = setOf("org.apache.ibatis.type.StringTypeHandler"),
        postgreSqlType = "varchar",
    )
    private val BINARY_SPEC = BindingSpec(
        jdbcTypes = setOf("VARBINARY"),
        javaTypes = setOf("[B", "byte[]"),
        handlers = setOf("org.apache.ibatis.type.ByteArrayTypeHandler"),
        postgreSqlType = "bytea",
    )
    private val DATE_SPEC = BindingSpec(
        jdbcTypes = setOf("DATE"),
        javaTypes = setOf("java.time.LocalDate"),
        handlers = setOf("org.apache.ibatis.type.LocalDateTypeHandler"),
        postgreSqlType = "date",
    )
    private val TIME_SPEC = BindingSpec(
        jdbcTypes = setOf("TIME"),
        javaTypes = setOf("java.time.LocalTime"),
        handlers = setOf("org.apache.ibatis.type.LocalTimeTypeHandler"),
        postgreSqlType = "time",
    )
    private val TIMESTAMP_SPEC = BindingSpec(
        jdbcTypes = setOf("TIMESTAMP"),
        javaTypes = setOf("java.time.LocalDateTime"),
        handlers = setOf("org.apache.ibatis.type.LocalDateTimeTypeHandler"),
        postgreSqlType = "timestamp",
    )

    private val INTEGER_SPECS = mapOf(
        "TINYINT" to TINYINT_SPEC,
        "SMALLINT" to SMALLINT_SPEC,
        "INTEGER" to INTEGER_SPEC,
        "BIGINT" to BIGINT_SPEC,
    )
    private val DECIMAL_SPECS = mapOf(
        "REAL" to REAL_SPEC,
        "DOUBLE" to DOUBLE_SPEC,
        "DECIMAL" to DECIMAL_SPEC,
    )
    private val NULL_SPECS = buildMap {
        put("TINYINT", TINYINT_SPEC)
        put("SMALLINT", SMALLINT_SPEC)
        put("INTEGER", INTEGER_SPEC)
        put("BIGINT", BIGINT_SPEC)
        put("REAL", REAL_SPEC)
        put("DOUBLE", DOUBLE_SPEC)
        put("DECIMAL", DECIMAL_SPEC)
        put("BOOLEAN", BOOLEAN_SPEC)
        put("VARCHAR", VARCHAR_SPEC)
        put("VARBINARY", BINARY_SPEC)
        put("DATE", DATE_SPEC)
        put("TIME", TIME_SPEC)
        put("TIMESTAMP", TIMESTAMP_SPEC)
    }

    private const val OBJECT_TYPE = "java.lang.Object"
    private const val UNKNOWN_HANDLER = "org.apache.ibatis.type.UnknownTypeHandler"
    private const val OBJECT_HANDLER = "org.apache.ibatis.type.ObjectTypeHandler"
    private const val HEX_DIGITS = "0123456789abcdef"
    private const val BINDING_METADATA_UNSUPPORTED = "prepared-plan-sql-binding-metadata-unsupported"
    private const val BINDING_VALUE_UNSUPPORTED = "prepared-plan-sql-binding-value-unsupported"
    private const val PLACEHOLDER_TOPOLOGY_UNSUPPORTED = "prepared-plan-sql-placeholder-topology-unsupported"

}

/**
 * Returns only executable bind markers. Question marks inside strings, quoted identifiers,
 * comments and dollar-quoted bodies are ignored. PostgreSQL question-mark operators are skipped
 * rather than consuming a MyBatis binding.
 */
private object PostgreSqlBindMarkerScanner {
    fun locate(sql: String): List<Int>? {
        val markers = mutableListOf<Int>()
        var index = 0
        var blockCommentDepth = 0
        var dollarDelimiter: String? = null
        var state = State.NORMAL

        while (index < sql.length) {
            val c = sql[index]
            when (state) {
                State.NORMAL -> {
                    val delimiter = dollarQuoteDelimiterAt(sql, index)
                    when {
                        c == '\'' -> state = State.SINGLE_QUOTE
                        c == '"' -> state = State.DOUBLE_QUOTE
                        c == '-' && sql.getOrNull(index + 1) == '-' -> {
                            state = State.LINE_COMMENT
                            index += 1
                        }
                        c == '/' && sql.getOrNull(index + 1) == '*' -> {
                            state = State.BLOCK_COMMENT
                            blockCommentDepth = 1
                            index += 1
                        }
                        delimiter != null -> {
                            state = State.DOLLAR_QUOTE
                            dollarDelimiter = delimiter
                            index += delimiter.length - 1
                        }
                        c == '?' && !looksLikeQuestionOperator(sql, index) -> markers += index
                    }
                }
                State.SINGLE_QUOTE -> when (c) {
                    '\\' -> if (index + 1 < sql.length) index += 1
                    '\'' -> if (sql.getOrNull(index + 1) == '\'') {
                        index += 1
                    } else {
                        state = State.NORMAL
                    }
                }
                State.DOUBLE_QUOTE -> if (c == '"') {
                    if (sql.getOrNull(index + 1) == '"') {
                        index += 1
                    } else {
                        state = State.NORMAL
                    }
                }
                State.LINE_COMMENT -> if (c == '\n' || c == '\r') state = State.NORMAL
                State.BLOCK_COMMENT -> {
                    if (c == '/' && sql.getOrNull(index + 1) == '*') {
                        blockCommentDepth += 1
                        index += 1
                    } else if (c == '*' && sql.getOrNull(index + 1) == '/') {
                        blockCommentDepth -= 1
                        index += 1
                        if (blockCommentDepth == 0) state = State.NORMAL
                    }
                }
                State.DOLLAR_QUOTE -> {
                    val delimiter = dollarDelimiter ?: return null
                    if (sql.startsWith(delimiter, index)) {
                        index += delimiter.length - 1
                        dollarDelimiter = null
                        state = State.NORMAL
                    }
                }
            }
            index += 1
        }

        return if (state == State.NORMAL || state == State.LINE_COMMENT) markers else null
    }

    private fun looksLikeQuestionOperator(sql: String, offset: Int): Boolean {
        val next = nextSignificant(sql, offset + 1)
        if (next == '|' || next == '&' || next == '?') return true
        val previousToken = previousToken(sql, offset)
        val nextToken = nextToken(sql, offset)
        return previousToken?.isOperand == true && nextToken?.isOperand == true
    }

    private fun previousToken(sql: String, offset: Int): Token? {
        var index = offset - 1
        while (index >= 0 && sql[index].isWhitespace()) index -= 1
        if (index < 0) return null
        val c = sql[index]
        if (c.isLetterOrDigit() || c == '_' || c == '$') {
            val end = index + 1
            while (index >= 0 && (sql[index].isLetterOrDigit() || sql[index] == '_' || sql[index] == '$')) {
                index -= 1
            }
            val value = sql.substring(index + 1, end).uppercase()
            return Token(value !in NON_OPERAND_PREVIOUS_KEYWORDS)
        }
        return Token(c == ')' || c == ']' || c == '\'' || c == '"')
    }

    private fun nextToken(sql: String, offset: Int): Token? {
        var index = offset + 1
        while (index < sql.length && sql[index].isWhitespace()) index += 1
        if (index >= sql.length) return null
        val c = sql[index]
        if (c.isLetterOrDigit() || c == '_' || c == '$') {
            val start = index
            while (index < sql.length && (sql[index].isLetterOrDigit() || sql[index] == '_' || sql[index] == '$')) {
                index += 1
            }
            val value = sql.substring(start, index).uppercase()
            return Token(value !in NON_OPERAND_NEXT_KEYWORDS)
        }
        return Token(c == '(' || c == '[' || c == '\'' || c == '"')
    }

    private fun nextSignificant(sql: String, start: Int): Char? {
        var index = start
        while (index < sql.length && sql[index].isWhitespace()) index += 1
        return sql.getOrNull(index)
    }

    private fun dollarQuoteDelimiterAt(sql: String, offset: Int): String? {
        if (sql[offset] != '$') return null
        var index = offset + 1
        if (sql.getOrNull(index) == '$') return "$$"
        if (sql.getOrNull(index)?.let { it.isLetter() || it == '_' } != true) return null
        index += 1
        while (index < sql.length && (sql[index].isLetterOrDigit() || sql[index] == '_')) index += 1
        if (sql.getOrNull(index) != '$') return null
        return sql.substring(offset, index + 1)
    }

    private data class Token(val isOperand: Boolean)

    private enum class State {
        NORMAL,
        SINGLE_QUOTE,
        DOUBLE_QUOTE,
        LINE_COMMENT,
        BLOCK_COMMENT,
        DOLLAR_QUOTE,
    }

    private val NON_OPERAND_PREVIOUS_KEYWORDS = setOf(
        "SELECT", "WHERE", "AND", "OR", "NOT", "IN", "VALUES", "SET", "BY", "LIMIT", "OFFSET",
        "RETURNING", "WHEN", "THEN", "ELSE", "CASE", "ON", "HAVING", "AS", "DISTINCT", "FROM",
    )
    private val NON_OPERAND_NEXT_KEYWORDS = setOf(
        "AS", "FROM", "WHERE", "AND", "OR", "GROUP", "ORDER", "LIMIT", "OFFSET", "RETURNING",
        "WHEN", "THEN", "ELSE", "END", "HAVING", "UNION",
    )
}
