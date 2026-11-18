package com.algorist.zMyBatis.execution

import com.intellij.database.remote.jdbc.RemotePreparedStatement
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.sql.PreparedStatement
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.apache.ibatis.type.JdbcType
import org.apache.ibatis.type.TypeHandler
import org.apache.ibatis.type.TypeHandlerRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3.2 bridge-contract proof.
 *
 * Stock MyBatis chooses the JDBC setter. This test records that choice from the actual 3.6.0
 * TypeHandlerRegistry, then proves the maintained Database Tools RemotePreparedStatement exposes
 * a compatible method. zMyBatis does not define a parallel per-type setter table here.
 */
class P3RemotePreparedStatementBindingBridgeContractTest {

    @Test
    fun `RemotePreparedStatement covers the setter surface selected by required stock handlers`() {
        val registry = TypeHandlerRegistry()
        val requiredValues: List<Pair<Class<*>, Any>> = listOf(
            Byte::class.javaObjectType to 7.toByte(),
            Short::class.javaObjectType to 8.toShort(),
            Int::class.javaObjectType to 9,
            Long::class.javaObjectType to 10L,
            Float::class.javaObjectType to 11.25f,
            Double::class.javaObjectType to 12.5,
            BigDecimal::class.java to BigDecimal("13.75"),
            Boolean::class.javaObjectType to true,
            String::class.java to "binding-proof",
            ByteArray::class.java to byteArrayOf(1, 2, 3),
            LocalDate::class.java to LocalDate.of(2026, 11, 18),
            LocalTime::class.java to LocalTime.of(12, 34, 56),
            LocalDateTime::class.java to LocalDateTime.of(2026, 11, 18, 12, 34, 56),
            Instant::class.java to Instant.parse("2026-11-18T03:00:00Z"),
        )

        val invoked = linkedSetOf<MethodSignature>()
        val recorder = Proxy.newProxyInstance(
            PreparedStatement::class.java.classLoader,
            arrayOf(PreparedStatement::class.java),
        ) { _, method, _ ->
            if (method.name.startsWith("set")) {
                invoked += MethodSignature(method.name, method.parameterTypes.toList())
            }
            defaultValue(method.returnType)
        } as PreparedStatement

        for ((javaType, value) in requiredValues) {
            @Suppress("UNCHECKED_CAST")
            val typedClass = javaType as Class<Any>
            val handler = registry.getTypeHandler(typedClass)
            assertNotNull("stock MyBatis handler missing for ${javaType.name}", handler)
            @Suppress("UNCHECKED_CAST")
            val untypedHandler = handler as TypeHandler<Any>
            untypedHandler.setParameter(recorder, 1, value, null)
        }

        val stringHandler = registry.getTypeHandler(String::class.java)
        assertNotNull("stock MyBatis StringTypeHandler must be registered", stringHandler)
        stringHandler!!.setParameter(recorder, 1, null, JdbcType.OTHER)

        val remoteMethods = RemotePreparedStatement::class.java.methods
            .map { method -> MethodSignature(method.name, method.parameterTypes.toList()) }
            .toSet()
        val missing = invoked.filterNot(remoteMethods::contains)

        assertTrue(
            "RemotePreparedStatement must cover every setter selected by the required stock handlers; missing=$missing",
            missing.isEmpty(),
        )
        assertTrue(invoked.any { it.name == "setNull" })
        assertTrue(invoked.any { it.name != "setNull" })

        println(
            "ZMYBATIS_P3_API stockSetterSurface=" +
                invoked.sortedBy(MethodSignature::render).joinToString(";") { it.render() },
        )
    }

    @Test
    fun `RemotePreparedStatement carries generic JDBC type-code binding authority`() {
        val methods = RemotePreparedStatement::class.java.methods

        assertTrue(
            "setObject(index, value, jdbcTypeCode) is required for generic metadata transport",
            methods.any { method ->
                method.matches(
                    "setObject",
                    Int::class.javaPrimitiveType!!,
                    Any::class.java,
                    Int::class.javaPrimitiveType!!,
                )
            },
        )
        assertTrue(
            "setNull(index, jdbcTypeCode) is required for effective null binding",
            methods.any { method ->
                method.matches(
                    "setNull",
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                )
            },
        )

        assertEquals(Types.BIGINT, JdbcType.BIGINT.TYPE_CODE)
        assertEquals(Types.VARCHAR, JdbcType.VARCHAR.TYPE_CODE)
        assertEquals(Types.OTHER, JdbcType.OTHER.TYPE_CODE)

        println(
            "ZMYBATIS_P3_API genericJdbcTypeBridge=" +
                "setObject:int,Object,int setNull:int,int jdbcTypeCodes=MyBatisJdbcType.TYPE_CODE",
        )
    }

    private fun Method.matches(name: String, vararg parameterTypes: Class<*>): Boolean =
        this.name == name && this.parameterTypes.contentEquals(parameterTypes)

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }

    private data class MethodSignature(
        val name: String,
        val parameterTypes: List<Class<*>>,
    ) {
        fun render(): String =
            "$name(${parameterTypes.joinToString(",") { it.name }})"
    }
}
