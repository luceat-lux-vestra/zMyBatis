package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.ExecutionInputOrigin
import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.InputEnvironmentResult
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.input.InternalBindingKind
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.input.ProvidedInput
import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationRequestResult
import com.algorist.zMyBatis.core.preparation.PreparationResult
import com.algorist.zMyBatis.core.preparation.PreparationSource
import com.algorist.zMyBatis.core.preparation.PreparedBindingOrigin
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
import java.math.BigInteger
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MyBatisForeachPreparationEngineTest {
    @Test
    fun listForeachBindingsAreCapturedAsMyBatisAdditionalParameters() {
        val result = prepare(
            fixture(
                """
                    <script>
                      select * from users where id in
                      <foreach collection="ids" item="item" open="(" separator="," close=")">
                        #{item}
                      </foreach>
                    </script>
                """.trimIndent(),
                listOf(listParameter("ids", 1, 2, 3)),
            ),
        )

        assertTrue(result is PreparationResult.Success)
        result as PreparationResult.Success
        assertEquals("select * from users where id in ( ? , ? , ? )", normalizeSql(result.execution.sqlWithPlaceholders))
        assertEquals(listOf(integer(1), integer(2), integer(3)), result.execution.orderedBindings.map { it.value })
        result.execution.orderedBindings.forEach { binding ->
            assertTrue(binding.additionalParameter)
            val origin = binding.origin as PreparedBindingOrigin.MyBatisAdditional
            assertEquals(InternalBindingKind.FOREACH_ITEM, origin.internalBinding.kind)
        }
    }

    @Test
    fun explicitIndexUsesStockMyBatisIterationValues() {
        val result = prepare(
            fixture(
                """
                    <script>
                      select
                      <foreach collection="ids" item="item" index="idx" separator=",">
                        #{idx}, #{item}
                      </foreach>
                    </script>
                """.trimIndent(),
                listOf(listParameter("ids", 10, 20)),
            ),
        )

        assertTrue(result is PreparationResult.Success)
        result as PreparationResult.Success
        assertEquals(
            listOf(integer(0), integer(10), integer(1), integer(20)),
            result.execution.orderedBindings.map { it.value },
        )
        assertTrue(result.execution.orderedBindings.all { it.additionalParameter })
        assertEquals(
            listOf(
                InternalBindingKind.FOREACH_INDEX,
                InternalBindingKind.FOREACH_ITEM,
                InternalBindingKind.FOREACH_INDEX,
                InternalBindingKind.FOREACH_ITEM,
            ),
            result.execution.orderedBindings.map {
                (it.origin as PreparedBindingOrigin.MyBatisAdditional).internalBinding.kind
            },
        )
    }

    @Test
    fun mapForeachDelegatesEntryKeyAndValueSemanticsToMyBatis() {
        val result = prepare(
            fixture(
                """
                    <script>
                      select
                      <foreach collection="entries" item="value" index="key" separator=",">
                        #{key}, #{value}
                      </foreach>
                    </script>
                """.trimIndent(),
                listOf(
                    Parameter(
                        javaType = "java.util.Map<java.lang.String,java.lang.Long>",
                        alias = "entries",
                        value = InputValue.MapValue(
                            linkedMapOf(
                                "left" to integer(7),
                                "right" to integer(9),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertTrue(result is PreparationResult.Success)
        result as PreparationResult.Success
        assertEquals(
            listOf(InputValue.Text("left"), integer(7), InputValue.Text("right"), integer(9)),
            result.execution.orderedBindings.map { it.value },
        )
    }

    @Test
    fun nestedItemPropertyKeepsResolvedValues() {
        val result = prepare(
            fixture(
                """
                    <script>
                      select * from users where id in
                      <foreach collection="rows" item="row" open="(" separator="," close=")">
                        #{row.id}
                      </foreach>
                    </script>
                """.trimIndent(),
                listOf(
                    Parameter(
                        javaType = "java.util.List<java.util.Map<java.lang.String,java.lang.Long>>",
                        alias = "rows",
                        value = InputValue.ListValue(
                            listOf(
                                InputValue.MapValue(linkedMapOf("id" to integer(31))),
                                InputValue.MapValue(linkedMapOf("id" to integer(32))),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertTrue(result is PreparationResult.Success)
        result as PreparationResult.Success
        assertEquals(listOf(integer(31), integer(32)), result.execution.orderedBindings.map { it.value })
        assertTrue(result.execution.orderedBindings.all { it.additionalParameter })
    }

    @Test
    fun emptyCollectionProducesNoFabricatedGeneratedBinding() {
        val result = prepare(
            fixture(
                """
                    <script>
                      select 1
                      <foreach collection="ids" item="item" open="(" separator="," close=")">
                        #{item}
                      </foreach>
                    </script>
                """.trimIndent(),
                listOf(listParameter("ids")),
            ),
        )

        assertTrue(result is PreparationResult.Success)
        result as PreparationResult.Success
        assertEquals("select 1", normalizeSql(result.execution.sqlWithPlaceholders))
        assertTrue(result.execution.orderedBindings.isEmpty())
    }

    @Test
    fun siblingForeachNodesWithDisjointLocalsRemainIndependent() {
        val result = prepare(
            fixture(
                """
                    <script>
                      select
                      <foreach collection="leftIds" item="leftItem" separator=",">#{leftItem}</foreach>
                      union all select
                      <foreach collection="rightIds" item="rightItem" separator=",">#{rightItem}</foreach>
                    </script>
                """.trimIndent(),
                listOf(
                    listParameter("leftIds", 1, 2),
                    listParameter("rightIds", 8, 9),
                ),
            ),
        )

        assertTrue(result is PreparationResult.Success)
        result as PreparationResult.Success
        assertEquals(
            listOf(integer(1), integer(2), integer(8), integer(9)),
            result.execution.orderedBindings.map { it.value },
        )
        assertTrue(result.execution.orderedBindings.all { it.additionalParameter })
    }

    @Test
    fun nestedForeachIsOwnedByStockMyBatis() {
        val result = prepare(
            fixture(
                """
                    <script>
                      select
                      <foreach collection="groups" item="group" separator=",">
                        <foreach collection="group" item="item" separator=",">#{item}</foreach>
                      </foreach>
                    </script>
                """.trimIndent(),
                listOf(
                    Parameter(
                        javaType = "java.util.List<java.util.List<java.lang.Long>>",
                        alias = "groups",
                        value = InputValue.ListValue(
                            listOf(
                                InputValue.ListValue(listOf(integer(1), integer(2))),
                                InputValue.ListValue(listOf(integer(3))),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertTrue(result is PreparationResult.Success)
        result as PreparationResult.Success
        assertEquals(
            listOf(integer(1), integer(2), integer(3)),
            result.execution.orderedBindings.map { it.value },
        )
        assertTrue(result.execution.orderedBindings.all { it.additionalParameter })
    }

    @Test
    fun concurrentForeachPreparationsCannotCrossContaminateGeneratedValues() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 20).map { seed ->
                executor.submit<List<InputValue>> {
                    val result = prepare(
                        fixture(
                            """
                                <script>
                                  select
                                  <foreach collection="ids" item="item" separator=",">#{item}</foreach>
                                </script>
                            """.trimIndent(),
                            listOf(listParameter("ids", seed.toLong(), (seed + 100).toLong())),
                        ),
                    )
                    check(result is PreparationResult.Success) { "foreach preparation failed for $seed: $result" }
                    result.execution.orderedBindings.map { it.value }
                }
            }

            assertEquals(
                (0 until 20).map {
                    listOf(integer(it.toLong()), integer((it + 100).toLong()))
                },
                futures.map { it.get(10, TimeUnit.SECONDS) },
            )
        } finally {
            executor.shutdownNow()
        }
    }

    private fun prepare(fixture: Fixture): PreparationResult {
        val request = MyBatisPreparationRequest.create(
            fixture.source,
            fixture.contract,
            fixture.environment,
        )
        check(request is PreparationRequestResult.Ready)
        return MyBatisPreparationEngine.prepare(request.request)
    }

    private fun fixture(
        script: String,
        parameters: List<Parameter>,
    ): Fixture {
        val fileId = SourceFileId("fixture/ForeachMapper.java")
        val revision = SourceRevision("foreach-revision-1")
        val snapshot = SourceSnapshot(fileId, revision, "authoritative-foreach-source")
        val types = parameters.map { JavaTypeIdentity(it.javaType) }
        val statementId = JavaStatementId(
            fileId,
            "fixture.ForeachMapper",
            MethodSignature("query", types),
        )
        val capture = JavaAnnotationStatementCapture(
            StatementSourceGraph(
                CapturedStatement(
                    statementId,
                    StatementKind.SELECT,
                    SourceRange(0, snapshot.content.length),
                ),
                listOf(snapshot),
                emptyList(),
            ),
            listOf(script),
            parameters.mapIndexed { index, parameter ->
                JavaMethodParameterMetadata(index, parameter.alias, types[index], parameter.alias)
            },
        )
        val source = PreparationSource.JavaAnnotation(capture)
        val contract = MyBatisInvocationContractFactory.build(source)
        check(!contract.isPreparationBlocked)
        val environment = InputEnvironment.validate(
            contract,
            contract.requirements.map { requirement ->
                val index = requirement.provenance.evidence
                    .filterIsInstance<com.algorist.zMyBatis.core.input.InputEvidence.MapperMethodParameter>()
                    .single()
                    .index
                ProvidedInput(
                    requirement.id,
                    parameters[index].value,
                    ExecutionInputOrigin.USER_ENTERED,
                )
            },
        )
        check(environment is InputEnvironmentResult.Success)
        return Fixture(source, contract, environment.environment)
    }

    private fun listParameter(alias: String, vararg values: Long) = Parameter(
        javaType = "java.util.List<java.lang.Long>",
        alias = alias,
        value = InputValue.ListValue(values.map(::integer)),
    )

    private fun integer(value: Long) = InputValue.IntegerValue(BigInteger.valueOf(value))

    private fun normalizeSql(sql: String): String = sql.trim().replace(Regex("\\s+"), " ")

    private data class Fixture(
        val source: PreparationSource.JavaAnnotation,
        val contract: ParameterContract,
        val environment: InputEnvironment,
    )

    private data class Parameter(
        val javaType: String,
        val alias: String,
        val value: InputValue,
    )
}
