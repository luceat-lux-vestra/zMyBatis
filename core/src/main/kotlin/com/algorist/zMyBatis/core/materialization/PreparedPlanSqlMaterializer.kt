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

        return when (val value = binding.value) {
            InputValue.NullValue -> {
                if (jdbcType !in SUPPORTED_NULL_JDBC_TYPES || metadata.typeHandlerIdentity !in SUPPORTED_NULL_HANDLERS) {
                    BindingRenderResult.MetadataUnsupported
                } else {
                    BindingRenderResult.Ready("NULL")
                }
            }
            is InputValue.IntegerValue -> {
                if (
                    jdbcType != "BIGINT" ||
                    metadata.typeHandlerIdentity !in BIGINT_HANDLERS ||
                    metadata.mappingJavaTypeIdentity !in BIGINT_MAPPING_TYPES
                ) {
                    BindingRenderResult.MetadataUnsupported
                } else if (value.value !in LONG_MIN..LONG_MAX) {
                    BindingRenderResult.ValueUnsupported
                } else {
                    BindingRenderResult.Ready(value.value.toString())
                }
            }
            is InputValue.Text -> {
                if (
                    jdbcType != "VARCHAR" ||
                    metadata.typeHandlerIdentity !in VARCHAR_HANDLERS ||
                    metadata.mappingJavaTypeIdentity !in VARCHAR_MAPPING_TYPES
                ) {
                    BindingRenderResult.MetadataUnsupported
                } else {
                    postgreSqlTextLiteral(value.value)
                        ?.let(BindingRenderResult::Ready)
                        ?: BindingRenderResult.ValueUnsupported
                }
            }
            else -> BindingRenderResult.MetadataUnsupported
        }
    }

    /**
     * E-strings make backslash semantics independent of the session's standard_conforming_strings
     * setting. Every input backslash is doubled and every quote is SQL-escaped. PostgreSQL text
     * cannot contain NUL, so that value fails closed.
     */
    private fun postgreSqlTextLiteral(value: String): String? {
        if ('\u0000' in value) return null
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

    private sealed interface BindingRenderResult {
        data class Ready(val literal: String) : BindingRenderResult
        data object MetadataUnsupported : BindingRenderResult
        data object ValueUnsupported : BindingRenderResult
    }

    private val LONG_MIN: BigInteger = BigInteger.valueOf(Long.MIN_VALUE)
    private val LONG_MAX: BigInteger = BigInteger.valueOf(Long.MAX_VALUE)
    private val BIGINT_MAPPING_TYPES = setOf("long", "java.lang.Long", "java.lang.Object")
    private val VARCHAR_MAPPING_TYPES = setOf("java.lang.String", "java.lang.Object")
    private val BIGINT_HANDLERS = setOf(
        "org.apache.ibatis.type.LongTypeHandler",
        "org.apache.ibatis.type.UnknownTypeHandler",
    )
    private val VARCHAR_HANDLERS = setOf(
        "org.apache.ibatis.type.StringTypeHandler",
        "org.apache.ibatis.type.UnknownTypeHandler",
    )
    private val SUPPORTED_NULL_HANDLERS = BIGINT_HANDLERS + VARCHAR_HANDLERS
    private val SUPPORTED_NULL_JDBC_TYPES = setOf("BIGINT", "VARCHAR")

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
