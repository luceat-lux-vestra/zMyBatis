package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.ExecutionInputOrigin
import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.InputEnvironmentResult
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.input.InternalBinding
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.input.ProvidedInput
import com.algorist.zMyBatis.core.input.XmlMapperMethodParameterContractFactory
import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import com.algorist.zMyBatis.core.preparation.PreparationRequestResult
import com.algorist.zMyBatis.core.preparation.PreparationResult
import com.algorist.zMyBatis.core.preparation.PreparedBindingOrigin
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

class XmlMapperForeachPreparationTest {
    @Test
    fun explicitListForeachUsesStockGeneratedItemBindings() {
        val fixture = fixture(
            """
            SELECT * FROM users WHERE id IN
            <foreach collection="ids" item="item" open="(" separator="," close=")">
              #{item}
            </foreach>
            """.trimIndent(),
            listOf(parameter(0, "java.util.List<java.lang.Long>", "ids", "ids")),
        )

        val execution = success(
            prepare(
                fixture,
                listOf(
                    InputValue.ListValue(
                        listOf(longValue(11), longValue(22), longValue(33)),
                    ),
                ),
            ),
        )

        assertEquals("SELECT * FROM users WHERE id IN ( ? , ? , ? )", normalize(execution.sqlWithPlaceholders))
        assertEquals(
            listOf(longValue(11), longValue(22), longValue(33)),
            execution.orderedBindings.map { it.value },
        )
        execution.orderedBindings.forEach { binding ->
            assertTrue(binding.additionalParameter)
            val origin = binding.origin as PreparedBindingOrigin.MyBatisAdditional
            assertEquals("item", origin.internalBinding.name)
        }
    }

    @Test
    fun explicitIndexRetainsItemAndIndexProvenance() {
        val fixture = fixture(
            """
            SELECT
            <foreach collection="ids" item="item" index="idx" separator=",">
              #{idx}, #{item}
            </foreach>
            """.trimIndent(),
            listOf(parameter(0, "java.util.List<java.lang.Long>", "ids", "ids")),
        )

        val execution = success(
            prepare(
                fixture,
                listOf(InputValue.ListValue(listOf(longValue(7), longValue(9)))),
            ),
        )

        assertEquals(
            listOf(longValue(0), longValue(7), longValue(1), longValue(9)),
            execution.orderedBindings.map { it.value },
        )
        assertEquals(
            listOf("idx", "item", "idx", "item"),
            execution.orderedBindings.map {
                (it.origin as PreparedBindingOrigin.MyBatisAdditional).internalBinding.name
            },
        )
    }

    @Test
    fun stockSingleCollectionShortcutsPrepareThroughSourceContract() {
        data class Case(
            val collection: String,
            val type: String,
            val value: InputValue,
        )

        val cases = listOf(
            Case(
                "list",
                "java.util.List<java.lang.Long>",
                InputValue.ListValue(listOf(longValue(1), longValue(2))),
            ),
            Case(
                "collection",
                "java.util.Collection<java.lang.Long>",
                InputValue.ListValue(listOf(longValue(3), longValue(4))),
            ),
            Case(
                "array",
                "long[]",
                InputValue.ArrayValue(listOf(longValue(5), longValue(6))),
            ),
        )

        cases.forEach { tc ->
            val fixture = fixture(
                """
                SELECT
                <foreach collection="${tc.collection}" item="item" separator=",">#{item}</foreach>
                """.trimIndent(),
                listOf(parameter(0, tc.type, "ids", null)),
            )

            val execution = success(prepare(fixture, listOf(tc.value)))

            assertEquals(2, execution.orderedBindings.size)
            assertTrue(
                execution.orderedBindings.all {
                    it.origin is PreparedBindingOrigin.MyBatisAdditional
                },
            )
        }
    }

    @Test
    fun explicitMapForeachDelegatesKeyAndValueIterationToStockMyBatis() {
        val fixture = fixture(
            """
            SELECT
            <foreach collection="entries" item="value" index="key" separator=",">
              #{key}, #{value}
            </foreach>
            """.trimIndent(),
            listOf(
                parameter(
                    0,
                    "java.util.Map<java.lang.String,java.lang.Long>",
                    "entries",
                    "entries",
                ),
            ),
        )

        val execution = success(
            prepare(
                fixture,
                listOf(
                    InputValue.MapValue(
                        linkedMapOf(
                            "left" to longValue(17),
                            "right" to longValue(19),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(
                InputValue.Text("left"),
                longValue(17),
                InputValue.Text("right"),
                longValue(19),
            ),
            execution.orderedBindings.map { it.value },
        )
        assertEquals(
            listOf("key", "value", "key", "value"),
            execution.orderedBindings.map {
                (it.origin as PreparedBindingOrigin.MyBatisAdditional).internalBinding.name
            },
        )
    }

    @Test
    fun emptyCollectionProducesNoFabricatedGeneratedBinding() {
        val fixture = fixture(
            """
            SELECT 1
            <foreach collection="ids" item="item" open="(" separator="," close=")">
              #{item}
            </foreach>
            """.trimIndent(),
            listOf(parameter(0, "java.util.List<java.lang.Long>", "ids", "ids")),
        )

        val execution = success(
            prepare(fixture, listOf(InputValue.ListValue(emptyList()))),
        )

        assertEquals("SELECT 1", normalize(execution.sqlWithPlaceholders))
        assertTrue(execution.orderedBindings.isEmpty())
    }

    @Test
    fun forgedForeachLocalContractFailsBeforeDynamicEvaluation() {
        val fixture = fixture(
            """SELECT <foreach collection="ids" item="item" separator=",">#{item}</foreach>""",
            listOf(parameter(0, "java.util.List<java.lang.Long>", "ids", "ids")),
        )
        val authentic = contract(fixture)
        val forgedLocal = authentic.internalBindings.single().let { local ->
            InternalBinding(
                name = "forged",
                kind = local.kind,
                provenance = local.provenance,
            )
        }
        val forged = ParameterContract(
            statementId = authentic.statementId,
            requirements = authentic.requirements,
            aliases = authentic.aliases,
            internalBindings = listOf(forgedLocal),
            blockingProblems = emptyList(),
            sourceRevisions = authentic.sourceRevisions,
        )

        assertFailure(
            prepare(
                fixture,
                listOf(InputValue.ListValue(listOf(longValue(1)))),
                forged,
            ),
            PreparationFailureKind.BINDING_RESOLUTION,
            "xml-preparation-foreach-source-contract-mismatch",
        )
    }

    @Test
    fun tamperedDynamicSourceCannotReusePreviouslyProvenForeachContract() {
        val fixture = fixture(
            """SELECT <foreach collection="ids" item="item" separator=",">#{item}</foreach>""",
            listOf(parameter(0, "java.util.List<java.lang.Long>", "ids", "ids")),
        )
        val authentic = contract(fixture)
        val tampered = fixture.copy(
            graph = graph(
                """SELECT <if test="ids != null">#{ids}</if>""",
                revision = XML_REVISION,
            ),
        )

        assertFailure(
            prepare(
                tampered,
                listOf(InputValue.ListValue(listOf(longValue(1)))),
                authentic,
            ),
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "xml-preparation-foreach-source-unsupported",
        )
    }

    private fun prepare(
        fixture: Fixture,
        values: List<InputValue>,
        contract: ParameterContract = contract(fixture),
    ): PreparationResult {
        check(values.size == contract.requirements.size)
        val provided = contract.requirements.zip(values).map { (requirement, value) ->
            ProvidedInput(requirement.id, value, ExecutionInputOrigin.USER_ENTERED)
        }
        val environment = (
            InputEnvironment.validate(contract, provided) as InputEnvironmentResult.Success
            ).environment
        val request = MyBatisPreparationRequest.create(
            XmlMapperPreparationSource(
                fixture.graph,
                additionalAuthoritySnapshots = listOf(fixture.mapper.mapperSource),
            ),
            contract,
            environment,
        )
        check(request is PreparationRequestResult.Ready) {
            "fixture request unexpectedly rejected: $request"
        }
        return XmlMapperPreparationEngine.prepare(request.request)
    }

    private fun contract(fixture: Fixture): ParameterContract =
        XmlMapperMethodParameterContractFactory.build(fixture.graph, fixture.mapper)

    private fun fixture(
        sql: String,
        parameters: List<JavaMethodParameterMetadata>,
    ): Fixture {
        val graph = graph(sql, XML_REVISION)
        val mapper = XmlMapperMethodCapture(
            statementId = graph.rootStatement.id as XmlStatementId,
            mapperSource = SourceSnapshot(JAVA_FILE, JAVA_REVISION, "x".repeat(256)),
            methodSourceRange = SourceRange(20, 120),
            parameters = parameters,
        )
        return Fixture(graph, mapper)
    }

    private fun graph(
        sql: String,
        revision: SourceRevision,
    ): StatementSourceGraph {
        val statementId = XmlStatementId(XML_FILE, "example.Mapper", "find")
        val declaration = "<select id=\"find\">$sql</select>"
        val content = mapperDocument("example.Mapper", declaration)
        return StatementSourceGraph(
            rootStatement = CapturedStatement(
                statementId,
                StatementKind.SELECT,
                SourceRange(0, content.length),
            ),
            sourceSnapshots = listOf(SourceSnapshot(XML_FILE, revision, content)),
            dependencies = emptyList(),
        )
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

    private fun longValue(value: Long): InputValue.IntegerValue =
        InputValue.IntegerValue(BigInteger.valueOf(value))

    private fun success(result: PreparationResult) = when (result) {
        is PreparationResult.Success -> result.execution
        is PreparationResult.Failed -> throw AssertionError(
            "Expected XML foreach preparation success but got kind=${result.failure.kind}, " +
                "code=${result.failure.code}, property=${result.failure.bindingProperty}, " +
                "diagnosticType=${result.failure.diagnosticType}",
        )
    }

    private fun assertFailure(
        result: PreparationResult,
        kind: PreparationFailureKind,
        code: String,
    ) {
        val failure = result as PreparationResult.Failed
        assertEquals(kind, failure.failure.kind)
        assertEquals(code, failure.failure.code)
    }

    private fun normalize(sql: String): String = sql.trim().replace(Regex("\\s+"), " ")

    private data class Fixture(
        val graph: StatementSourceGraph,
        val mapper: XmlMapperMethodCapture,
    )

    private companion object {
        val XML_FILE = SourceFileId("vfs:/mapper.xml")
        val XML_REVISION = SourceRevision("xml-foreach-r1")
        val JAVA_FILE = SourceFileId("vfs:/Mapper.java")
        val JAVA_REVISION = SourceRevision("java-foreach-r1")
    }
}
