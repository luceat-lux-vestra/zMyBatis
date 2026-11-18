package com.algorist.zMyBatis.execution

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
import com.intellij.database.remote.jdbc.RemotePreparedStatement
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.math.BigInteger
import java.sql.PreparedStatement
import org.apache.ibatis.type.TypeHandlerRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StockMyBatisPreparedBindingBridgeTest {
    @Test
    fun stockHandlersChooseRemoteSettersThroughExactPublicFacade() {
        val calls = mutableListOf<Call>()
        val remote = recordingRemote(calls)
        val statement = PublicRemotePreparedStatementFacade.create(remote)
        val registry = TypeHandlerRegistry()

        assertSame(
            PreparedBindingBindResult.Bound,
            StockMyBatisPreparedBindingBinder.bind(
                registry,
                statement,
                binding(
                    index = 0,
                    value = InputValue.IntegerValue(BigInteger.valueOf(41)),
                    mappingType = "java.lang.Long",
                    handler = "org.apache.ibatis.type.LongTypeHandler",
                    jdbcType = "BIGINT",
                ),
            ),
        )
        assertSame(
            PreparedBindingBindResult.Bound,
            StockMyBatisPreparedBindingBinder.bind(
                registry,
                statement,
                binding(
                    index = 1,
                    value = InputValue.Text("alpha"),
                    mappingType = "java.lang.String",
                    handler = "org.apache.ibatis.type.StringTypeHandler",
                    jdbcType = "VARCHAR",
                ),
            ),
        )
        assertSame(
            PreparedBindingBindResult.Bound,
            StockMyBatisPreparedBindingBinder.bind(
                registry,
                statement,
                binding(
                    index = 2,
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
            ),
        )
        assertSame(
            PreparedBindingBindResult.Bound,
            StockMyBatisPreparedBindingBinder.bind(
                registry,
                statement,
                binding(
                    index = 3,
                    value = InputValue.NullValue,
                    mappingType = "java.lang.Object",
                    handler = "org.apache.ibatis.type.ObjectTypeHandler",
                    jdbcType = null,
                    effectiveJdbcType = "VARCHAR",
                ),
            ),
        )

        assertEquals(
            listOf("setLong", "setString", "setBytes", "setNull"),
            calls.map { it.method.name },
        )
        assertEquals(listOf(1, 2, 3, 4), calls.map { it.args.first() })
        assertEquals(41L, calls[0].args[1])
        assertEquals("alpha", calls[1].args[1])
        assertTrue((calls[2].args[1] as ByteArray).contentEquals(byteArrayOf(1, 2)))
        assertEquals(java.sql.Types.VARCHAR, calls[3].args[1])
    }

    @Test
    fun customHandlerAndIdentityDriftFailBeforeRemoteStatementInvocation() {
        val calls = mutableListOf<Call>()
        val statement = PublicRemotePreparedStatementFacade.create(recordingRemote(calls))
        val registry = TypeHandlerRegistry()

        val custom = StockMyBatisPreparedBindingBinder.bind(
            registry,
            statement,
            binding(
                index = 0,
                value = InputValue.Text("alpha"),
                mappingType = "java.lang.String",
                handler = "fixture.CustomTypeHandler",
                jdbcType = "VARCHAR",
            ),
        )
        assertTrue(custom is PreparedBindingBindResult.Failed)
        assertEquals(
            PreparedBindingBindFailureKind.TYPE_HANDLER_UNSUPPORTED,
            (custom as PreparedBindingBindResult.Failed).failure.kind,
        )
        assertTrue(calls.isEmpty())

        val mismatch = StockMyBatisPreparedBindingBinder.bind(
            registry,
            statement,
            binding(
                index = 0,
                value = InputValue.Text("alpha"),
                mappingType = "java.lang.String",
                handler = "org.apache.ibatis.type.LongTypeHandler",
                jdbcType = "VARCHAR",
            ),
        )
        assertTrue(mismatch is PreparedBindingBindResult.Failed)
        assertEquals(
            PreparedBindingBindFailureKind.TYPE_HANDLER_IDENTITY_MISMATCH,
            (mismatch as PreparedBindingBindResult.Failed).failure.kind,
        )
        assertTrue(calls.isEmpty())
    }

    @Test
    fun facadeResolvesOnlyExactPublicRemoteSignatures() {
        val supported = PreparedStatement::class.java.getMethod(
            "setLong",
            Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
        )
        assertEquals(
            RemotePreparedStatement::class.java.getMethod(
                "setLong",
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
            ),
            PublicRemotePreparedStatementFacade.exactRemoteMethod(supported),
        )

        val unsupported = UnsupportedPreparedContract::class.java.getMethod("notJdbc", String::class.java)
        assertNull(PublicRemotePreparedStatementFacade.exactRemoteMethod(unsupported))
    }

    private fun recordingRemote(calls: MutableList<Call>): RemotePreparedStatement =
        Proxy.newProxyInstance(
            RemotePreparedStatement::class.java.classLoader,
            arrayOf(RemotePreparedStatement::class.java),
        ) { _, method, args ->
            calls += Call(method, args?.toList().orEmpty())
            defaultValue(method.returnType)
        } as RemotePreparedStatement

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Byte::class.javaPrimitiveType -> 0.toByte()
        Short::class.javaPrimitiveType -> 0.toShort()
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        Char::class.javaPrimitiveType -> '\u0000'
        else -> null
    }

    private fun binding(
        index: Int,
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
            index = index,
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

    private data class Call(
        val method: Method,
        val args: List<Any?>,
    )

    private interface UnsupportedPreparedContract {
        fun notJdbc(value: String)
    }
}
