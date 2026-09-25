package com.algorist.zMyBatis

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSyntaxException
import java.math.BigDecimal

/**
 * Parses JSON strings into Kotlin/Java types that MyBatis MetaObject can traverse.
 *
 * Uses Gson's JsonParser (tree API) instead of TypeToken-based deserialization to avoid
 * ClassCastException issues in IntelliJ plugin sandbox environments.
 *
 * JSON → Kotlin/Java type mapping:
 *   JSON object  → LinkedHashMap<String, Any?>   (MetaObject traverses via dot notation: user.name)
 *   JSON array   → ArrayList<Any?>               (MetaObject traverses via index: items[0])
 *   JSON string  → String
 *   JSON numeric value with no fractional part → Long when it fits, otherwise BigInteger
 *   JSON numeric value with a fractional part   → Double
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

    // ── Internal ──────────────────────────────────────────────────────────────

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
        prim.isNumber  -> convertNumber(prim)
        else -> prim.asString
    }

    private fun convertNumber(prim: JsonPrimitive): Number {
        val decimal = BigDecimal(prim.asString)
        if (decimal.stripTrailingZeros().scale() <= 0) {
            return try {
                decimal.longValueExact()
            } catch (_: ArithmeticException) {
                decimal.toBigIntegerExact()
            }
        }
        return decimal.toDouble()
    }
}
