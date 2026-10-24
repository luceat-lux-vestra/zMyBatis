package com.algorist.zMyBatis.core.input

import java.math.BigInteger

/**
 * Lexical evidence about a bounded XML <if> guard, not an OGNL interpreter.
 *
 * Stock MyBatis evaluates the unchanged expression. zMyBatis uses this narrow source grammar only
 * to prove one caller root and, when bound mappings are conditionally present, to validate mapping
 * cardinality against the same already-authenticated scalar value.
 */
object XmlBooleanGuardSyntax {
    private val simpleAlias = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val booleanComparison = Regex("([A-Za-z_][A-Za-z0-9_]*) (==|!=) (true|false)")
    private val positiveIntegerComparison = Regex("([A-Za-z_][A-Za-z0-9_]*) > 0")

    enum class CallerValueKind {
        BOOLEAN,
        INTEGER,
    }

    data class Parsed(
        val callerAlias: String,
        val activeWhenTrue: Boolean,
        val requiresSingleSelect: Boolean,
        val callerValueKind: CallerValueKind,
    ) {
        fun accepts(expectedType: ExpectedInputType): Boolean =
            expectedType.shape == InputShape.SCALAR &&
                when (callerValueKind) {
                    CallerValueKind.BOOLEAN ->
                        expectedType.scalarType == InputScalarType.BOOLEAN
                    CallerValueKind.INTEGER ->
                        expectedType.scalarType == InputScalarType.INTEGER
                }

        fun isActive(value: Any?): Boolean? = when (callerValueKind) {
            CallerValueKind.BOOLEAN ->
                (value as? Boolean)?.let { it == activeWhenTrue }
            CallerValueKind.INTEGER -> when (value) {
                is BigInteger -> value.signum() > 0
                is Byte -> value > 0
                is Short -> value > 0
                is Int -> value > 0
                is Long -> value > 0
                else -> null
            }
        }
    }

    fun parse(expression: String): Parsed? {
        if (simpleAlias.matches(expression)) {
            return Parsed(
                callerAlias = expression,
                activeWhenTrue = true,
                requiresSingleSelect = false,
                callerValueKind = CallerValueKind.BOOLEAN,
            )
        }
        if (expression.startsWith('!') && simpleAlias.matches(expression.substring(1))) {
            return Parsed(
                callerAlias = expression.substring(1),
                activeWhenTrue = false,
                requiresSingleSelect = true,
                callerValueKind = CallerValueKind.BOOLEAN,
            )
        }
        booleanComparison.matchEntire(expression)?.let { match ->
            val (alias, operator, literal) = match.destructured
            val positive = (operator == "==" && literal == "true") ||
                (operator == "!=" && literal == "false")
            return Parsed(
                callerAlias = alias,
                activeWhenTrue = positive,
                requiresSingleSelect = true,
                callerValueKind = CallerValueKind.BOOLEAN,
            )
        }
        positiveIntegerComparison.matchEntire(expression)?.let { match ->
            return Parsed(
                callerAlias = match.groupValues[1],
                activeWhenTrue = true,
                requiresSingleSelect = true,
                callerValueKind = CallerValueKind.INTEGER,
            )
        }
        return null
    }
}
