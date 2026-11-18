package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.ExecutionInputOrigin
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputKind
import com.algorist.zMyBatis.core.input.InputProvenance
import com.algorist.zMyBatis.core.input.InputRequirementId
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.input.SourceEvidence
import com.algorist.zMyBatis.core.preparation.PreparedBinding
import com.algorist.zMyBatis.core.preparation.PreparedBindingMetadata
import com.algorist.zMyBatis.core.preparation.PreparedBindingOrigin
import com.algorist.zMyBatis.core.source.JavaTypeIdentity
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceRevision
import java.math.BigInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MyBatisPreparedBindingRuntimeAdapterTest {
    @Test
    fun standardScalarAndPrimitiveArrayReusePreparationConversionAuthority() {
        val long = ready(
            binding(
                value = InputValue.IntegerValue(BigInteger.valueOf(41)),
                mappingType = "java.lang.Long",
                handler = "org.apache.ibatis.type.LongTypeHandler",
                jdbcType = "BIGINT",
            ),
        )
        assertEquals(Long::class.javaObjectType, long.javaType)
        assertEquals(41L, long.value)

        val bytes = ready(
            binding(
                value = InputValue.ArrayValue(
                    listOf(
                        InputValue.IntegerValue(BigInteger.ONE),
                        InputValue.IntegerValue(BigInteger.TWO),
                    ),
                ),
                mappingType = "[B",
                handler = "org.apache.ibatis.type.ByteArrayTypeHandler",
                jdbcType = "VARBINARY",
            ),
        )
        assertEquals(ByteArray::class.java, bytes.javaType)
        assertArrayEquals(byteArrayOf(1, 2), bytes.value as ByteArray)
    }

    @Test
    fun stockObjectNullAuthorityIsTheOnlyAcceptedObjectMapping() {
        val nullValue = ready(
            binding(
                value = InputValue.NullValue,
                mappingType = "java.lang.Object",
                handler = "org.apache.ibatis.type.ObjectTypeHandler",
                jdbcType = null,
                effectiveJdbcType = "VARCHAR",
            ),
        )
        assertEquals(Object::class.java, nullValue.javaType)
        assertNull(nullValue.value)

        val nonNull = MyBatisPreparedBindingRuntimeAdapter.adapt(
            binding(
                value = InputValue.Text("not-null"),
                mappingType = "java.lang.Object",
                handler = "org.apache.ibatis.type.ObjectTypeHandler",
                jdbcType = "VARCHAR",
            ),
        )
        assertTrue(nonNull is MyBatisPreparedBindingRuntimeAdapter.Result.Failed)
    }

    private fun ready(binding: PreparedBinding): MyBatisPreparedBindingRuntimeAdapter.Result.Ready =
        when (val result = MyBatisPreparedBindingRuntimeAdapter.adapt(binding)) {
            is MyBatisPreparedBindingRuntimeAdapter.Result.Ready -> result
            is MyBatisPreparedBindingRuntimeAdapter.Result.Failed ->
                throw AssertionError("unexpected adapter failure: ${result.failure}")
        }

    private fun binding(
        value: InputValue,
        mappingType: String,
        handler: String,
        jdbcType: String?,
        effectiveJdbcType: String? = jdbcType,
    ): PreparedBinding {
        val source = SourceEvidence(
            SourceFileId("fixture/Mapper.java"),
            SourceRevision("r1"),
            SourceRange(0, 1),
        )
        return PreparedBinding(
            index = 0,
            property = "value",
            value = value,
            origin = PreparedBindingOrigin.CallerInput(
                InputRequirementId("method-param:0"),
                InputProvenance(
                    listOf(InputEvidence.Placeholder(InputKind.BOUND, "value", source)),
                ),
            ),
            metadata = PreparedBindingMetadata(
                declaredJavaTypeIdentity = JavaTypeIdentity(mappingType),
                mappingJavaTypeIdentity = mappingType,
                jdbcTypeIdentity = jdbcType,
                effectiveJdbcTypeIdentity = effectiveJdbcType,
                typeHandlerIdentity = handler,
                parameterMode = "IN",
                numericScale = null,
            ),
        )
    }
}
