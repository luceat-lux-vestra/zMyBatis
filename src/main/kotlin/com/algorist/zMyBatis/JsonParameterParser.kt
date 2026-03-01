package com.algorist.zMyBatis

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSyntaxException

/**
 * Parses JSON strings into Kotlin/Java types that MyBatis MetaObject can traverse.
 *
 * Uses Gson's JsonParser (tree API) instead of TypeToken-based deserialization to avoid
 * ClassCastException issues in IntelliJ plugin sandbox environments.
 *
 * JSON → Kotlin type mapping:
 *   JSON object  → LinkedHashMap<String, Any?>   (MetaObject traverses via dot notation: user.name)
 *   JSON array   → ArrayList<Any?>               (MetaObject traverses via index: items[0])
 *   JSON string  → String
 *   JSON number  → Long (whole numbers) / Double (decimals)
 *   JSON boolean → Boolean
 *   JSON null    → null
 */
object JsonParameterParser {

    /**
     * Parses a top-level JSON **object** string into a Map.
     *
     * @throws JsonSyntaxException      if [json] is not valid JSON
     * @throws IllegalArgumentException if [json] is valid JSON but not a top-level object { }
     */
    fun parse(json: String): Map<String, Any?> {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return emptyMap()

        val element = parseElement(trimmed)

        require(element.isJsonObject) {
            "JSON must be a top-level object { } — got ${element.javaClass.simpleName}"
        }

        return convertObject(element.asJsonObject)
    }

    /**
     * Parses a single JSON **value** string into the corresponding Kotlin/Java type.
     * Accepts any valid JSON token: object `{}`, array `[]`, string, number, boolean, or `null`.
     *
     * @throws JsonSyntaxException if [json] is not valid JSON
     */
    fun parseValue(json: String): Any? {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return null
        return convertElement(parseElement(trimmed))
    }

    /**
     * Parses a JSON value and flattens it into dot-notation entries for a given [rootKey].
     *
     * This is designed for MyBatis `DynamicContext` which stores params in a flat
     * `ContextMap` (extends HashMap). OGNL expressions like `user.id == 1` or
     * `#{cust.name}` resolve by looking up `"user.id"` or `"cust.name"` as **literal
     * keys** in the bindings map — they do NOT navigate nested Map structures.
     *
     * Examples with `rootKey = "cust"`:
     * ```
     * {"name": "test", "id": 1}
     *   → { "cust.name" = "test", "cust.id" = 1 }
     *
     * {"address": {"city": "Seoul"}}
     *   → { "cust.address.city" = "Seoul" }
     *
     * [1, 2, 3]   (array)
     *   → { "cust[0]" = 1, "cust[1]" = 2, "cust[2]" = 3 }
     *
     * "Alice"      (scalar)
     *   → { "cust" = "Alice" }
     * ```
     */
    fun flattenValue(rootKey: String, json: String): Map<String, Any?> {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return emptyMap()
        val element = parseElement(trimmed)
        val result = LinkedHashMap<String, Any?>()
        flattenElement(rootKey, element, result)
        return result
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun flattenElement(prefix: String, element: JsonElement, out: MutableMap<String, Any?>) {
        when {
            element.isJsonObject -> {
                for ((key, value) in element.asJsonObject.entrySet()) {
                    flattenElement("$prefix.$key", value, out)
                }
            }
            element.isJsonArray -> {
                element.asJsonArray.forEachIndexed { index, value ->
                    flattenElement("$prefix[$index]", value, out)
                }
            }
            else -> {
                // Leaf: null, string, number, boolean
                out[prefix] = convertElement(element)
            }
        }
    }

    private fun parseElement(text: String): JsonElement = try {
        JsonParser.parseString(text)
    } catch (e: JsonSyntaxException) {
        throw JsonSyntaxException("Invalid JSON: ${e.message}", e)
    }

    private fun convertObject(obj: JsonObject): Map<String, Any?> =
        LinkedHashMap<String, Any?>().also { map ->
            for ((key, value) in obj.entrySet()) {
                map[key] = convertElement(value)
            }
        }

    private fun convertArray(arr: JsonArray): List<Any?> =
        ArrayList<Any?>().also { list ->
            for (element in arr) {
                list.add(convertElement(element))
            }
        }

    private fun convertElement(element: JsonElement): Any? = when {
        element.isJsonNull      -> null
        element.isJsonObject    -> convertObject(element.asJsonObject)
        element.isJsonArray     -> convertArray(element.asJsonArray)
        element.isJsonPrimitive -> convertPrimitive(element.asJsonPrimitive)
        else                    -> null
    }

    private fun convertPrimitive(prim: JsonPrimitive): Any? = when {
        prim.isBoolean -> prim.asBoolean
        prim.isString  -> prim.asString
        prim.isNumber  -> {
            val d = prim.asDouble
            if (d == kotlin.math.floor(d) && !d.isInfinite()) d.toLong() else d
        }
        else -> prim.asString
    }
}
