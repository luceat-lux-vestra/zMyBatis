package com.algorist.zMyBatis.core.input

import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate

class InputCodecTest {
    private val source = SourceEvidence(SourceFileId("src/main/resources/UserMapper.xml"), SourceRange(0, 8))

    @Test
    fun integerAndDecimalCodecsPreserveArbitraryPrecision() {
        val integer = decodeSuccess(
            "92233720368547758081234567890",
            requirement(InputShape.SCALAR, InputScalarType.INTEGER),
        )
        val decimal = decodeSuccess(
            "0.1000000000000000000000000001",
            requirement(InputShape.SCALAR, InputScalarType.DECIMAL),
        )

        assertEquals(
            InputValue.IntegerValue(BigInteger("92233720368547758081234567890")),
            integer,
        )
        assertEquals(
            InputValue.DecimalValue(BigDecimal("0.1000000000000000000000000001")),
            decimal,
        )
    }

    @Test
    fun structuredJsonPreservesNestedNumericFidelityAndOrder() {
        val decoded = decodeSuccess(
            """{"id":123456789012345678901,"rate":1.2300,"items":[1,2]}""",
            requirement(InputShape.OBJECT),
        ) as InputValue.ObjectValue

        assertEquals(listOf("id", "rate", "items"), decoded.entries.keys.toList())
        assertEquals(
            InputValue.IntegerValue(BigInteger("123456789012345678901")),
            decoded.entries["id"],
        )
        assertEquals(InputValue.DecimalValue(BigDecimal("1.2300")), decoded.entries["rate"])
        assertEquals(
            InputValue.ListValue(
                listOf(
                    InputValue.IntegerValue(BigInteger.ONE),
                    InputValue.IntegerValue(BigInteger.TWO),
                ),
            ),
            decoded.entries["items"],
        )
    }

    @Test
    fun objectCanBeAdaptedToMapAndArrayKeepsDistinctTopLevelShape() {
        val map = decodeSuccess("""{"a":1}""", requirement(InputShape.MAP))
        val array = decodeSuccess("[1,2]", requirement(InputShape.ARRAY))

        assertTrue(map is InputValue.MapValue)
        assertTrue(array is InputValue.ArrayValue)
    }

    @Test
    fun malformedJsonDuplicateKeysAndWrongTopLevelShapeFailClosed() {
        assertFailureKind(
            InputCodecFailureKind.INVALID_JSON,
            InputCodec.decode("""{"a":1,"a":2}""", requirement(InputShape.OBJECT)),
        )
        assertFailureKind(
            InputCodecFailureKind.INVALID_JSON,
            InputCodec.decode("[1,]", requirement(InputShape.LIST)),
        )
        assertFailureKind(
            InputCodecFailureKind.SHAPE_MISMATCH,
            InputCodec.decode("[1,2]", requirement(InputShape.OBJECT)),
        )
    }

    @Test
    fun rawInterpolationPreservesExactTextWithoutNormalization() {
        val raw = requirement(
            InputShape.RAW_TEXT,
            InputScalarType.STRING,
            kind = InputKind.RAW_INTERPOLATION,
        )
        val text = "  created_at DESC, id ${'$'}{literal}  "

        assertEquals(InputValue.RawText(text), decodeSuccess(text, raw))
    }

    @Test
    fun nullableAndTemporalValuesUseTypedCodecs() {
        val nullableInteger = requirement(
            InputShape.SCALAR,
            InputScalarType.INTEGER,
            nullability = InputNullability.NULLABLE,
        )
        val nonNullInteger = requirement(
            InputShape.SCALAR,
            InputScalarType.INTEGER,
            nullability = InputNullability.NON_NULL,
        )

        assertEquals(InputValue.NullValue, decodeSuccess("null", nullableInteger))
        assertFailureKind(InputCodecFailureKind.NULL_NOT_ALLOWED, InputCodec.decode("null", nonNullInteger))
        assertEquals(
            InputValue.DateValue(LocalDate.parse("2026-09-14")),
            decodeSuccess("2026-09-14", requirement(InputShape.TEMPORAL, InputScalarType.DATE)),
        )
        assertEquals(
            InputValue.InstantValue(Instant.parse("2026-09-14T10:00:00Z")),
            decodeSuccess("2026-09-14T10:00:00Z", requirement(InputShape.TEMPORAL, InputScalarType.INSTANT)),
        )
    }

    @Test
    fun unprovenScalarTypeDoesNotBecomeStringByConvenience() {
        assertFailureKind(
            InputCodecFailureKind.UNSUPPORTED_EXPECTATION,
            InputCodec.decode("anything", requirement(InputShape.SCALAR, InputScalarType.UNKNOWN)),
        )
    }

    private fun requirement(
        shape: InputShape,
        scalarType: InputScalarType = InputScalarType.UNKNOWN,
        kind: InputKind = InputKind.BOUND,
        nullability: InputNullability = InputNullability.UNKNOWN,
    ): InputRequirement = InputRequirement(
        InputRequirementId("value"),
        kind,
        ExpectedInputType(shape, scalarType, nullability = nullability),
        InputRequiredness.REQUIRED,
        InputProvenance(listOf(InputEvidence.Placeholder(kind, "value", source))),
    )

    private fun decodeSuccess(text: String, requirement: InputRequirement): InputValue =
        (InputCodec.decode(text, requirement) as InputDecodeResult.Success).value

    private fun assertFailureKind(expected: InputCodecFailureKind, result: InputDecodeResult) {
        assertEquals(expected, (result as InputDecodeResult.Failure).failure.kind)
    }
}
