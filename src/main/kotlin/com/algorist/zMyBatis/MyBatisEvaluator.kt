@file:Suppress("NestedBlockDepth")

package com.algorist.zMyBatis

import com.algorist.zMyBatis.settings.ZMyBatisSettings
import org.apache.ibatis.builder.BuilderException
import org.apache.ibatis.ognl.OgnlContext
import org.apache.ibatis.ognl.OgnlRuntime
import org.apache.ibatis.ognl.PropertyAccessor
import org.apache.ibatis.parsing.XPathParser
import org.apache.ibatis.scripting.xmltags.XMLScriptBuilder
import org.apache.ibatis.session.Configuration
import java.time.temporal.Temporal
import java.util.Date
import java.util.regex.Matcher

object MyBatisEvaluator {

    /**
     * Standard MyBatis XML dynamic-SQL tags that XMLScriptBuilder knows how to handle.
     * Any element tag NOT in this set is considered "unknown" for [ignoreUnknownTags] purposes.
     * Statement-level wrappers (select/insert/update/delete/script/root) are also excluded
     * from the unknown-tag check because they are stripped / used as context nodes, not
     * passed into XMLScriptBuilder as dynamic content.
     */
    private val KNOWN_MYBATIS_TAGS = setOf(
        "trim", "where", "set", "foreach", "if", "choose", "when", "otherwise", "bind"
    )

    /** Tags that wrap the whole statement — never treated as unknown. */
    private val WRAPPER_TAGS = setOf("select", "insert", "update", "delete", "script", "root")

    init {
        val mapAccessor = object : PropertyAccessor {
            override fun getProperty(context: OgnlContext, target: Any, name: Any): Any? {
                val map = target as Map<*, *>
                val key = name.toString()
                return when (key) {
                    "size" -> map.size
                    "keys", "keySet" -> map.keys
                    "values" -> map.values
                    "isEmpty" -> map.isEmpty()
                    else -> {
                        // Direct lookup first
                        val direct = map[key]
                        if (direct != null || map.containsKey(key)) return direct
                        // Nested: search inside nested Map values
                        for ((_, v) in map) {
                            if (v is Map<*, *> && v.containsKey(key)) {
                                return v[key]
                            }
                        }
                        null
                    }
                }
            }
            override fun setProperty(context: OgnlContext, target: Any, name: Any, value: Any?) {
                @Suppress("UNCHECKED_CAST")
                (target as MutableMap<Any, Any?>)[name.toString()] = value
            }
            override fun getSourceAccessor(c: OgnlContext, t: Any, i: Any): String? = null
            override fun getSourceSetter(c: OgnlContext, t: Any, i: Any): String? = null
        }
        OgnlRuntime.setPropertyAccessor(LinkedHashMap::class.java, mapAccessor)
    }

    @Suppress("TooGenericExceptionCaught", "NestedBlockDepth")
    fun evaluate(xmlContent: String, params: Map<String, Any?>): String {
        val settings = ZMyBatisSettings.getInstance()
        return try {
            var cleanedXml = xmlContent
                .replace(Regex("<\\?xml.*\\?>", RegexOption.IGNORE_CASE), "")
                .replace(Regex("<!DOCTYPE[^>]*>", RegexOption.IGNORE_CASE), "")
                .trim()

            // ── Ignore Unknown Tags ───────────────────────────────────────
            // Strip unrecognised element tags BEFORE handing XML to XMLScriptBuilder.
            // XMLScriptBuilder throws BuilderException("Unknown element <X>") for any
            // tag not in its nodeHandlerMap.  When this option is ON we pre-remove those
            // tags (preserving their text content) so parsing can continue.
            if (settings.ignoreUnknownTags) {
                cleanedXml = stripUnknownTags(cleanedXml)
            }

            cleanedXml = sanitizeOgnlExpressions(cleanedXml)
            val scriptXml = "<root>$cleanedXml</root>"

            val configuration = Configuration()
            val parser = XPathParser(scriptXml, false, configuration.variables, null)
            val rootNode = parser.evalNode("/root")

            var contextNode = rootNode
            val children = rootNode.children
            val firstElement = children?.firstOrNull {
                it.node.nodeType == org.w3c.dom.Node.ELEMENT_NODE
            }
            val statementTags = setOf("select", "insert", "update", "delete", "script")
            if (firstElement != null && firstElement.name.lowercase() in statementTags) {
                contextNode = firstElement
            }

            val builder = XMLScriptBuilder(configuration, contextNode)
            val sqlSource = builder.parseScriptNode()
            val boundSql = sqlSource.getBoundSql(params)

            var pureSql = boundSql.sql
                .replace("\n", " ")
                .replace(Regex("\\s+"), " ")

            for (mapping in boundSql.parameterMappings) {
                val propName = mapping.property
                val value = if (boundSql.hasAdditionalParameter(propName)) {
                    boundSql.getAdditionalParameter(propName)
                } else {
                    resolveProperty(params, propName)
                }
                val literalValue = convertToLiteral(value)
                pureSql = pureSql.replaceFirst(Regex("\\?"), Matcher.quoteReplacement(literalValue))
            }

            pureSql.trim()

        } catch (e: Throwable) {
            // ── Strict OGNL Mode ──────────────────────────────────────────
            // BuilderException wraps OgnlException when an OGNL expression fails.
            // Strict ON  → rethrow so the caller shows an error dialog immediately.
            // Strict OFF → fall through to the error-comment SQL (existing behaviour).
            if (settings.strictOgnlMode && isOgnlError(e)) throw e

            """
            -- [MyBatis Plugin Error]
            -- Message: ${e.message}
            -- Cause: ${e.cause}
            -- Input: $xmlContent
            """.trimIndent()
        }
    }

    // ── Strict OGNL helper ────────────────────────────────────────────────

    /**
     * Returns true when [t] is (or is caused by) an OGNL evaluation failure.
     *
     * The chain is: OgnlException → wrapped by BuilderException inside OgnlCache.getValue().
     * We match on the message prefix that OgnlCache always produces so we don't accidentally
     * suppress unrelated BuilderExceptions (e.g. "Too many default (otherwise) elements").
     */
    private fun isOgnlError(t: Throwable): Boolean {
        if (t is BuilderException) {
            val msg = t.message ?: ""
            if (msg.startsWith("Error evaluating expression")) return true
        }
        val cause = t.cause
        return cause != null && isOgnlError(cause)
    }

    // ── Ignore Unknown Tags helper ────────────────────────────────────────

    /**
     * Strips element tags that XMLScriptBuilder does not recognise, preserving their
     * inner text/content so that the surrounding SQL remains syntactically intact.
     *
     * Strategy: regex-based removal of open/close/self-closing tags for any tag name
     * that is NOT in [KNOWN_MYBATIS_TAGS] and NOT in [WRAPPER_TAGS].
     *
     * We deliberately do NOT use a full XML parser here because:
     *  - The input may be a partial fragment (no root element yet).
     *  - We only need coarse tag removal, not semantic understanding.
     *
     * Limitation: nested unknown tags are handled correctly because the regex
     * is applied globally (all matching tags are removed in one pass).
     */
    private fun stripUnknownTags(xml: String): String {
        val allKnown = KNOWN_MYBATIS_TAGS + WRAPPER_TAGS

        // Self-closing: <custom-tag ... />
        val selfClosing = Regex("<([a-zA-Z][a-zA-Z0-9_:-]*)(?:\\s[^>]*)?>")
        // Closing: </custom-tag>
        val closing = Regex("</([a-zA-Z][a-zA-Z0-9_:-]*)\\s*>")

        var result = selfClosing.replace(xml) { match ->
            val tagName = match.groupValues[1].lowercase()
            // Keep the tag if it's known; remove it otherwise
            if (tagName in allKnown) match.value else ""
        }
        result = closing.replace(result) { match ->
            val tagName = match.groupValues[1].lowercase()
            if (tagName in allKnown) match.value else ""
        }
        return result
    }

    /**
     * Resolves a property path against the parameter map.
     *
     * Tries two strategies:
     *  1. **Flat key** — look up `propName` as-is (e.g. `"cust.id"` → `params["cust.id"]`)
     *  2. **Nested navigation** — split by `.` and walk nested Maps
     *     (e.g. `"user.name"` → `params["user"]["name"]`)
     */
    private fun resolveProperty(params: Map<String, Any?>, propName: String): Any? {
        if (params.containsKey(propName)) return params[propName]

        val segments = propName.split(".")
        var current: Any? = params
        for (segment in segments) {
            current = when (current) {
                is Map<*, *> -> current[segment]
                else -> return null
            }
        }
        return current
    }

    private fun sanitizeOgnlExpressions(xml: String): String {
        val pattern = Regex("(test|when|value)\\s*=\\s*(\"[^\"]*\"|'[^']*')")
        return pattern.replace(xml) { matchResult ->
            val attributeName = matchResult.groupValues[1]
            val quotedValue = matchResult.groupValues[2]
            val sanitizedValue = quotedValue.replace(Regex("#\\{([^}]+)}"), "$1")
            "$attributeName=$sanitizedValue"
        }
    }

    private fun convertToLiteral(value: Any?): String {
        return when (value) {
            null -> "NULL"
            is Number -> value.toString()
            is Boolean -> if (value) "1" else "0"
            is String -> "'${value.replace("'", "''")}'"
            is Date -> {
                if (value is java.sql.Timestamp) "'$value'"
                else {
                    val s = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(value)
                    "'$s'"
                }
            }
            is Temporal -> "'$value'"
            is List<*> -> "/*[ERROR: List — use <foreach>]*/NULL"
            is Map<*, *> -> "/*[ERROR: Object — use dot notation e.g. #{user.name}]*/NULL"
            else -> "'${value.toString().replace("'", "''")}'"
        }
    }
}
