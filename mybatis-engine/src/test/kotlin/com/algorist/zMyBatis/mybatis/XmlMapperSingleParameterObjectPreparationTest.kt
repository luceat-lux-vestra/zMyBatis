package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.ExecutionInputOrigin
import com.algorist.zMyBatis.core.input.InputAliasKind
import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.InputEnvironmentResult
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputProvenance
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.input.ProvidedInput
import com.algorist.zMyBatis.core.input.XmlMapperMethodParameterContractFactory
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

class XmlMapperSingleParameterObjectPreparationTest {
    @Test
    fun soleUnannotatedIntegerBindsDirectParameterObjectForArbitraryProperty() {
        val fixture = fixture(
            "SELECT * FROM users WHERE id = #{arbitrary,jdbcType=BIGINT}",
            "long",
            "id",
        )
        val execution = prepareSuccess(
            fixture,
            InputValue.IntegerValue(BigInteger.valueOf(42)),
        )

        assertEquals("SELECT * FROM users WHERE id = ?", normalize(execution.sqlWithPlaceholders))
        val binding = execution.orderedBindings.single()
        assertEquals("arbitrary", binding.property)
        assertEquals(InputValue.IntegerValue(BigInteger.valueOf(42)), binding.value)
        assertEquals("xml-java-param:0", binding.requirementId?.value)
        assertEquals("long", binding.metadata.declaredJavaTypeIdentity?.value)
        assertEquals("BIGINT", binding.metadata.jdbcTypeIdentity)
        assertTrue(binding.metadata.typeHandlerIdentity.startsWith("org.apache.ibatis.type."))
        assertEquals(InputAliasKind.PARAMETER_OBJECT, contract(fixture).aliases.single().kind)
    }

    @Test
    fun multipleMappingPropertiesBindSameDirectScalarInMyBatisOrder() {
        val fixture = fixture(
            "SELECT * FROM users WHERE id = #{left} OR parent_id = #{right}",
            "long",
            "id",
        )
        val value = InputValue.IntegerValue(BigInteger.valueOf(7))
        val execution = prepareSuccess(fixture, value)

        assertEquals(listOf("left", "right"), execution.orderedBindings.map { it.property })
        assertEquals(listOf(value, value), execution.orderedBindings.map { it.value })
        assertEquals(
            listOf("xml-java-param:0", "xml-java-param:0"),
            execution.orderedBindings.map { it.requirementId?.value },
        )
    }

    @Test
    fun soleUnannotatedTemporalValuePreservesTypedBinding() {
        val fixture = fixture(
            "SELECT * FROM audit WHERE created_on = #{anything}",
            "java.time.LocalDate",
            "createdOn",
        )
        val date = LocalDate.of(2026, 10, 5)
        val execution = prepareSuccess(fixture, InputValue.DateValue(date))

        assertEquals(InputValue.DateValue(date), execution.orderedBindings.single().value)
        assertEquals("anything", execution.orderedBindings.single().property)
        assertEquals(
            "java.time.LocalDate",
            execution.orderedBindings.single().metadata.declaredJavaTypeIdentity?.value,
        )
    }

    @Test
    fun mismatchedParameterObjectFallbackIndexFailsClosedBeforeMyBatisPreparation() {
        val fixture = fixture(
            "SELECT * FROM users WHERE id = #{arbitrary}",
            "long",
            "id",
        )
        val original = contract(fixture)
        val alias = original.aliases.single()
        val tamperedAlias = alias.copy(
            provenance = InputProvenance(
                alias.provenance.evidence.map { evidence ->
                    if (evidence is InputEvidence.ParameterObjectFallback) {
                        evidence.copy(parameterIndex = 1)
                    } else {
                        evidence
                    }
                },
            ),
        )
        val tamperedContract = ParameterContract(
            statementId = original.statementId,
            requirements = original.requirements,
            aliases = listOf(tamperedAlias),
            internalBindings = original.internalBindings,
            blockingProblems = original.blockingProblems,
            sourceRevisions = original.sourceRevisions,
        )

        val failure = (
            prepare(
                fixture,
                tamperedContract,
                InputValue.IntegerValue(BigInteger.valueOf(42)),
            ) as PreparationResult.Failed
            ).failure

        assertEquals(PreparationFailureKind.PREPARATION_INVARIANT, failure.kind)
        assertEquals("xml-preparation-parameter-object-contract-invalid", failure.code)
    }

    private fun prepareSuccess(
        fixture: Fixture,
        value: InputValue,
    ) = when (val result = prepare(fixture, value)) {
        is PreparationResult.Success -> result.execution
        is PreparationResult.Failed -> throw AssertionError(
            "expected parameter-object preparation success but got " +
                "kind=\${result.failure.kind}, code=\${result.failure.code}, " +
                "property=\${result.failure.bindingProperty}",
        )
    }

    private fun prepare(
        fixture: Fixture,
        value: InputValue,
    ): PreparationResult = prepare(fixture, contract(fixture), value)

    private fun prepare(
        fixture: Fixture,
        contract: ParameterContract,
        value: InputValue,
    ): PreparationResult {
        check(!contract.isPreparationBlocked) {
            "fixture contract unexpectedly blocked: \${contract.blockingProblems.map { it.code }}"
        }
        val requirement = contract.requirements.single()
        val environment = (
            InputEnvironment.validate(
                contract,
                listOf(ProvidedInput(requirement.id, value, ExecutionInputOrigin.USER_ENTERED)),
            ) as InputEnvironmentResult.Success
            ).environment
        val request = MyBatisPreparationRequest.create(
            XmlMapperPreparationSource(
                fixture.graph,
                additionalAuthoritySnapshots = listOf(fixture.mapper.mapperSource),
            ),
            contract,
            environment,
        ) as PreparationRequestResult.Ready
        return XmlMapperPreparationEngine.prepare(request.request)
    }

    private fun contract(fixture: Fixture) =
        XmlMapperMethodParameterContractFactory.build(fixture.graph, fixture.mapper)

    private fun fixture(
        sql: String,
        type: String,
        sourceName: String,
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
                    sourceName = sourceName,
                    typeIdentity = JavaTypeIdentity(type),
                    myBatisParamAlias = null,
                ),
            ),
        )
        return Fixture(graph, mapper)
    }

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
