package com.algorist.zMyBatis

import java.util.regex.Pattern

object ParameterExtractor {

    /**
     * Result of parameter extraction.
     *
     * @param params      All unique root parameter names found in the SQL/XML.
     * @param objectParams Subset of [params] that are accessed via dot-notation (e.g. `user` in `#{user.name}`).
     *                     These require an object/map value; simple scalars are not enough.
     */
    data class ExtractionResult(
        val params: List<String>,
        val objectParams: Set<String>
    )

    /**
     * Convenience overload — returns only the list of parameter names.
     */
    fun extract(xmlContent: String): List<String> = extractResult(xmlContent).params

    /**
     * Full extraction: returns both the parameter list and the set of object-accessed parameters.
     */
    fun extractResult(xmlContent: String): ExtractionResult {
        val params = mutableSetOf<String>()
        val objectParams = mutableSetOf<String>()   // root params accessed via dot-notation
        val boundVariables = mutableSetOf<String>()

        // 0a. Find <bind name="..."> variables to exclude them
        val bindMatcher = Pattern.compile("<bind\\s+name=[\"']([^\"']+)[\"']").matcher(xmlContent)
        while (bindMatcher.find()) {
            boundVariables.add(bindMatcher.group(1).trim())
        }

        // 0b. Find <foreach item="..." index="..."> loop variables to exclude them
        //     These are iteration variables, not caller-supplied parameters
        val foreachMatcher = Pattern.compile(
            "<foreach[^>]*\\bitem=[\"']([^\"']+)[\"'][^>]*>"
        ).matcher(xmlContent)
        while (foreachMatcher.find()) {
            boundVariables.add(foreachMatcher.group(1).trim())
        }
        val foreachIndexMatcher = Pattern.compile(
            "<foreach[^>]*\\bindex=[\"']([^\"']+)[\"'][^>]*>"
        ).matcher(xmlContent)
        while (foreachIndexMatcher.find()) {
            boundVariables.add(foreachIndexMatcher.group(1).trim())
        }

        // 0c. Find <foreach collection="..."> — the collection itself is a caller-supplied parameter
        val foreachCollectionMatcher = Pattern.compile(
            "<foreach[^>]*\\bcollection=[\"']([^\"']+)[\"'][^>]*>"
        ).matcher(xmlContent)
        while (foreachCollectionMatcher.find()) {
            val collectionName = foreachCollectionMatcher.group(1).trim()
            val rootName = collectionName.substringBefore(".")
            if (isValidParam(rootName)) {
                params.add(rootName)
            }
        }

        // 1. Extract #{param} and ${param}
        val sqlParamMatcher = Pattern.compile("[#$]\\{\\s*([^},]+)[^}]*}").matcher(xmlContent)
        while (sqlParamMatcher.find()) {
            val paramName = sqlParamMatcher.group(1).trim()
            // Strip nested property access: "user.id" -> "user"
            val rootName = paramName.substringBefore(".")
            if (isValidParam(rootName) && !boundVariables.contains(rootName)) {
                params.add(rootName)
                // If the original expression contains a dot, this root is an object-accessed param
                if (paramName.contains('.')) {
                    objectParams.add(rootName)
                }
            }
        }

        // 2. Extract parameters from OGNL expressions (test="...", value="...", when="...")
        val ognlAttributeMatcher = Pattern.compile(
            "(?:test|value|when)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')"
        ).matcher(xmlContent)
        while (ognlAttributeMatcher.find()) {
            val expression = (ognlAttributeMatcher.group(1) ?: ognlAttributeMatcher.group(2))?.trim() ?: continue
            val ognlResult = extractParamsFromOgnl(expression)
            params.addAll(ognlResult.roots - boundVariables)
            objectParams.addAll(ognlResult.objectRoots - boundVariables)
        }

        return ExtractionResult(
            params = params.toList().sorted(),
            objectParams = objectParams
        )
    }

    /**
     * Result of OGNL expression parsing.
     * @param roots      All valid root parameter names found.
     * @param objectRoots Subset of [roots] accessed via dot-notation (e.g. `user` in `user.name != null`).
     */
    private data class OgnlResult(val roots: Set<String>, val objectRoots: Set<String>)

    private fun extractParamsFromOgnl(expression: String): OgnlResult {
        val roots = mutableSetOf<String>()
        val objectRoots = mutableSetOf<String>()

        // Remove string literals to avoid matching text inside quotes
        val noStrings = expression.replace(Regex("('[^']*')|(\"[^\"]*\")"), " ")

        // Remove @ClassName@staticField/method references  e.g. @com.example.Status@ACTIVE
        val noStatic = noStrings.replace(Regex("@[^@]+@[a-zA-Z_][a-zA-Z0-9_]*"), " ")

        // Match identifier paths like "user", "user.name", "user.address.city", "list.size()"
        // group(0) = full match (e.g. "user.name"), group(1) = root only (e.g. "user")
        // If group(0) != group(1), the path had dot-notation → root is an object-accessed param.
        val pathMatcher = Pattern.compile(
            "\\b([a-zA-Z_][a-zA-Z0-9_]*)(?:\\.[a-zA-Z_][a-zA-Z0-9_]*(?:\\(\\))?)*"
        ).matcher(noStatic)

        while (pathMatcher.find()) {
            val root     = pathMatcher.group(1)
            val fullPath = pathMatcher.group(0)
            if (!OGNL_KEYWORDS.contains(root) && isValidParam(root)) {
                roots.add(root)
                if (fullPath != root) {   // dot-notation detected
                    objectRoots.add(root)
                }
            }
        }

        return OgnlResult(roots, objectRoots)
    }

    private fun isValidParam(name: String): Boolean {
        return name.isNotBlank() &&
               !name.startsWith("_") &&         // Exclude internal mybatis variables like _parameter
               !name.matches(Regex("param\\d+")) // Exclude auto-generated param1, param2...
    }

    /** OGNL / Java keywords, literals, and common method names that are NOT user parameters */
    private val OGNL_KEYWORDS = setOf(
        // OGNL logical / comparison operators (text form)
        "and", "or", "not", "eq", "neq", "lt", "gt", "lte", "gte", "band", "bor", "xor", "shl", "shr", "ushr",
        "in", "not",
        // Java / OGNL literals & keywords
        "true", "false", "null", "instanceof", "class", "new",
        // Java primitive types
        "byte", "short", "int", "long", "float", "double", "char", "boolean", "void",
        // Common Java/OGNL methods that appear in test expressions (not parameter names)
        "size", "length", "isEmpty", "isNotEmpty", "contains", "startsWith", "endsWith",
        "trim", "toUpperCase", "toLowerCase", "equals", "equalsIgnoreCase",
        "toString", "hashCode", "getClass", "compareTo",
        // MyBatis internal context variables
        "_parameter", "_databaseId",
        // Common collection type names used in OGNL casts / instanceof
        "String", "Integer", "Long", "Double", "Float", "Boolean", "List", "Map", "Set", "Collection",
        "Object", "Number", "Date"
    )
}
