package com.algorist.zMyBatis.core.input

import java.math.BigDecimal
import java.math.BigInteger
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

enum class InputCodecFailureKind {
    EMPTY_INPUT,
    NULL_NOT_ALLOWED,
    INVALID_BOOLEAN,
    INVALID_INTEGER,
    INVALID_DECIMAL,
    INVALID_TEMPORAL,
    INVALID_UUID,
    INVALID_JSON,
    INPUT_TOO_COMPLEX,
    SHAPE_MISMATCH,
    UNSUPPORTED_EXPECTATION,
}

data class InputCodecFailure(
    val kind: InputCodecFailureKind,
    val offset: Int? = null,
)

sealed interface InputDecodeResult {
    data class Success(val value: InputValue) : InputDecodeResult

    data class Failure(val failure: InputCodecFailure) : InputDecodeResult
}

object InputCodec {
    fun decode(text: String, requirement: InputRequirement): InputDecodeResult {
        if (requirement.kind == InputKind.RAW_INTERPOLATION) {
            return InputDecodeResult.Success(InputValue.RawText(text))
        }

        val expected = requirement.expectedType
        val trimmed = text.trim()

        if (trimmed == "null" && expected.scalarType != InputScalarType.STRING) {
            return if (expected.nullability == InputNullability.NULLABLE) {
                InputDecodeResult.Success(InputValue.NullValue)
            } else {
                InputDecodeResult.Failure(InputCodecFailure(InputCodecFailureKind.NULL_NOT_ALLOWED))
            }
        }

        return when (expected.shape) {
            InputShape.SCALAR -> decodeScalar(text, trimmed, expected.scalarType)
            InputShape.TEMPORAL -> decodeTemporal(trimmed, expected.scalarType)
            InputShape.OBJECT,
            InputShape.LIST,
            InputShape.ARRAY,
            InputShape.MAP,
            -> decodeStructured(trimmed, expected.shape)
            InputShape.RAW_TEXT -> InputDecodeResult.Success(InputValue.Text(text))
            InputShape.UNKNOWN -> InputDecodeResult.Failure(
                InputCodecFailure(InputCodecFailureKind.UNSUPPORTED_EXPECTATION),
            )
        }
    }

    private fun decodeScalar(
        original: String,
        trimmed: String,
        scalarType: InputScalarType,
    ): InputDecodeResult = when (scalarType) {
        InputScalarType.STRING -> InputDecodeResult.Success(InputValue.Text(original))
        InputScalarType.BOOLEAN -> when (trimmed) {
            "true" -> InputDecodeResult.Success(InputValue.BooleanValue(true))
            "false" -> InputDecodeResult.Success(InputValue.BooleanValue(false))
            else -> InputDecodeResult.Failure(InputCodecFailure(InputCodecFailureKind.INVALID_BOOLEAN))
        }
        InputScalarType.INTEGER -> {
            if (trimmed.isEmpty()) {
                InputDecodeResult.Failure(InputCodecFailure(InputCodecFailureKind.EMPTY_INPUT))
            } else {
                try {
                    InputDecodeResult.Success(InputValue.IntegerValue(BigInteger(trimmed)))
                } catch (_: NumberFormatException) {
                    InputDecodeResult.Failure(InputCodecFailure(InputCodecFailureKind.INVALID_INTEGER))
                }
            }
        }
        InputScalarType.DECIMAL -> {
            if (trimmed.isEmpty()) {
                InputDecodeResult.Failure(InputCodecFailure(InputCodecFailureKind.EMPTY_INPUT))
            } else {
                try {
                    InputDecodeResult.Success(InputValue.DecimalValue(BigDecimal(trimmed)))
                } catch (_: NumberFormatException) {
                    InputDecodeResult.Failure(InputCodecFailure(InputCodecFailureKind.INVALID_DECIMAL))
                }
            }
        }
        InputScalarType.UUID -> try {
            InputDecodeResult.Success(InputValue.UuidValue(UUID.fromString(trimmed)))
        } catch (_: IllegalArgumentException) {
            InputDecodeResult.Failure(InputCodecFailure(InputCodecFailureKind.INVALID_UUID))
        }
        InputScalarType.DATE,
        InputScalarType.TIME,
        InputScalarType.DATE_TIME,
        InputScalarType.INSTANT,
        -> decodeTemporal(trimmed, scalarType)
        InputScalarType.UNKNOWN -> InputDecodeResult.Failure(
            InputCodecFailure(InputCodecFailureKind.UNSUPPORTED_EXPECTATION),
        )
    }

    private fun decodeTemporal(text: String, scalarType: InputScalarType): InputDecodeResult {
        if (text.isEmpty()) {
            return InputDecodeResult.Failure(InputCodecFailure(InputCodecFailureKind.EMPTY_INPUT))
        }

        return try {
            val value = when (scalarType) {
                InputScalarType.DATE -> InputValue.DateValue(LocalDate.parse(text))
                InputScalarType.TIME -> InputValue.TimeValue(LocalTime.parse(text))
                InputScalarType.DATE_TIME -> InputValue.DateTimeValue(LocalDateTime.parse(text))
                InputScalarType.INSTANT -> InputValue.InstantValue(Instant.parse(text))
                else -> return InputDecodeResult.Failure(
                    InputCodecFailure(InputCodecFailureKind.UNSUPPORTED_EXPECTATION),
                )
            }
            InputDecodeResult.Success(value)
        } catch (_: DateTimeException) {
            InputDecodeResult.Failure(InputCodecFailure(InputCodecFailureKind.INVALID_TEMPORAL))
        }
    }

    private fun decodeStructured(text: String, expectedShape: InputShape): InputDecodeResult {
        if (text.isEmpty()) {
            return InputDecodeResult.Failure(InputCodecFailure(InputCodecFailureKind.EMPTY_INPUT))
        }

        val parsed = try {
            JsonValueParser(text).parse()
        } catch (failure: JsonParseFailure) {
            return InputDecodeResult.Failure(
                InputCodecFailure(failure.kind, failure.offset),
            )
        }

        val adapted = when (expectedShape) {
            InputShape.OBJECT -> parsed as? InputValue.ObjectValue
            InputShape.MAP -> (parsed as? InputValue.ObjectValue)?.let { InputValue.MapValue(it.entries) }
            InputShape.LIST -> parsed as? InputValue.ListValue
            InputShape.ARRAY -> (parsed as? InputValue.ListValue)?.let { InputValue.ArrayValue(it.elements) }
            else -> null
        }

        return if (adapted == null) {
            InputDecodeResult.Failure(InputCodecFailure(InputCodecFailureKind.SHAPE_MISMATCH))
        } else {
            InputDecodeResult.Success(adapted)
        }
    }
}

private class JsonParseFailure(
    val kind: InputCodecFailureKind,
    val offset: Int,
) : RuntimeException()

private class JsonValueParser(private val text: String) {
    private var cursor: Int = 0

    fun parse(): InputValue {
        skipWhitespace()
        val value = parseValue(depth = 0)
        skipWhitespace()
        if (cursor != text.length) fail()
        return value
    }

    private fun parseValue(depth: Int): InputValue {
        if (depth > MAX_NESTING_DEPTH) {
            fail(kind = InputCodecFailureKind.INPUT_TOO_COMPLEX)
        }
        skipWhitespace()
        if (cursor >= text.length) fail()
        return when (text[cursor]) {
            '{' -> parseObject(depth)
            '[' -> parseArray(depth)
            '"' -> InputValue.Text(parseString())
            't' -> {
                consumeLiteral("true")
                InputValue.BooleanValue(true)
            }
            'f' -> {
                consumeLiteral("false")
                InputValue.BooleanValue(false)
            }
            'n' -> {
                consumeLiteral("null")
                InputValue.NullValue
            }
            '-', in '0'..'9' -> parseNumber()
            else -> fail()
        }
    }

    private fun parseObject(depth: Int): InputValue.ObjectValue {
        expect('{')
        skipWhitespace()
        val entries = LinkedHashMap<String, InputValue>()
        if (peek('}')) {
            cursor++
            return InputValue.ObjectValue(entries)
        }

        while (true) {
            skipWhitespace()
            if (!peek('"')) fail()
            val key = parseString()
            if (key in entries) fail()
            skipWhitespace()
            expect(':')
            entries[key] = parseValue(depth + 1)
            skipWhitespace()
            when {
                peek(',') -> cursor++
                peek('}') -> {
                    cursor++
                    return InputValue.ObjectValue(entries)
                }
                else -> fail()
            }
        }
    }

    private fun parseArray(depth: Int): InputValue.ListValue {
        expect('[')
        skipWhitespace()
        val values = mutableListOf<InputValue>()
        if (peek(']')) {
            cursor++
            return InputValue.ListValue(values)
        }

        while (true) {
            values += parseValue(depth + 1)
            skipWhitespace()
            when {
                peek(',') -> cursor++
                peek(']') -> {
                    cursor++
                    return InputValue.ListValue(values)
                }
                else -> fail()
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (cursor < text.length) {
            val ch = text[cursor++]
            when (ch) {
                '"' -> return result.toString()
                '\\' -> result.append(parseEscape())
                else -> {
                    if (ch.code < 0x20) fail(cursor - 1)
                    result.append(ch)
                }
            }
        }
        fail()
    }

    private fun parseEscape(): Char {
        if (cursor >= text.length) fail()
        return when (val escaped = text[cursor++]) {
            '"', '\\', '/' -> escaped
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> parseUnicodeEscape()
            else -> fail(cursor - 1)
        }
    }

    private fun parseUnicodeEscape(): Char {
        if (cursor + 4 > text.length) fail()
        val hex = text.substring(cursor, cursor + 4)
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) fail()
        cursor += 4
        return hex.toInt(16).toChar()
    }

    private fun parseNumber(): InputValue {
        val start = cursor
        if (peek('-')) cursor++
        if (cursor >= text.length) fail()

        if (peek('0')) {
            cursor++
            if (cursor < text.length && text[cursor].isDigit()) fail()
        } else {
            if (!text[cursor].isDigit() || text[cursor] == '0') fail()
            while (cursor < text.length && text[cursor].isDigit()) cursor++
        }

        var decimal = false
        if (peek('.')) {
            decimal = true
            cursor++
            val fractionStart = cursor
            while (cursor < text.length && text[cursor].isDigit()) cursor++
            if (fractionStart == cursor) fail()
        }

        if (peek('e') || peek('E')) {
            decimal = true
            cursor++
            if (peek('+') || peek('-')) cursor++
            val exponentStart = cursor
            while (cursor < text.length && text[cursor].isDigit()) cursor++
            if (exponentStart == cursor) fail()
        }

        val token = text.substring(start, cursor)
        return try {
            if (decimal) {
                InputValue.DecimalValue(BigDecimal(token))
            } else {
                InputValue.IntegerValue(BigInteger(token))
            }
        } catch (_: NumberFormatException) {
            fail(start)
        }
    }

    private fun consumeLiteral(literal: String) {
        if (!text.startsWith(literal, cursor)) fail()
        cursor += literal.length
    }

    private fun expect(expected: Char) {
        if (!peek(expected)) fail()
        cursor++
    }

    private fun peek(expected: Char): Boolean = cursor < text.length && text[cursor] == expected

    private fun skipWhitespace() {
        while (cursor < text.length) {
            when (text[cursor]) {
                ' ', '\t', '\r', '\n' -> cursor++
                else -> return
            }
        }
    }

    private fun fail(
        offset: Int = cursor,
        kind: InputCodecFailureKind = InputCodecFailureKind.INVALID_JSON,
    ): Nothing = throw JsonParseFailure(kind, offset)

    private companion object {
        const val MAX_NESTING_DEPTH = 128
    }
}
