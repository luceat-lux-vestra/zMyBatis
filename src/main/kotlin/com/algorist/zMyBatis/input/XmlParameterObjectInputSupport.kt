package com.algorist.zMyBatis.input

import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputProvenance

internal data class XmlParameterObjectPathHint(
    val path: String,
    val exampleJson: String?,
)

/**
 * Presentation-only helpers for the XML-only caller-object fallback.
 *
 * Core still owns one xml-parameter-object requirement. These helpers only project already captured
 * source provenance into convenient dotted-path fields and an editable JSON template.
 */
internal object XmlParameterObjectInputSupport {
    private val simplePath = Regex("""[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*""")
    private val pathToken = Regex("""[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*""")
    private val quotedText = Regex("""'[^']*'|"[^"]*"""")
    private val comparison = Regex(
        """([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)\s*(?:==|!=|>=|<=|>|<)\s*(null|true|false|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?[lLfFdD]?|'[^']*'|"[^"]*")""",
    )
    private val numericLiteral = Regex(
        """(?<![A-Za-z0-9_])[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?[lLfFdD]?(?![A-Za-z0-9_])""",
    )
    private val jsonNumber = Regex("""-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?""")
    private val reservedTokens = setOf(
        "and",
        "or",
        "not",
        "true",
        "false",
        "null",
        "empty",
        "new",
        "instanceof",
    )

    fun isXmlParameterObject(provenance: InputProvenance): Boolean =
        provenance.evidence.any { it is InputEvidence.XmlParameterObject }

    fun hints(provenance: InputProvenance): List<XmlParameterObjectPathHint> {
        if (!isXmlParameterObject(provenance)) return emptyList()

        val hints = linkedMapOf<String, String?>()
        fun add(path: String, exampleJson: String? = null) {
            val normalized = normalizePath(path) ?: return
            val existing = hints[normalized]
            if (normalized !in hints || shouldReplace(existing, exampleJson)) {
                hints[normalized] = exampleJson
            }
        }

        provenance.evidence.forEach { evidence ->
            when (evidence) {
                is InputEvidence.Placeholder -> add(evidence.expression)
                is InputEvidence.OgnlExpression -> {
                    comparison.findAll(evidence.expression).forEach { match ->
                        val path = match.groupValues[1]
                        if (path.endsWith(".size")) {
                            add(path.removeSuffix(".size"), "[1]")
                        } else {
                            add(
                                path = path,
                                exampleJson = literalAsJson(match.groupValues[2]),
                            )
                        }
                    }
                    ognlPaths(evidence.expression).forEach { add(it.first, it.second) }
                }
                is InputEvidence.ForeachCollection -> add(evidence.expression, "[1]")
                else -> Unit
            }
        }

        return hints.map { (path, exampleJson) ->
            XmlParameterObjectPathHint(path, exampleJson)
        }
    }

    fun template(hints: List<XmlParameterObjectPathHint>): String =
        renderObject(
            values = hints.associate { hint ->
                hint.path to (hint.exampleJson ?: "null")
            },
        )

    fun composeScalarValues(values: Map<String, String>): String =
        renderObject(
            values = values.mapValues { (_, value) -> scalarValueAsJson(value) },
        )

    fun scalarPlaceholder(hint: XmlParameterObjectPathHint): String {
        val example = hint.exampleJson ?: return "value"
        if (example == "null") return "null"
        if (example.startsWith('"') && example.endsWith('"') && example.length >= 2) {
            return example.substring(1, example.length - 1)
        }
        return example
    }

    private fun shouldReplace(existing: String?, candidate: String?): Boolean = when {
        candidate == null -> false
        existing == null -> true
        existing == "null" && candidate != "null" -> true
        else -> false
    }

    private fun ognlPaths(expression: String): List<Pair<String, String?>> {
        val withoutQuotedText = quotedText.replace(expression, " ")
        val withoutLiterals = numericLiteral.replace(withoutQuotedText, " ")
        return pathToken.findAll(withoutLiterals)
            .mapNotNull { match ->
                val raw = match.value
                if (raw in reservedTokens) return@mapNotNull null
                val next = withoutQuotedText.getOrNull(match.range.last + 1)
                if (next == '(') return@mapNotNull null

                if (raw.endsWith(".size")) {
                    normalizePath(raw.removeSuffix(".size"))?.let { it to "[1]" }
                } else {
                    normalizePath(raw)?.let { it to null }
                }
            }
            .distinctBy { it.first }
            .toList()
    }

    private fun normalizePath(path: String): String? {
        val trimmed = path.trim()
        if (!simplePath.matches(trimmed)) return null
        if (trimmed in reservedTokens) return null
        return trimmed
    }

    private fun literalAsJson(literal: String): String? {
        val trimmed = literal.trim()
        val numeric = trimmed.removeSuffix("L").removeSuffix("l")
            .removeSuffix("F").removeSuffix("f")
            .removeSuffix("D").removeSuffix("d")
        return when {
            trimmed == "null" || trimmed == "true" || trimmed == "false" -> trimmed
            jsonNumber.matches(numeric) -> numeric
            trimmed.length >= 2 && trimmed.startsWith("'") && trimmed.endsWith("'") ->
                quoteJson(trimmed.substring(1, trimmed.length - 1))
            trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"') -> trimmed
            else -> null
        }
    }

    private fun scalarValueAsJson(value: String): String {
        val trimmed = value.trim()
        return when {
            trimmed == "null" || trimmed == "true" || trimmed == "false" -> trimmed
            jsonNumber.matches(trimmed) -> trimmed
            trimmed.startsWith("[") || trimmed.startsWith("{") -> trimmed
            trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"') -> trimmed
            trimmed.length >= 2 && trimmed.startsWith("'") && trimmed.endsWith("'") ->
                quoteJson(trimmed.substring(1, trimmed.length - 1))
            else -> quoteJson(trimmed)
        }
    }

    private fun renderObject(values: Map<String, String>): String {
        val root = ObjectNode()
        values.forEach { (path, jsonValue) ->
            put(root, path.split('.'), ValueNode(jsonValue))
        }
        return render(root, depth = 0)
    }

    private fun put(
        root: ObjectNode,
        segments: List<String>,
        value: ValueNode,
    ) {
        var current = root
        segments.forEachIndexed { index, segment ->
            val last = index == segments.lastIndex
            if (last) {
                if (current.children[segment] !is ObjectNode) {
                    current.children[segment] = value
                }
            } else {
                current = current.children[segment] as? ObjectNode
                    ?: ObjectNode().also { current.children[segment] = it }
            }
        }
    }

    private fun render(node: JsonNode, depth: Int): String = when (node) {
        is ValueNode -> node.json
        is ObjectNode -> if (node.children.isEmpty()) {
            "{}"
        } else {
            val indent = "  ".repeat(depth)
            val childIndent = "  ".repeat(depth + 1)
            node.children.entries.joinToString(
                prefix = "{\n",
                postfix = "\n$indent}",
                separator = ",\n",
            ) { (key, child) ->
                "$childIndent${quoteJson(key)}: ${render(child, depth + 1)}"
            }
        }
    }

    private fun quoteJson(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) {
                    append("\\u")
                    append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    append(ch)
                }
            }
        }
        append('"')
    }

    private sealed interface JsonNode

    private data class ObjectNode(
        val children: LinkedHashMap<String, JsonNode> = linkedMapOf(),
    ) : JsonNode

    private data class ValueNode(val json: String) : JsonNode
}
