package com.algorist.zMyBatis.core.materialization

import com.algorist.zMyBatis.core.execution.ResolvedExecutionTarget
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.preparation.PreparedBinding
import com.algorist.zMyBatis.core.preparation.PreparedExecution
import com.algorist.zMyBatis.core.source.ConventionalMyBatisDatabaseIds
import java.math.BigInteger

/**
 * First #391/#393 executable SQL-text proof slice.
 *
 * This is intentionally not a generic PostgreSQL renderer. It accepts exactly one stock-MyBatis
 * BIGINT mapping backed either directly by LongTypeHandler or by MyBatis' stock
 * Object/UnknownTypeHandler path (which resolves a non-null Long to LongTypeHandler at binding
 * time), plus a signed 64-bit caller value. Every
 * other binding/type/handler/topology fails closed. A target-derived conventional PostgreSQL
 * database id is mandatory; target selection itself remains DBMS-neutral. The selected execution
 * target and proven dialect are captured in the immutable artifact so preview and dispatch bind to
 * the same authority.
 */
class PostgreSqlBigintExecutionMaterializer(
    private val target: ResolvedExecutionTarget,
) : ExecutionMaterializer {
    override fun materialize(prepared: PreparedExecution): MaterializationResult {
        if (target.effectiveDatabaseId?.value != ConventionalMyBatisDatabaseIds.POSTGRESQL) {
            return failed(
                MaterializationFailureKind.DIALECT_UNSUPPORTED,
                DIALECT_UNSUPPORTED,
            )
        }
        if (prepared.rawInterpolations.isNotEmpty()) {
            return failed(
                MaterializationFailureKind.RAW_INTERPOLATION_REQUIRES_POLICY,
                RAW_INTERPOLATION_POLICY_REQUIRED,
            )
        }
        if (prepared.orderedBindings.size != 1) {
            return failed(
                MaterializationFailureKind.BINDING_COUNT_UNSUPPORTED,
                BINDING_COUNT_UNSUPPORTED,
            )
        }

        val binding = prepared.orderedBindings.single()
        val value = supportedBigint(binding)
            ?: return failed(
                MaterializationFailureKind.BINDING_METADATA_UNSUPPORTED,
                BINDING_METADATA_UNSUPPORTED,
            )
        if (value !in LONG_MIN..LONG_MAX) {
            return failed(
                MaterializationFailureKind.BINDING_VALUE_UNSUPPORTED,
                BINDING_VALUE_UNSUPPORTED,
            )
        }

        val marker = PostgreSqlSingleBindMarkerScanner.locate(prepared.sqlWithPlaceholders)
            ?: return failed(
                MaterializationFailureKind.PLACEHOLDER_TOPOLOGY_UNSUPPORTED,
                PLACEHOLDER_TOPOLOGY_UNSUPPORTED,
            )
        val executableSql = buildString(prepared.sqlWithPlaceholders.length + 20) {
            append(prepared.sqlWithPlaceholders, 0, marker)
            append(value.toString())
            append(prepared.sqlWithPlaceholders, marker + 1, prepared.sqlWithPlaceholders.length)
        }
        return materialized(
            prepared = prepared,
            executionSql = executableSql,
            executionTargetId = target.targetId,
            executionDialectId = ConventionalMyBatisDatabaseIds.POSTGRESQL,
        )
    }

    private fun supportedBigint(binding: PreparedBinding): BigInteger? {
        val value = (binding.value as? InputValue.IntegerValue)?.value ?: return null
        val metadata = binding.metadata
        if (metadata.declaredJavaTypeIdentity?.value !in LONG_JAVA_TYPES) return null
        if (metadata.jdbcTypeIdentity != "BIGINT") return null
        if (!isStockLongBindingPath(metadata.mappingJavaTypeIdentity, metadata.typeHandlerIdentity)) return null
        if (metadata.parameterMode != "IN" || metadata.numericScale != null) return null
        if (binding.additionalParameter) return null
        return value
    }

    private fun isStockLongBindingPath(
        mappingJavaTypeIdentity: String?,
        typeHandlerIdentity: String?,
    ): Boolean =
        (mappingJavaTypeIdentity == "java.lang.Long" &&
            typeHandlerIdentity == "org.apache.ibatis.type.LongTypeHandler") ||
            (
                mappingJavaTypeIdentity == "java.lang.Object" &&
                    typeHandlerIdentity == "org.apache.ibatis.type.UnknownTypeHandler"
                )

    private companion object {
        val LONG_MIN: BigInteger = BigInteger.valueOf(Long.MIN_VALUE)
        val LONG_MAX: BigInteger = BigInteger.valueOf(Long.MAX_VALUE)
        val LONG_JAVA_TYPES = setOf("long", "java.lang.Long")
        const val DIALECT_UNSUPPORTED = "postgresql-bigint-dialect-unsupported"
        const val BINDING_COUNT_UNSUPPORTED = "postgresql-bigint-binding-count-unsupported"
        const val BINDING_METADATA_UNSUPPORTED = "postgresql-bigint-binding-metadata-unsupported"
        const val BINDING_VALUE_UNSUPPORTED = "postgresql-bigint-binding-value-unsupported"
        const val PLACEHOLDER_TOPOLOGY_UNSUPPORTED = "postgresql-bigint-placeholder-topology-unsupported"
        const val RAW_INTERPOLATION_POLICY_REQUIRED = "materialization-raw-interpolation-policy-required"
    }
}

/**
 * Locates exactly one executable bind marker without regex replacement.
 *
 * For this first slice, any additional question mark anywhere is unsupported. This deliberately
 * rejects quoted/comment question marks and PostgreSQL question-mark operators when a bound value
 * is present rather than guessing which mark came from MyBatis. The one surviving mark must also
 * be outside quoted identifiers/literals, comments and dollar-quoted text.
 */
private object PostgreSqlSingleBindMarkerScanner {
    fun locate(sql: String): Int? {
        if (sql.count { it == '?' } != 1) return null

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
                        c == '?' -> {
                            if (looksLikeQuestionOperator(sql, index)) return null
                            return index
                        }
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
                State.DOUBLE_QUOTE -> when (c) {
                    '"' -> if (sql.getOrNull(index + 1) == '"') {
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
        return null
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
