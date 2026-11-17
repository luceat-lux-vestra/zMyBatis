package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.ExecutionInputOrigin
import com.algorist.zMyBatis.core.input.InputAliasKind
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlMapperNamedMapPropertyPreparationTest {
    @Test
    fun explicitNamedMapPropertyCapturesResolvedEntry() {
        val fixture = fixture(
            "SELECT * FROM users WHERE id = #{payload.id,jdbcType=BIGINT}",
            alias = "payload",
        )
        val execution = prepareSuccess(
            fixture,
            mapValue("id" to InputValue.IntegerValue(BigInteger.valueOf(42))),
        )

        assertEquals("SELECT * FROM users WHERE id = ?", normalize(execution.sqlWithPlaceholders))
        val binding = execution.orderedBindings.single()
        assertEquals("payload.id", binding.property)
        assertEquals(InputValue.IntegerValue(BigInteger.valueOf(42)), binding.value)
        assertEquals("method-param:0", binding.requirementId?.value)
        assertEquals("BIGINT", binding.metadata.jdbcTypeIdentity)
        assertEquals(InputAliasKind.EXPLICIT_PARAM, contract(fixture).aliases.single { it.name == "payload" }.kind)
    }

    @Test
    fun siblingPropertiesPreserveMappingOrderAndEntryIdentity() {
        val fixture = fixture(
            "SELECT * FROM users WHERE id = #{payload.id} OR parent_id = #{payload.parentId}",
            alias = "payload",
        )
        val execution = prepareSuccess(
            fixture,
            mapValue(
                "id" to InputValue.IntegerValue(BigInteger.valueOf(7)),
                "parentId" to InputValue.IntegerValue(BigInteger.valueOf(9)),
            ),
        )

        assertEquals(listOf("payload.id", "payload.parentId"), execution.orderedBindings.map { it.property })
        assertEquals(
            listOf(
                InputValue.IntegerValue(BigInteger.valueOf(7)),
                InputValue.IntegerValue(BigInteger.valueOf(9)),
            ),
            execution.orderedBindings.map { it.value },
        )
    }

    @Test
    fun genericAliasPropertyPathUsesSameTypedMap() {
        val fixture = fixture(
            "SELECT * FROM users WHERE id = #{param1.id}",
            alias = "payload",
        )
        val execution = prepareSuccess(
            fixture,
            mapValue("id" to InputValue.IntegerValue(BigInteger.valueOf(5))),
        )

        assertEquals("param1.id", execution.orderedBindings.single().property)
        assertEquals(InputValue.IntegerValue(BigInteger.valueOf(5)), execution.orderedBindings.single().value)
        assertEquals(InputAliasKind.GENERIC_PARAM, contract(fixture).aliases.single { it.name == "param1" }.kind)
    }

    @Test
    fun absentLeafAndPresentNullFollowStockNullSemantics() {
        val fixture = fixture(
            "SELECT * FROM users WHERE id = #{payload.id}",
            alias = "payload",
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
    fun entryValueMustMatchDeclaredMapValueType() {
        val fixture = fixture(
            "SELECT * FROM users WHERE id = #{payload.id}",
            alias = "payload",
        )

        val failure = prepare(
            fixture,
            mapValue("id" to InputValue.Text("not-a-long")),
        ) as PreparationResult.Failed

        assertEquals(PreparationFailureKind.UNSUPPORTED_BINDING_VALUE, failure.failure.kind)
        assertEquals("java-parameter-value-type-mismatch", failure.failure.code)
    }

    @Test
    fun directParameterObjectMapRegressionRemainsSeparate() {
        val fixture = fixture(
            "SELECT * FROM users WHERE id = #{id}",
            alias = null,
        )
        val execution = prepareSuccess(
            fixture,
            mapValue("id" to InputValue.IntegerValue(BigInteger.valueOf(12))),
        )

        assertEquals("id", execution.orderedBindings.single().property)
        assertTrue(contract(fixture).aliases.isEmpty())
    }

    private fun prepareSuccess(
        fixture: Fixture,
        value: InputValue,
    ) = when (val result = prepare(fixture, value)) {
        is PreparationResult.Success -> result.execution
        is PreparationResult.Failed -> throw AssertionError(
            "expected named Map preparation success but got " +
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
        alias: String?,
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
                    typeIdentity = JavaTypeIdentity("java.util.Map<java.lang.String,java.lang.Long>"),
                    myBatisParamAlias = alias,
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
