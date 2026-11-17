package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.preparation.PreparationFailure
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import java.util.ArrayDeque
import org.apache.ibatis.builder.xml.XMLMapperEntityResolver
import org.apache.ibatis.parsing.XNode
import org.apache.ibatis.parsing.XPathParser

/**
 * Safety-only OGNL preflight for P2.
 *
 * Standard MyBatis expression semantics are not reimplemented here. The scanner only extracts
 * runtime OGNL expressions and delegates capability/resource inspection to the isolated shaded OGNL
 * parser. Caller-property existence and standard AST node shapes are intentionally not admitted here.
 */
internal object DynamicOgnlSafetyAdmission {
    private const val UNSUPPORTED = "mybatis-ognl-runtime-capability-unsupported"
    private const val MALFORMED = "mybatis-ognl-expression-malformed"
    private const val SCRIPT_MALFORMED = "mybatis-dynamic-script-malformed"
    private const val INVARIANT = "mybatis-ognl-safety-invariant"
    private const val DATABASE_ID_AUTHORITY_UNAVAILABLE = "mybatis-database-id-authority-unavailable"
    private const val MAX_XML_LENGTH = 2 * 1024 * 1024
    private const val MAX_DYNAMIC_NODES = 65_536
    private const val MAX_DYNAMIC_DEPTH = 128
    private val rawExpression = Regex("""\$\{([^}]*)}""")
    private val reservedBindNames = setOf("_parameter", "_databaseId")

    fun failureForScript(
        script: String,
        databaseIdAvailable: Boolean = true,
    ): PreparationFailure? =
        inspectDocuments(listOf(script), "/script", databaseIdAvailable)

    fun failureForMapperDocuments(
        contents: List<String>,
        databaseIdAvailable: Boolean = true,
    ): PreparationFailure? =
        contents.asSequence()
            .mapNotNull { content -> inspectDocuments(listOf(content), "/mapper", databaseIdAvailable) }
            .firstOrNull()

    fun failureForExpressions(
        expressions: List<String>,
        databaseIdAvailable: Boolean = true,
    ): PreparationFailure? =
        inspectExpressions(expressions, databaseIdAvailable)


    private fun inspectDocuments(
        contents: List<String>,
        rootPath: String,
        databaseIdAvailable: Boolean,
    ): PreparationFailure? {
        val expressions = mutableListOf<String>()
        for (content in contents) {
            if (content.length > MAX_XML_LENGTH) return unsupported("DynamicScript[length]")
            val root = try {
                XPathParser(content, false, null, XMLMapperEntityResolver()).evalNode(rootPath)
            } catch (failure: StackOverflowError) {
                return unsupported("DynamicScript[parser-depth]")
            } catch (failure: RuntimeException) {
                return PreparationFailure(
                    PreparationFailureKind.MYBATIS_PARSE,
                    SCRIPT_MALFORMED,
                    diagnosticType = failure.javaClass.name,
                )
            } ?: return PreparationFailure(
                PreparationFailureKind.MYBATIS_PARSE,
                SCRIPT_MALFORMED,
            )

            val pending = ArrayDeque<Pair<XNode, Int>>()
            pending.addLast(root to 0)
            var visited = 0
            while (pending.isNotEmpty()) {
                if (++visited > MAX_DYNAMIC_NODES) return unsupported("DynamicScript[node-budget]")
                val (node, depth) = pending.removeLast()
                if (depth > MAX_DYNAMIC_DEPTH) return unsupported("DynamicScript[depth]")

                when (node.name) {
                    "if", "when" ->
                        node.getStringAttribute("test")?.let(expressions::add)
                    "foreach" ->
                        node.getStringAttribute("collection")?.let(expressions::add)
                    "bind" -> {
                        val name = node.getStringAttribute("name")
                        if (name in reservedBindNames) return unsupported("Bind[reserved-context]")
                        node.getStringAttribute("value")?.let(expressions::add)
                    }
                }

                val body = node.stringBody
                if (!body.isNullOrEmpty()) {
                    rawExpression.findAll(body)
                        .map { it.groupValues[1].trim() }
                        .filter(String::isNotEmpty)
                        .forEach(expressions::add)
                }

                val children = node.children
                for (index in children.indices.reversed()) {
                    pending.addLast(children[index] to depth + 1)
                }
            }
        }

        return inspectExpressions(expressions, databaseIdAvailable)
    }

    private fun inspectExpressions(
        expressions: List<String>,
        databaseIdAvailable: Boolean,
    ): PreparationFailure? {
        if (!databaseIdAvailable && expressions.any(::referencesDatabaseId)) {
            return PreparationFailure(
                PreparationFailureKind.UNSUPPORTED_SEMANTIC,
                DATABASE_ID_AUTHORITY_UNAVAILABLE,
            )
        }
        return when (val result = IsolatedOgnlAstAdmission.inspectCapabilities(expressions.distinct())) {
            IsolatedOgnlAstAdmission.Result.Admitted -> null
            is IsolatedOgnlAstAdmission.Result.Unsupported -> unsupported(result.nodeType)
            is IsolatedOgnlAstAdmission.Result.Malformed -> PreparationFailure(
                PreparationFailureKind.OGNL,
                MALFORMED,
                diagnosticType = result.diagnosticType,
            )
            is IsolatedOgnlAstAdmission.Result.Invariant -> PreparationFailure(
                PreparationFailureKind.PREPARATION_INVARIANT,
                INVARIANT,
                diagnosticType = result.diagnosticType,
            )
            is IsolatedOgnlAstAdmission.Result.UnprovenProperty ->
                error("capability-only OGNL inspection cannot produce caller-property admission failures")
        }
    }

    private fun referencesDatabaseId(expression: String): Boolean {
        var index = 0
        var quote: Char? = null
        var escaped = false
        while (index < expression.length) {
            val character = expression[index]
            if (quote != null) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == quote -> quote = null
                }
                index++
                continue
            }
            if (character == '\'' || character == '"') {
                quote = character
                index++
                continue
            }
            if (character == '_' || character.isLetter()) {
                val start = index
                index++
                while (
                    index < expression.length &&
                    (expression[index] == '_' || expression[index].isLetterOrDigit())
                ) {
                    index++
                }
                if (expression.substring(start, index) == "_databaseId") return true
                continue
            }
            index++
        }
        return false
    }

    private fun unsupported(capability: String): PreparationFailure = PreparationFailure(
        PreparationFailureKind.UNSUPPORTED_SEMANTIC,
        UNSUPPORTED,
        bindingProperty = capability,
    )
}
