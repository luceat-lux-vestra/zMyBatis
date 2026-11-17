package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.ExecutionInputOrigin
import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.InputEnvironmentResult
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.input.ProvidedInput
import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import com.algorist.zMyBatis.core.preparation.PreparationRequestResult
import com.algorist.zMyBatis.core.preparation.PreparationResult
import com.algorist.zMyBatis.core.preparation.XmlMapperPreparationSource
import com.algorist.zMyBatis.core.source.CapturedStatement
import com.algorist.zMyBatis.core.source.JavaMethodParameterMetadata
import com.algorist.zMyBatis.core.source.JavaTypeIdentity
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.StatementSourceGraph
import com.algorist.zMyBatis.core.source.XmlMapperMethodCapture
import com.algorist.zMyBatis.core.source.XmlStatementId
import java.math.BigInteger
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlMapperSingleMapParameterObjectPreparationTest {
    @Test
    fun typedMapIsPassedDirectlyAndBindingCapturesResolvedEntry() {
        val fixture = fixture(
            "SELECT * FROM users WHERE id = #{id,jdbcType=BIGINT}",
            "java.util.Map<java.lang.String,java.lang.Long>",
        )
        val execution = prepareSuccess(
            fixture,
            mapValue("id" to InputValue.IntegerValue(BigInteger.valueOf(42))),
        )

        assertEquals("SELECT * FROM users WHERE id = ?", normalize(execution.sqlWithPlaceholders))
        val binding = execution.orderedBindings.single()
        assertEquals("id", binding.property)
        assertEquals(InputValue.IntegerValue(BigInteger.valueOf(42)), binding.value)
        assertEquals("method-param:0", binding.requirementId?.value)
        assertEquals(
            "java.util.Map<java.lang.String,java.lang.Long>",
            binding.metadata.declaredJavaTypeIdentity?.value,
        )
        assertEquals("BIGINT", binding.metadata.jdbcTypeIdentity)
        assertTrue(binding.metadata.typeHandlerIdentity.startsWith("org.apache.ibatis.type."))
        assertTrue(contract(fixture).aliases.isEmpty())
    }

    @Test
    fun multipleMapPropertiesPreserveMyBatisMappingOrderAndEntryIdentity() {
        val fixture = fixture(
            "SELECT * FROM users WHERE id = #{id} OR parent_id = #{parentId}",
            "java.util.Map<java.lang.String,java.lang.Long>",
        )
        val execution = prepareSuccess(
            fixture,
            mapValue(
                "id" to InputValue.IntegerValue(BigInteger.valueOf(7)),
                "parentId" to InputValue.IntegerValue(BigInteger.valueOf(9)),
            ),
        )

        assertEquals(listOf("id", "parentId"), execution.orderedBindings.map { it.property })
        assertEquals(
            listOf(
                InputValue.IntegerValue(BigInteger.valueOf(7)),
                InputValue.IntegerValue(BigInteger.valueOf(9)),
            ),
            execution.orderedBindings.map { it.value },
        )
        assertEquals(
            listOf("method-param:0", "method-param:0"),
            execution.orderedBindings.map { it.requirementId?.value },
        )
    }

    @Test
    fun temporalMapEntryPreservesTypedValue() {
        val fixture = fixture(
            "SELECT * FROM audit WHERE created_on = #{createdOn}",
            "java.util.Map<java.lang.String,java.time.LocalDate>",
        )
        val date = LocalDate.of(2026, 10, 5)
        val execution = prepareSuccess(
            fixture,
            mapValue("createdOn" to InputValue.DateValue(date)),
        )

        assertEquals(InputValue.DateValue(date), execution.orderedBindings.single().value)
        assertEquals("createdOn", execution.orderedBindings.single().property)
    }

    @Test
    fun absentMapKeyAndPresentNullFollowStockNullSemantics() {
        val fixture = fixture(
            "SELECT * FROM users WHERE id = #{id}",
            "java.util.Map<java.lang.String,java.lang.Long>",
        )

        val missing = prepareSuccess(
            fixture,
            mapValue("other" to InputValue.IntegerValue(BigInteger.ONE)),
        )
        assertEquals(InputValue.NullValue, missing.orderedBindings.single().value)

        val presentNull = prepareSuccess(
            fixture,
            mapValue("id" to InputValue.NullValue),
        )
        assertEquals(InputValue.NullValue, presentNull.orderedBindings.single().value)
    }

    @Test
    fun mapEntryValueMustMatchDeclaredGenericValueType() {
        val fixture = fixture(
            "SELECT * FROM users WHERE id = #{id}",
            "java.util.Map<java.lang.String,java.lang.Long>",
        )

        val failure = prepare(
            fixture,
            mapValue("id" to InputValue.Text("not-a-long")),
        ) as PreparationResult.Failed

        assertEquals(PreparationFailureKind.UNSUPPORTED_BINDING_VALUE, failure.failure.kind)
        assertEquals("java-parameter-value-type-mismatch", failure.failure.code)
    }

    @Test
    fun scalarParameterObjectFallbackRegressionRemainsPreparedAsWholeValue() {
        val fixture = fixture(
            sql = "SELECT * FROM users WHERE id = #{arbitrary}",
            type = "long",
        )
        val execution = prepareSuccess(
            fixture,
            InputValue.IntegerValue(BigInteger.valueOf(12)),
        )

        assertEquals(InputValue.IntegerValue(BigInteger.valueOf(12)), execution.orderedBindings.single().value)
        assertEquals("arbitrary", execution.orderedBindings.single().property)
        assertTrue(contract(fixture).aliases.isEmpty())
    }

    private fun prepareSuccess(
        fixture: Fixture,
        value: InputValue,
    ) = when (val result = prepare(fixture, value)) {
        is PreparationResult.Success -> result.execution
        is PreparationResult.Failed -> throw AssertionError(
            "expected map parameter-object preparation success but got " +
                "kind=${result.failure.kind}, code=${result.failure.code}, " +
                "property=${result.failure.bindingProperty}",
        )
    }

    private fun prepare(
        fixture: Fixture,
        value: InputValue,
    ): PreparationResult {
        val source = source(fixture)
        val contract = MyBatisInvocationContractFactory.build(source)
        check(!contract.isPreparationBlocked) {
            "fixture contract unexpectedly blocked: ${contract.blockingProblems.map { it.code }}"
        }
        val requirement = contract.requirements.single()
        val environment = (
            InputEnvironment.validate(
                contract,
                listOf(ProvidedInput(requirement.id, value, ExecutionInputOrigin.USER_ENTERED)),
            ) as InputEnvironmentResult.Success
            ).environment
        val request = MyBatisPreparationRequest.create(source, contract, environment) as PreparationRequestResult.Ready
        return XmlMapperPreparationEngine.prepare(request.request)
    }

    private fun source(fixture: Fixture) = XmlMapperPreparationSource(
        fixture.graph,
        mapperMethod = fixture.mapper,
    )

    private fun contract(fixture: Fixture) =
        MyBatisInvocationContractFactory.build(source(fixture))

    private fun fixture(
        sql: String,
        type: String,
    ): Fixture {
        val statementId = XmlStatementId(XML_FILE, "example.Mapper", "find")
        val content = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
            <mapper namespace="example.Mapper">
                <select id="find">$sql</select>
            </mapper>
        """.trimIndent()
        val graph = StatementSourceGraph(
            rootStatement = CapturedStatement(
                statementId,
                StatementKind.SELECT,
                SourceRange(0, content.length),
            ),
            sourceSnapshots = listOf(SourceSnapshot(XML_FILE, XML_REVISION, content)),
            dependencies = emptyList(),
        )
        val mapper = XmlMapperMethodCapture(
            statementId = statementId,
            mapperSource = SourceSnapshot(JAVA_FILE, JAVA_REVISION, "x".repeat(256)),
            methodSourceRange = SourceRange(20, 120),
            parameters = listOf(
                JavaMethodParameterMetadata(
                    index = 0,
                    sourceName = "payload",
                    typeIdentity = JavaTypeIdentity(type),
                    myBatisParamAlias = null,
                ),
            ),
        )
        return Fixture(graph, mapper)
    }

    private fun mapValue(vararg entries: Pair<String, InputValue>): InputValue.MapValue =
        InputValue.MapValue(linkedMapOf(*entries))

    private fun normalize(sql: String): String = sql.trim().replace(Regex("\\s+"), " ")

    private data class Fixture(
        val graph: StatementSourceGraph,
        val mapper: XmlMapperMethodCapture,
    )

    private companion object {
        val XML_FILE = SourceFileId("vfs:/mapper.xml")
        val XML_REVISION = SourceRevision("xml-r1")
        val JAVA_FILE = SourceFileId("vfs:/Mapper.java")
        val JAVA_REVISION = SourceRevision("java-r1")
    }
}
