@file:Suppress("NestedBlockDepth")

package com.algorist.zMyBatis

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

    init {
        // Register a PropertyAccessor for LinkedHashMap so OGNL can resolve
        // nested Map properties like "user.id" → map.get("id").
        //
        // OGNL evaluates "user.id == 1" in 2 steps:
        //   1) ContextAccessor resolves "user" from _parameter → LinkedHashMap{id=1}
        //   2) OGNL resolves ".id" on the returned LinkedHashMap
        //
        // Step 2 requires a PropertyAccessor for LinkedHashMap. OgnlRuntime registers
        // MapPropertyAccessor for Map.class (interface), but ClassCacheHandler walks
        // LinkedHashMap → HashMap → AbstractMap → Object → interfaces(Map) and may
        // fail to reach Map.class in IntelliJ plugin sandbox classloader environments.
        //
        // We register ONLY for LinkedHashMap (the concrete type that JsonParameterParser
        // produces). We must NOT register for HashMap because ContextMap extends HashMap,
        // and ContextMap has its own ContextAccessor registered by DynamicContext.
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
        return try {
            var cleanedXml = xmlContent
                .replace(Regex("<\\?xml.*\\?>", RegexOption.IGNORE_CASE), "")
                .replace(Regex("<!DOCTYPE[^>]*>", RegexOption.IGNORE_CASE), "")
                .trim()

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
            """
            -- [MyBatis Plugin Error]
            -- Message: ${e.message}
            -- Cause: ${e.cause}
            -- Input: $xmlContent
            """.trimIndent()
        }
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
