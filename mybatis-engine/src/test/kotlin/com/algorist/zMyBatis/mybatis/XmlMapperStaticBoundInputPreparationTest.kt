package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.ExecutionInputOrigin
import com.algorist.zMyBatis.core.input.InputAliasKind
import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.InputEnvironmentResult
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputUsageKind
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.input.ProvidedInput
import com.algorist.zMyBatis.core.execution.ExecutionTargetDescriptor
import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.ExplicitSchemaIdentity
import com.algorist.zMyBatis.core.execution.ResolvedExecutionTarget
import com.algorist.zMyBatis.core.execution.StableDataSourceId
import com.algorist.zMyBatis.core.execution.PreparedExecutionPlanResult
import com.algorist.zMyBatis.core.execution.PreparedExecutionPlanner
import com.algorist.zMyBatis.core.materialization.NativeExecutionPlanMaterializer
import com.algorist.zMyBatis.core.materialization.PreparedPlanSqlMaterializationResult
import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import com.algorist.zMyBatis.core.preparation.PreparationRequestResult
import com.algorist.zMyBatis.core.preparation.PreparationResult
import com.algorist.zMyBatis.core.preparation.XmlMapperPreparationSource
import com.algorist.zMyBatis.core.source.CapturedStatement
import com.algorist.zMyBatis.core.source.ConventionalMyBatisDatabaseIds
import com.algorist.zMyBatis.core.source.MyBatisDatabaseId
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

class XmlMapperStaticBoundInputPreparationTest {
    @Test
    fun preparedLongBindingMaterializesThroughNativeExecutionPlan() {
        val fixture = fixture(
            "SELECT #{id,jdbcType=BIGINT}::BIGINT AS RESULT_VALUE",
            listOf(parameter(0, "long", "sourceId", "id")),
        )
        val prepared = success(
            prepare(fixture, listOf(InputValue.IntegerValue(BigInteger.valueOf(42)))),
        )
        val binding = prepared.orderedBindings.single()
        assertEquals("java.lang.Long", binding.metadata.mappingJavaTypeIdentity)
        assertEquals("BIGINT", binding.metadata.jdbcTypeIdentity)
        assertEquals("org.apache.ibatis.type.LongTypeHandler", binding.metadata.typeHandlerIdentity)

        val targetId = ExecutionTargetId(
            StableDataSourceId("postgres-proof"),
            ExplicitSchemaIdentity("public"),
        )
        val target = ResolvedExecutionTarget(
            ExecutionTargetDescriptor(targetId, "PostgreSQL proof"),
            MyBatisDatabaseId(ConventionalMyBatisDatabaseIds.POSTGRESQL),
        )

        val plan = when (val frozen = PreparedExecutionPlanner.freeze(prepared, target)) {
            is PreparedExecutionPlanResult.Ready -> frozen.plan
            is PreparedExecutionPlanResult.Failed ->
                throw AssertionError("prepared plan freeze failed: ${frozen.failure}")
        }
        val artifact = when (val result = NativeExecutionPlanMaterializer.materialize(plan)) {
            is PreparedPlanSqlMaterializationResult.Ready -> result.artifact
            is PreparedPlanSqlMaterializationResult.Failed ->
                throw AssertionError("native materialization failed: ${result.failure}")
        }

        assertEquals("SELECT 42::BIGINT AS RESULT_VALUE", normalize(artifact.executionSql))
        assertEquals(targetId, artifact.executionTargetId)
        assertEquals(ConventionalMyBatisDatabaseIds.POSTGRESQL, artifact.databaseId)
        assertEquals(prepared.statementId, plan.statementId)
        assertEquals(prepared.sourceRevisions, plan.sourceRevisions)
        assertEquals(plan.fingerprint, artifact.planFingerprint)
    }

    @Test
    fun explicitParamScalarProducesProvenOrderedBinding() {
        val fixture = fixture(
            "SELECT * FROM users WHERE id = #{id,jdbcType=BIGINT}",
            listOf(parameter(0, "long", "sourceId", "id")),
        )

        val execution = success(prepare(fixture, listOf(InputValue.IntegerValue(BigInteger.valueOf(42)))))

        assertEquals("SELECT * FROM users WHERE id = ?", normalize(execution.sqlWithPlaceholders))
        assertEquals(mapOf(XML_FILE to XML_REVISION, JAVA_FILE to JAVA_REVISION), execution.sourceRevisions)
        val binding = execution.orderedBindings.single()
        assertEquals(0, binding.index)
        assertEquals("id", binding.property)
        assertEquals(InputValue.IntegerValue(BigInteger.valueOf(42)), binding.value)
        assertEquals("long", binding.metadata.declaredJavaTypeIdentity?.value)
        assertEquals("BIGINT", binding.metadata.jdbcTypeIdentity)
        assertEquals("IN", binding.metadata.parameterMode)
        assertTrue(binding.metadata.typeHandlerIdentity.startsWith("org.apache.ibatis.type."))
        assertEquals("method-param:0", binding.requirementId?.value)
        assertTrue(binding.provenance.evidence.any { it is InputEvidence.MapperMethodParameter })
        assertTrue(binding.provenance.evidence.any { it is InputEvidence.ExplicitParamAlias })
        assertEquals(
            setOf(InputUsageKind.BOUND_PLACEHOLDER),
            contract(fixture).requirements.single().usageKinds,
        )
    }

    @Test
    fun repeatedPlaceholderPreservesMyBatisMappingOrderAndCardinality() {
        val fixture = fixture(
            "SELECT * FROM users WHERE id = #{id} OR parent_id = #{id}",
            listOf(parameter(0, "long", "id", "id")),
        )

        val execution = success(prepare(fixture, listOf(InputValue.IntegerValue(BigInteger.TEN))))

        assertEquals(2, execution.orderedBindings.size)
        assertEquals(listOf(0, 1), execution.orderedBindings.map { it.index })
        assertEquals(listOf("id", "id"), execution.orderedBindings.map { it.property })
        assertEquals(
            listOf(InputValue.IntegerValue(BigInteger.TEN), InputValue.IntegerValue(BigInteger.TEN)),
            execution.orderedBindings.map { it.value },
        )
        assertEquals(
            listOf("method-param:0", "method-param:0"),
            execution.orderedBindings.map { it.requirementId?.value },
        )
    }

    @Test
    fun boundPlaceholderCardinalityRemainsStockMyBatisOwned() {
        val fixture = fixture(
            "SELECT * FROM users WHERE id = #{id} OR parent_id = #{id}",
            listOf(parameter(0, "long", "id", "id")),
        )
        val callerContract = contract(fixture)
        val requirement = callerContract.requirements.single()

        assertEquals("method-param:0", requirement.id.value)
        assertEquals(setOf(InputUsageKind.BOUND_PLACEHOLDER), requirement.usageKinds)
        assertTrue(requirement.provenance.evidence.none { it is InputEvidence.Placeholder })

        val execution = success(
            prepare(fixture, listOf(InputValue.IntegerValue(BigInteger.ONE))),
        )
        assertEquals(listOf("id", "id"), execution.orderedBindings.map { it.property })
        assertEquals(
            listOf("method-param:0", "method-param:0"),
            execution.orderedBindings.map { it.requirementId?.value },
        )
    }

    @Test
    fun provenGenericParamAliasesPrepareWithoutSourceNameAuthority() {
        val fixture = fixture(
            "SELECT * FROM users WHERE id = #{param1} AND name = #{param2}",
            listOf(
                parameter(0, "long", "sourceId", "id"),
                parameter(1, "java.lang.String", "sourceName", "name"),
            ),
        )

        val execution = success(
            prepare(
                fixture,
                listOf(
                    InputValue.IntegerValue(BigInteger.valueOf(7)),
                    InputValue.Text("alice"),
                ),
            ),
        )

        assertEquals(listOf("param1", "param2"), execution.orderedBindings.map { it.property })
        assertEquals(
            listOf(InputValue.IntegerValue(BigInteger.valueOf(7)), InputValue.Text("alice")),
            execution.orderedBindings.map { it.value },
        )
        assertEquals(
            listOf(
                "id" to InputAliasKind.EXPLICIT_PARAM,
                "param1" to InputAliasKind.GENERIC_PARAM,
                "name" to InputAliasKind.EXPLICIT_PARAM,
                "param2" to InputAliasKind.GENERIC_PARAM,
            ),
            contract(fixture).aliases.map { it.name to it.kind },
        )
    }

    @Test
    fun temporalBindingPreservesTypedValue() {
        val fixture = fixture(
            "SELECT * FROM audit WHERE created_on = #{created}",
            listOf(parameter(0, "java.time.LocalDate", "created", "created")),
        )
        val date = LocalDate.of(2026, 9, 19)

        val execution = success(prepare(fixture, listOf(InputValue.DateValue(date))))

        assertEquals(InputValue.DateValue(date), execution.orderedBindings.single().value)
        assertEquals(
            "java.time.LocalDate",
            execution.orderedBindings.single().metadata.declaredJavaTypeIdentity?.value,
        )
    }

    @Test
    fun outputParameterModeRemainsFailClosedAfterAuthorityResolution() {
        val fixture = fixture(
            "SELECT * FROM users WHERE id = #{id,mode=OUT}",
            listOf(parameter(0, "long", "id", "id")),
        )

        assertFailure(
            prepare(fixture, listOf(InputValue.IntegerValue(BigInteger.ONE))),
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "mybatis-parameter-mode-unsupported",
        )
    }

    @Test
    fun structuredDirectBindingsRemainFailClosed() {
        val element = InputValue.IntegerValue(BigInteger.ONE)
        val cases = listOf(
            Triple(
                fixture(
                    "SELECT * FROM users WHERE id IN #{collection}",
                    listOf(parameter(0, "java.util.Collection<java.lang.Long>", "ids", null)),
                ),
                InputValue.ListValue(listOf(element)),
                "Collection",
            ),
            Triple(
                fixture(
                    "SELECT * FROM users WHERE id IN #{list}",
                    listOf(parameter(0, "java.util.List<java.lang.Long>", "ids", null)),
                ),
                InputValue.ListValue(listOf(element)),
                "List",
            ),
            Triple(
                fixture(
                    "SELECT * FROM users WHERE id IN #{array}",
                    listOf(parameter(0, "long[]", "ids", null)),
                ),
                InputValue.ArrayValue(listOf(element)),
                "array",
            ),
            Triple(
                fixture(
                    "SELECT * FROM users WHERE payload = #{payload}",
                    listOf(parameter(0, "java.util.Map<java.lang.String,java.lang.Long>", "payload", "payload")),
                ),
                InputValue.MapValue(mapOf("id" to element)),
                "Map",
            ),
        )

        cases.forEach { (fixture, value, description) ->
            val result = prepare(fixture, listOf(value))
            val failure = result as PreparationResult.Failed
            assertEquals(description, PreparationFailureKind.UNSUPPORTED_TYPE_HANDLER, failure.failure.kind)
            assertEquals(description, "mybatis-custom-type-handler-unsupported", failure.failure.code)
        }
    }

    @Test
    fun soleUnannotatedRawStringIsRenderedByStockMyBatisAndRetainsRawProvenance() {
        val fixture = fixture(
            "SELECT " + raw("value") + " AS RESULT_VALUE",
            listOf(parameter(0, "java.lang.String", "value", null)),
        )

        val execution = success(
            prepare(fixture, listOf(InputValue.Text("7"))),
        )

        assertEquals("SELECT 7 AS RESULT_VALUE", normalize(execution.sqlWithPlaceholders))
        assertTrue(execution.orderedBindings.isEmpty())
        assertEquals(1, execution.rawInterpolations.size)
        assertEquals(
            contract(fixture).requirements.single().id,
            execution.rawInterpolations.single().requirementId,
        )
        assertEquals(
            setOf(InputUsageKind.BOUND_PLACEHOLDER, InputUsageKind.RAW_INTERPOLATION),
            contract(fixture).requirements.single().usageKinds,
        )
    }


    private fun prepare(
        fixture: Fixture,
        values: List<InputValue>,
    ): PreparationResult {
        val contract = contract(fixture)
        check(!contract.isPreparationBlocked) {
            "fixture contract unexpectedly blocked: ${contract.blockingProblems.map { it.code }}"
        }
        check(values.size == contract.requirements.size)

        val provided = contract.requirements.zip(values).map { (requirement, value) ->
            ProvidedInput(requirement.id, value, ExecutionInputOrigin.USER_ENTERED)
        }
        val environment = (
            InputEnvironment.validate(contract, provided) as InputEnvironmentResult.Success
            ).environment
        val source = XmlMapperPreparationSource(
            fixture.graph,
            mapperMethod = fixture.mapper,
        )
        val request = MyBatisPreparationRequest.create(source, contract, environment)
        check(request is PreparationRequestResult.Ready) {
            "fixture request unexpectedly rejected: $request"
        }
        return XmlMapperPreparationEngine.prepare(request.request)
    }

    private fun contract(fixture: Fixture) =
        MyBatisInvocationContractFactory.build(
            XmlMapperPreparationSource(fixture.graph, mapperMethod = fixture.mapper),
        )

    private fun fixture(
        sql: String,
        parameters: List<JavaMethodParameterMetadata>,
    ): Fixture {
        val statementId = XmlStatementId(XML_FILE, "example.Mapper", "find")
        val content = mapperDocument(
            "example.Mapper",
            "<select id=\"find\">$sql</select>",
        )
        val graph = StatementSourceGraph(
            CapturedStatement(statementId, StatementKind.SELECT, SourceRange(0, content.length)),
            listOf(SourceSnapshot(XML_FILE, XML_REVISION, content)),
            emptyList(),
        )
        val javaContent = "x".repeat(256)
        val mapper = XmlMapperMethodCapture(
            statementId = statementId,
            mapperSource = SourceSnapshot(JAVA_FILE, JAVA_REVISION, javaContent),
            methodSourceRange = SourceRange(20, 120),
            parameters = parameters,
        )
        return Fixture(graph, mapper)
    }

    private fun parameter(
        index: Int,
        type: String,
        sourceName: String?,
        alias: String?,
    ) = JavaMethodParameterMetadata(
        index = index,
        sourceName = sourceName,
        typeIdentity = JavaTypeIdentity(type),
        myBatisParamAlias = alias,
    )

    private fun mapperDocument(namespace: String, body: String): String = """
        <?xml version="1.0" encoding="UTF-8" ?>
        <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
        <mapper namespace="$namespace">$body</mapper>
    """.trimIndent()

    private fun raw(name: String): String = 36.toChar().toString() + "{" + name + "}"

    private fun success(result: PreparationResult) = when (result) {
        is PreparationResult.Success -> result.execution
        is PreparationResult.Failed -> throw AssertionError(
            "Expected XML bound preparation success but got kind=${result.failure.kind}, " +
                "code=${result.failure.code}, property=${result.failure.bindingProperty}, " +
                "diagnosticType=${result.failure.diagnosticType}",
        )
    }

    private fun assertFailure(
        result: PreparationResult,
        kind: PreparationFailureKind,
        code: String,
    ) {
        val failure = (result as PreparationResult.Failed).failure
        assertEquals(kind, failure.kind)
        assertEquals(code, failure.code)
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
