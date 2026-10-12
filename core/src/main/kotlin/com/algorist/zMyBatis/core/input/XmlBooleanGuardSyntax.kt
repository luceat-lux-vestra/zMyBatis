package com.algorist.zMyBatis.core.input

/**
 * Lexical evidence about a *bounded* XML Boolean <if> guard, not an OGNL interpreter.
 *
 * Stock MyBatis evaluates the unchanged expression. The value polarity here is used only to
 * verify that ordered source-proven placeholders match what MyBatis actually emitted.
 * Comparing anything except an independently authenticated Boolean caller is forbidden.
 */
object XmlBooleanGuardSyntax {
    private val simpleAlias = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val booleanComparison = Regex("([A-Za-z_][A-Za-z0-9_]*) (==|!=) (true|false)")

    data class Parsed(
        val callerAlias: String,
        val activeWhenTrue: Boolean,
        val requiresSingleSelect: Boolean,
    ) {
        fun isActive(value: Boolean): Boolean = value == activeWhenTrue
    }

    fun parse(expression: String): Parsed? {
        if (simpleAlias.matches(expression)) {
            return Parsed(callerAlias = expression, activeWhenTrue = true, requiresSingleSelect = false)
        }
        if (expression.startsWith('!') && simpleAlias.matches(expression.substring(1))) {
            return Parsed(callerAlias = expression.substring(1), activeWhenTrue = false, requiresSingleSelect = true)
        }
        val match = booleanComparison.matchEntire(expression) ?: return null
        val (alias, operator, literal) = match.destructured
        val positive = (operator == "==" && literal == "true") ||
            (operator == "!=" && literal == "false")
        return Parsed(callerAlias = alias, activeWhenTrue = positive, requiresSingleSelect = true)
    }
}
