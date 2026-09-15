package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.ExecutionInputOrigin
import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.InputEnvironmentResult
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.input.JavaAnnotationParameterContractFactory
import com.algorist.zMyBatis.core.input.ProvidedInput
import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import com.algorist.zMyBatis.core.preparation.PreparationRequestResult
import com.algorist.zMyBatis.core.preparation.PreparationResult
import com.algorist.zMyBatis.core.preparation.PreparationSource
import com.algorist.zMyBatis.core.source.CapturedStatement
import com.algorist.zMyBatis.core.source.JavaAnnotationStatementCapture
import com.algorist.zMyBatis.core.source.JavaMethodParameterMetadata
import com.algorist.zMyBatis.core.source.JavaStatementId
import com.algorist.zMyBatis.core.source.JavaTypeIdentity
import com.algorist.zMyBatis.core.source.MethodSignature
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.StatementSourceGraph
import java.math.BigDecimal
import java.math.BigInteger
import java.sql.CallableStatement
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.apache.ibatis.type.BaseTypeHandler
import org.apache.ibatis.type.JdbcType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MyBatisPreparationEngineAdversarialTest {
    @Test
    fun missingNestedMapKeyFailsInsteadOfBecomingNullBinding() {
        val request = request(
            "select #{payload.missing}",
            listOf(
                Parameter(
                    "java.util.Map<java.lang.String, java.lang.Object>",
                    "payload",
                    InputValue.MapValue(emptyMap()),
                ),
            ),
        )

        val result = MyBatisPreparationEngine.prepare(request)

        assertTrue(result is PreparationResult.Failed)
        result as PreparationResult.Failed
        assertEquals(PreparationFailureKind.BINDING_RESOLUTION, result.failure.kind)
        assertEquals("payload.missing", result.failure.bindingProperty)
    }

    @Test
    fun longOverflowFailsBeforeProducingAnIncompatibleBinding() {
        val request = request(
            "select #{id}",
            listOf(
                Parameter(
                    "java.lang.Long",
                    "id",
                    InputValue.IntegerValue(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)),
                ),
            ),
        )

        val result = MyBatisPreparationEngine.prepare(request)

        assertTrue(result is PreparationResult.Failed)
        result as PreparationResult.Failed
        assertEquals(PreparationFailureKind.UNSUPPORTED_BINDING_VALUE, result.failure.kind)
        assertEquals("java-parameter-value-out-of-range", result.failure.code)
        assertEquals("id", result.failure.bindingProperty)
    }

    @Test
    fun nonFiniteDoubleCoercionFailsClosed() {
        val request = request(
            "select #{amount}",
            listOf(
                Parameter(
                    "java.lang.Double",
                    "amount",
                    InputValue.DecimalValue(BigDecimal("1E10000")),
                ),
            ),
        )

        val result = MyBatisPreparationEngine.prepare(request)

        assertTrue(result is PreparationResult.Failed)
        result as PreparationResult.Failed
        assertEquals(PreparationFailureKind.UNSUPPORTED_BINDING_VALUE, result.failure.kind)
        assertEquals("java-parameter-value-out-of-range", result.failure.code)
        assertEquals("amount", result.failure.bindingProperty)
    }

    @Test
    fun unusedUnknownMapperParameterDoesNotTriggerRuntimeClassResolution() {
        val request = request(
            "select #{id}",
            listOf(
                Parameter("java.lang.Long", "id", InputValue.IntegerValue(BigInteger.valueOf(17))),
                Parameter("fixture.DoesNotExist", "unused", InputValue.Text("not-consumed")),
            ),
        )

        val result = MyBatisPreparationEngine.prepare(request)

        assertTrue(result is PreparationResult.Success)
        val execution = (result as PreparationResult.Success).execution
        assertEquals("select ?", execution.sqlWithPlaceholders)
        assertEquals(InputValue.IntegerValue(BigInteger.valueOf(17)), execution.orderedBindings.single().value)
    }

    @Test
    fun invalidMyBatisPlaceholderOptionFailsAsTypedParseFailure() {
        val request = request(
            "select #{id,notARealOption=x}",
            listOf(Parameter("java.lang.Long", "id", InputValue.IntegerValue(BigInteger.ONE))),
        )

        val result = MyBatisPreparationEngine.prepare(request)

        assertTrue(result is PreparationResult.Failed)
        result as PreparationResult.Failed
        assertEquals(PreparationFailureKind.MYBATIS_PARSE, result.failure.kind)
        assertEquals("mybatis-sql-source-parse-failure", result.failure.code)
    }

    @Test
    fun customTypeHandlerIsRefusedBeforeItsConstructorCanRun() {
        customHandlerConstructions.set(0)
        val handler =
            "com.algorist.zMyBatis.mybatis.MyBatisPreparationEngineAdversarialTest\$CustomLongTypeHandler"
        val request = request(
            "select #{id,typeHandler=$handler}",
            listOf(Parameter("java.lang.Long", "id", InputValue.IntegerValue(BigInteger.TEN))),
        )

        val result = MyBatisPreparationEngine.prepare(request)

        assertTrue(result is PreparationResult.Failed)
        result as PreparationResult.Failed
        assertEquals(PreparationFailureKind.UNSUPPORTED_TYPE_HANDLER, result.failure.kind)
        assertEquals("mybatis-custom-type-handler-unsupported", result.failure.code)
        assertEquals(0, customHandlerConstructions.get())
    }

    @Test
    fun explicitJavaTypeIsRefusedBeforeMyBatisRuntimeClassResolution() {
        val request = request(
            "select #{id,javaType=java.lang.Long}",
            listOf(Parameter("java.lang.Long", "id", InputValue.IntegerValue(BigInteger.ONE))),
        )

        val result = MyBatisPreparationEngine.prepare(request)

        assertTrue(result is PreparationResult.Failed)
        result as PreparationResult.Failed
        assertEquals(PreparationFailureKind.UNSUPPORTED_SEMANTIC, result.failure.kind)
        assertEquals("mybatis-explicit-java-type-unsupported", result.failure.code)
    }

    @Test
    fun rawOgnlFailureIsTypedAndNeverReturnedAsSql() {
        val request = request(
            "select * from ${'$'}{id.missing}",
            listOf(Parameter("java.lang.String", "id", InputValue.RawText("abc"))),
        )

        val result = MyBatisPreparationEngine.prepare(request)

        assertTrue(result is PreparationResult.Failed)
        result as PreparationResult.Failed
        assertEquals(PreparationFailureKind.OGNL, result.failure.kind)
        assertEquals("mybatis-ognl-evaluation-failure", result.failure.code)
    }

    @Test
    fun concurrentPreparationsCannotCrossContaminateRawOrBoundValues() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 24).map { index ->
                executor.submit<String> {
                    val request = request(
                        "select * from ${'$'}{table} where id = #{id}",
                        listOf(
                            Parameter("java.lang.String", "table", InputValue.RawText("table_$index")),
                            Parameter(
                                "java.lang.Long",
                                "id",
                                InputValue.IntegerValue(BigInteger.valueOf(index.toLong())),
                            ),
                        ),
                    )
                    val result = MyBatisPreparationEngine.prepare(request)
                    check(result is PreparationResult.Success) { "preparation failed for index $index" }
                    val execution = result.execution
                    check(execution.orderedBindings.single().value == InputValue.IntegerValue(BigInteger.valueOf(index.toLong())))
                    execution.sqlWithPlaceholders
                }
            }

            val sql = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(
                (0 until 24).mapTo(linkedSetOf()) { "select * from table_$it where id = ?" },
                sql.toSet(),
            )
        } finally {
            executor.shutdownNow()
        }
    }

    private fun request(sql: String, parameters: List<Parameter>): MyBatisPreparationRequest {
        val capture = capture(sql, parameters)
        val contract = JavaAnnotationParameterContractFactory.build(capture)
        assertFalse("test fixture unexpectedly produced a blocked contract", contract.isPreparationBlocked)
        val provided = contract.requirements.map { requirement ->
            val index = requirement.id.value.substringAfter("java-param:").toInt()
            ProvidedInput(requirement.id, parameters[index].value, ExecutionInputOrigin.USER_ENTERED)
        }
        val environmentResult = InputEnvironment.validate(contract, provided)
        assertTrue(environmentResult is InputEnvironmentResult.Success)
        val environment = (environmentResult as InputEnvironmentResult.Success).environment
        val requestResult = MyBatisPreparationRequest.create(
            PreparationSource.JavaAnnotation(capture),
            contract,
            environment,
        )
        assertTrue(requestResult is PreparationRequestResult.Ready)
        return (requestResult as PreparationRequestResult.Ready).request
    }

    private fun capture(sql: String, parameters: List<Parameter>): JavaAnnotationStatementCapture {
        val fileId = SourceFileId("fixture/AdversarialMapper.java")
        val types = parameters.map { JavaTypeIdentity(it.type) }
        val statementId = JavaStatementId(
            fileId,
            "fixture.AdversarialMapper",
            MethodSignature("query", types),
        )
        val snapshot = SourceSnapshot(fileId, SourceRevision("revision-1"), "authoritative-java-source")
        return JavaAnnotationStatementCapture(
            StatementSourceGraph(
                CapturedStatement(statementId, StatementKind.SELECT, SourceRange(0, snapshot.content.length)),
                listOf(snapshot),
                emptyList(),
            ),
            listOf(sql),
            parameters.mapIndexed { index, parameter ->
                JavaMethodParameterMetadata(index, parameter.alias, types[index], parameter.alias)
            },
        )
    }

    class CustomLongTypeHandler : BaseTypeHandler<Long>() {
        init {
            customHandlerConstructions.incrementAndGet()
        }

        override fun setNonNullParameter(
            ps: PreparedStatement,
            i: Int,
            parameter: Long,
            jdbcType: JdbcType?,
        ) {
            ps.setLong(i, parameter)
        }

        override fun getNullableResult(rs: ResultSet, columnName: String): Long = rs.getLong(columnName)

        override fun getNullableResult(rs: ResultSet, columnIndex: Int): Long = rs.getLong(columnIndex)

        override fun getNullableResult(cs: CallableStatement, columnIndex: Int): Long = cs.getLong(columnIndex)
    }

    private data class Parameter(
        val type: String,
        val alias: String,
        val value: InputValue,
    )

    companion object {
        private val customHandlerConstructions = AtomicInteger()
    }
}
