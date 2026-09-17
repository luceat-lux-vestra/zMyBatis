package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.ExecutionInputOrigin
import com.algorist.zMyBatis.core.input.ExpectedInputType
import com.algorist.zMyBatis.core.input.ForeachLocalRole
import com.algorist.zMyBatis.core.input.InputAlias
import com.algorist.zMyBatis.core.input.InputAliasKind
import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.InputEnvironmentResult
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputKind
import com.algorist.zMyBatis.core.input.InputNullability
import com.algorist.zMyBatis.core.input.InputProvenance
import com.algorist.zMyBatis.core.input.InputRequiredness
import com.algorist.zMyBatis.core.input.InputRequirement
import com.algorist.zMyBatis.core.input.InputRequirementId
import com.algorist.zMyBatis.core.input.InputShape
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.input.InternalBinding
import com.algorist.zMyBatis.core.input.InternalBindingKind
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.input.ProvidedInput
import com.algorist.zMyBatis.core.input.SourceEvidence
import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
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
    fun listForeachItemBindingsCarryExactInternalProvenance() {
        val result = prepare(
            fixture(
                script = """
                    <script>
                      select * from users where id in
                      <foreach collection="ids" item="item" open="(" separator="," close=")">
                        #{item}
                      </foreach>
                    </script>
                """.trimIndent(),
                parameters = listOf(listParameter("ids", 1, 2, 3)),
                locals = listOf(LocalSpec("item", InternalBindingKind.FOREACH_ITEM, ForeachLocalRole.ITEM)),
            ),
        )

        assertTrue(result is PreparationResult.Success)
        result as PreparationResult.Success
        assertEquals("select * from users where id in ( ? , ? , ? )", normalizeSql(result.execution.sqlWithPlaceholders))
        assertEquals(listOf(longValue(1), longValue(2), longValue(3)), result.execution.orderedBindings.map { it.value })
        result.execution.orderedBindings.forEach { binding ->
            assertTrue(binding.additionalParameter)
            val origin = binding.origin as PreparedBindingOrigin.MyBatisAdditional
            assertEquals("item", origin.internalBinding.name)
            assertEquals(InternalBindingKind.FOREACH_ITEM, origin.internalBinding.kind)
        }
    }

    @Test
    fun explicitIndexUsesStockMyBatisIterationValuesAndIndexProvenance() {
        val result = prepare(
            fixture(
                script = """
                    <script>
                      select
                      <foreach collection="ids" item="item" index="idx" separator=",">
                        #{idx}, #{item}
                      </foreach>
                    </script>
                """.trimIndent(),
                parameters = listOf(listParameter("ids", 10, 20)),
                locals = listOf(
                    LocalSpec("item", InternalBindingKind.FOREACH_ITEM, ForeachLocalRole.ITEM),
                    LocalSpec("idx", InternalBindingKind.FOREACH_INDEX, ForeachLocalRole.INDEX),
                ),
            ),
        )

        assertTrue(result is PreparationResult.Success)
        result as PreparationResult.Success
        assertEquals(
            listOf(longValue(0), longValue(10), longValue(1), longValue(20)),
            result.execution.orderedBindings.map { it.value },
        )
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
                script = """
                    <script>
                      select
                      <foreach collection="entries" item="value" index="key" separator=",">
                        #{key}, #{value}
                      </foreach>
                    </script>
                """.trimIndent(),
                parameters = listOf(
                    Parameter(
                        javaType = "java.util.Map<java.lang.String,java.lang.Long>",
                        alias = "entries",
                        expectedType = ExpectedInputType(
                            shape = InputShape.MAP,
                            javaTypeIdentity = JavaTypeIdentity("java.util.Map<java.lang.String,java.lang.Long>"),
                            nullability = InputNullability.NON_NULL,
                        ),
                        value = InputValue.MapValue(
                            linkedMapOf(
                                "left" to longValue(7),
                                "right" to longValue(9),
                            ),
                        ),
                        foreachCollection = "entries",
                    ),
                ),
                locals = listOf(
                    LocalSpec("value", InternalBindingKind.FOREACH_ITEM, ForeachLocalRole.ITEM),
                    LocalSpec("key", InternalBindingKind.FOREACH_INDEX, ForeachLocalRole.INDEX),
                ),
            ),
        )

        assertTrue(result is PreparationResult.Success)
        result as PreparationResult.Success
        assertEquals(
            listOf(InputValue.Text("left"), longValue(7), InputValue.Text("right"), longValue(9)),
            result.execution.orderedBindings.map { it.value },
        )
    }

    @Test
    fun nestedItemPropertyKeepsItemProvenance() {
        val rows = InputValue.ListValue(
            listOf(
                InputValue.MapValue(linkedMapOf("id" to longValue(31))),
                InputValue.MapValue(linkedMapOf("id" to longValue(32))),
            ),
        )
        val result = prepare(
            fixture(
                script = """
                    <script>
                      select * from users where id in
                      <foreach collection="rows" item="row" open="(" separator="," close=")">
                        #{row.id}
                      </foreach>
                    </script>
                """.trimIndent(),
                parameters = listOf(
                    Parameter(
                        javaType = "java.util.List<java.util.Map<java.lang.String,java.lang.Long>>",
                        alias = "rows",
                        expectedType = ExpectedInputType(
                            shape = InputShape.LIST,
                            javaTypeIdentity = JavaTypeIdentity(
                                "java.util.List<java.util.Map<java.lang.String,java.lang.Long>>",
                            ),
                            nullability = InputNullability.NON_NULL,
                        ),
                        value = rows,
                        foreachCollection = "rows",
                    ),
                ),
                locals = listOf(LocalSpec("row", InternalBindingKind.FOREACH_ITEM, ForeachLocalRole.ITEM)),
            ),
        )

        assertTrue(result is PreparationResult.Success)
        result as PreparationResult.Success
        assertEquals(listOf(longValue(31), longValue(32)), result.execution.orderedBindings.map { it.value })
        result.execution.orderedBindings.forEach {
            val origin = it.origin as PreparedBindingOrigin.MyBatisAdditional
            assertEquals("row", origin.internalBinding.name)
        }
    }

    @Test
    fun emptyCollectionProducesNoFabricatedGeneratedBinding() {
        val result = prepare(
            fixture(
                script = """
                    <script>
                      select 1
                      <foreach collection="ids" item="item" open="(" separator="," close=")">
                        #{item}
                      </foreach>
                    </script>
                """.trimIndent(),
                parameters = listOf(listParameter("ids")),
                locals = listOf(LocalSpec("item", InternalBindingKind.FOREACH_ITEM, ForeachLocalRole.ITEM)),
            ),
        )

        assertTrue(result is PreparationResult.Success)
        result as PreparationResult.Success
        assertEquals("select 1", normalizeSql(result.execution.sqlWithPlaceholders))
        assertTrue(result.execution.orderedBindings.isEmpty())
    }

    @Test
    fun siblingForeachNodesWithDisjointLocalsRemainUnambiguous() {
        val result = prepare(
            fixture(
                script = """
                    <script>
                      select
                      <foreach collection="leftIds" item="leftItem" separator=",">#{leftItem}</foreach>
                      union all select
                      <foreach collection="rightIds" item="rightItem" separator=",">#{rightItem}</foreach>
                    </script>
                """.trimIndent(),
                parameters = listOf(
                    listParameter("leftIds", 1, 2),
                    listParameter("rightIds", 8, 9),
                ),
                locals = listOf(
                    LocalSpec("leftItem", InternalBindingKind.FOREACH_ITEM, ForeachLocalRole.ITEM),
                    LocalSpec("rightItem", InternalBindingKind.FOREACH_ITEM, ForeachLocalRole.ITEM),
                ),
            ),
        )

        assertTrue(result is PreparationResult.Success)
        result as PreparationResult.Success
        assertEquals(
            listOf(longValue(1), longValue(2), longValue(8), longValue(9)),
            result.execution.orderedBindings.map { it.value },
        )
        assertEquals(
            listOf("leftItem", "leftItem", "rightItem", "rightItem"),
            result.execution.orderedBindings.map {
                (it.origin as PreparedBindingOrigin.MyBatisAdditional).internalBinding.name
            },
        )
    }

    @Test
    fun nestedForeachIsRejectedBeforeMyBatisEvaluation() {
        val result = prepare(
            fixture(
                script = """
                    <script>
                      select
                      <foreach collection="ids" item="outer">
                        <foreach collection="ids2" item="inner">#{inner}</foreach>
                      </foreach>
                    </script>
                """.trimIndent(),
                parameters = listOf(listParameter("ids", 1), listParameter("ids2", 2)),
                locals = listOf(
                    LocalSpec("outer", InternalBindingKind.FOREACH_ITEM, ForeachLocalRole.ITEM),
                    LocalSpec("inner", InternalBindingKind.FOREACH_ITEM, ForeachLocalRole.ITEM),
                ),
            ),
        )

        assertFailure(result, PreparationFailureKind.UNSUPPORTED_SEMANTIC, "mybatis-foreach-nesting-unsupported")
    }

    @Test
    fun sourceCannotForgeMyBatisGeneratedForeachNamespace() {
        val result = prepare(
            fixture(
                script = """
                    <script>
                      select #{__frch_item_0}
                      <foreach collection="ids" item="item">#{item}</foreach>
                    </script>
                """.trimIndent(),
                parameters = listOf(listParameter("ids", 1)),
                locals = listOf(LocalSpec("item", InternalBindingKind.FOREACH_ITEM, ForeachLocalRole.ITEM)),
            ),
        )

        assertFailure(
            result,
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "mybatis-foreach-generated-namespace-collision",
        )
    }

    @Test
    fun collectionRequiresExactForeachCollectionEvidence() {
        val result = prepare(
            fixture(
                script = "<script><foreach collection=\"ids\" item=\"item\">#{item}</foreach></script>",
                parameters = listOf(listParameter("ids", 1).copy(foreachCollection = null)),
                locals = listOf(LocalSpec("item", InternalBindingKind.FOREACH_ITEM, ForeachLocalRole.ITEM)),
            ),
        )

        assertFailure(
            result,
            PreparationFailureKind.BINDING_RESOLUTION,
            "mybatis-foreach-collection-provenance-missing",
            "ids",
        )
    }

    @Test
    fun wrongKindForeachLocalCannotBecomeAuthority() {
        val result = prepare(
            fixture(
                script = "<script><foreach collection=\"ids\" item=\"item\">#{item}</foreach></script>",
                parameters = listOf(listParameter("ids", 1)),
                locals = listOf(LocalSpec("item", InternalBindingKind.FOREACH_INDEX, ForeachLocalRole.INDEX)),
            ),
        )

        assertFailure(
            result,
            PreparationFailureKind.BINDING_RESOLUTION,
            "mybatis-foreach-source-contract-mismatch",
            "item",
        )
    }

    @Test
    fun foreachLocalCannotShadowCallerAlias() {
        val result = prepare(
            fixture(
                script = "<script><foreach collection=\"ids\" item=\"ids\">#{ids}</foreach></script>",
                parameters = listOf(listParameter("ids", 1)),
                locals = listOf(LocalSpec("ids", InternalBindingKind.FOREACH_ITEM, ForeachLocalRole.ITEM)),
            ),
        )

        assertFailure(
            result,
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "mybatis-foreach-local-shadowing-unsupported",
            "ids",
        )
    }

    @Test
    fun foreachLocalOgnlRemainsOutsideThisSlice() {
        val result = prepare(
            fixture(
                script = """
                    <script>
                      <foreach collection="ids" item="item">
                        <if test="item != null">#{item}</if>
                      </foreach>
                    </script>
                """.trimIndent(),
                parameters = listOf(listParameter("ids", 1)),
                locals = listOf(LocalSpec("item", InternalBindingKind.FOREACH_ITEM, ForeachLocalRole.ITEM)),
            ),
        )

        assertFailure(
            result,
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "java-annotation-dynamic-ognl-property-unproven",
            "item",
        )
    }

    @Test
    fun concurrentForeachPreparationsCannotCrossContaminateGeneratedValues() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 20).map { seed ->
                executor.submit<List<InputValue>> {
                    val result = prepare(
                        fixture(
                            script = """
                                <script>
                                  select
                                  <foreach collection="ids" item="item" separator=",">#{item}</foreach>
                                </script>
                            """.trimIndent(),
                            parameters = listOf(listParameter("ids", seed.toLong(), (seed + 100).toLong())),
                            locals = listOf(
                                LocalSpec("item", InternalBindingKind.FOREACH_ITEM, ForeachLocalRole.ITEM),
                            ),
                        ),
                    )
                    check(result is PreparationResult.Success) { "foreach preparation failed for $seed: $result" }
                    result.execution.orderedBindings.map { it.value }
                }
            }

            assertEquals(
                (0 until 20).map {
                    listOf(longValue(it.toLong()), longValue((it + 100).toLong()))
                },
                futures.map { it.get(10, TimeUnit.SECONDS) },
            )
        } finally {
            executor.shutdownNow()
        }
    }

    private fun prepare(fixture: Fixture): PreparationResult {
        val request = MyBatisPreparationRequest.create(
            PreparationSource.JavaAnnotation(fixture.capture),
            fixture.contract,
            fixture.environment,
        )
        check(request is PreparationRequestResult.Ready) { "foreach fixture request invalid: $request" }
        return MyBatisPreparationEngine.prepare(request.request)
    }

    private fun fixture(
        script: String,
        parameters: List<Parameter>,
        locals: List<LocalSpec>,
    ): Fixture {
        val fileId = SourceFileId("fixture/ForeachMapper.java")
        val revision = SourceRevision("foreach-revision-1")
        val snapshot = SourceSnapshot(fileId, revision, "authoritative-foreach-source")
        val range = SourceRange(0, snapshot.content.length)
        val types = parameters.map { JavaTypeIdentity(it.javaType) }
        val statementId = JavaStatementId(
            fileId,
            "fixture.ForeachMapper",
            MethodSignature("query", types),
        )
        val graph = StatementSourceGraph(
            CapturedStatement(statementId, StatementKind.SELECT, range),
            listOf(snapshot),
            emptyList(),
        )
        val capture = JavaAnnotationStatementCapture(
            graph,
            listOf(script),
            parameters.mapIndexed { index, parameter ->
                JavaMethodParameterMetadata(index, parameter.alias, types[index], parameter.alias)
            },
        )
        val source = SourceEvidence(fileId, revision, range)
        val requirements = parameters.mapIndexed { index, parameter ->
            val id = InputRequirementId("foreach-param:$index")
            val evidence = buildList {
                add(InputEvidence.MapperMethodParameter(index, parameter.alias, types[index], source))
                add(InputEvidence.ExplicitParamAlias(index, parameter.alias, source))
                add(InputEvidence.OgnlExpression(parameter.alias, source))
                parameter.foreachCollection?.let { add(InputEvidence.ForeachCollection(it, source)) }
            }
            InputRequirement(
                id = id,
                kind = InputKind.BOUND,
                expectedType = parameter.expectedType,
                requiredness = InputRequiredness.REQUIRED,
                provenance = InputProvenance(evidence),
            )
        }
        val aliases = parameters.mapIndexed { index, parameter ->
            InputAlias(
                name = parameter.alias,
                requirementId = requirements[index].id,
                kind = InputAliasKind.EXPLICIT_PARAM,
                provenance = requirements[index].provenance,
            )
        }
        val internalBindings = locals.map { local ->
            InternalBinding(
                name = local.name,
                kind = local.kind,
                provenance = InputProvenance(
                    listOf(InputEvidence.ForeachLocal(local.name, local.role, source)),
                ),
            )
        }
        val contract = ParameterContract(
            statementId = statementId,
            requirements = requirements,
            aliases = aliases,
            internalBindings = internalBindings,
            blockingProblems = emptyList(),
            sourceRevisions = mapOf(fileId to revision),
        )
        val environment = InputEnvironment.validate(
            contract,
            requirements.mapIndexed { index, requirement ->
                ProvidedInput(requirement.id, parameters[index].value, ExecutionInputOrigin.USER_ENTERED)
            },
        )
        check(environment is InputEnvironmentResult.Success) { "foreach fixture environment invalid: $environment" }
        return Fixture(capture, contract, environment.environment)
    }

    private fun listParameter(alias: String, vararg values: Long) = Parameter(
        javaType = "java.util.List<java.lang.Long>",
        alias = alias,
        expectedType = ExpectedInputType(
            shape = InputShape.LIST,
            javaTypeIdentity = JavaTypeIdentity("java.util.List<java.lang.Long>"),
            nullability = InputNullability.NON_NULL,
        ),
        value = InputValue.ListValue(values.map { longValue(it) }),
        foreachCollection = alias,
    )

    private fun assertFailure(
        result: PreparationResult,
        kind: PreparationFailureKind,
        code: String,
        property: String? = null,
    ) {
        assertTrue(result is PreparationResult.Failed)
        result as PreparationResult.Failed
        assertEquals(kind, result.failure.kind)
        assertEquals(code, result.failure.code)
        assertEquals(property, result.failure.bindingProperty)
    }

    private fun longValue(value: Long) = InputValue.IntegerValue(BigInteger.valueOf(value))

    private fun normalizeSql(sql: String): String = sql.trim().replace(Regex("\\s+"), " ")

    private data class Fixture(
        val capture: JavaAnnotationStatementCapture,
        val contract: ParameterContract,
        val environment: InputEnvironment,
    )

    private data class Parameter(
        val javaType: String,
        val alias: String,
        val expectedType: ExpectedInputType,
        val value: InputValue,
        val foreachCollection: String?,
    )

    private data class LocalSpec(
        val name: String,
        val kind: InternalBindingKind,
        val role: ForeachLocalRole,
    )
}
