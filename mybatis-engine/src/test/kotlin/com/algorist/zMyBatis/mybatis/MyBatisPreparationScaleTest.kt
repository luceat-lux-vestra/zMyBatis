package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.execution.ExecutionTargetDescriptor
import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.ExplicitSchemaIdentity
import com.algorist.zMyBatis.core.execution.ResolvedExecutionTarget
import com.algorist.zMyBatis.core.execution.StableDataSourceId
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
import com.algorist.zMyBatis.core.materialization.MaterializationFailureKind
import com.algorist.zMyBatis.core.materialization.MaterializationResult
import com.algorist.zMyBatis.core.materialization.PostgreSqlBigintExecutionMaterializer
import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationRequestResult
import com.algorist.zMyBatis.core.preparation.PreparationResult
import com.algorist.zMyBatis.core.preparation.PreparationSource
import com.algorist.zMyBatis.core.source.CapturedStatement
import com.algorist.zMyBatis.core.source.ConventionalMyBatisDatabaseIds
import com.algorist.zMyBatis.core.source.JavaAnnotationStatementCapture
import com.algorist.zMyBatis.core.source.JavaMethodParameterMetadata
import com.algorist.zMyBatis.core.source.JavaStatementId
import com.algorist.zMyBatis.core.source.JavaTypeIdentity
import com.algorist.zMyBatis.core.source.MethodSignature
import com.algorist.zMyBatis.core.source.MyBatisDatabaseId
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.StatementSourceGraph
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import org.apache.ibatis.session.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MyBatisPreparationScaleTest {
    @Test
    fun foreachPreparationScaleRecordsGrowthAndFailsClosedAtMaterialization() {
        val runtimeArtifact = MyBatisRuntimeArtifact.resolve(Configuration::class.java)
        assertNotNull("scale evidence requires the real local MyBatis runtime artifact", runtimeArtifact)
        assertEquals("file", runtimeArtifact!!.protocol)

        FOREACH_CARDINALITIES.forEach { cardinality ->
            val fixture = foreachFixture(cardinality)
            val request = when (
                val result = MyBatisPreparationRequest.create(
                    source = fixture.source,
                    parameterContract = fixture.contract,
                    inputEnvironment = fixture.environment,
                    effectiveDatabaseId = fixture.target.effectiveDatabaseId,
                )
            ) {
                is PreparationRequestResult.Ready -> result.request
                is PreparationRequestResult.Failed ->
                    throw AssertionError("scale fixture request failed for $cardinality: ${result.failure}")
            }

            val pipelineStartedNanos = System.nanoTime()
            val preparationStartedNanos = System.nanoTime()
            val preparation = MyBatisPreparationBoundary.prepare(request)
            val preparationElapsedNanos = System.nanoTime() - preparationStartedNanos
            assertTrue(
                "foreach scale preparation failed for cardinality=$cardinality: $preparation",
                preparation is PreparationResult.Success,
            )
            val prepared = (preparation as PreparationResult.Success).execution

            assertEquals(cardinality, prepared.orderedBindings.size)
            assertEquals(cardinality, prepared.sqlWithPlaceholders.count { it == '?' })
            assertTrue(prepared.rawInterpolations.isEmpty())

            val materializationStartedNanos = System.nanoTime()
            val materialization =
                PostgreSqlBigintExecutionMaterializer(fixture.target).materialize(prepared)
            val materializationElapsedNanos = System.nanoTime() - materializationStartedNanos
            val pipelineElapsedNanos = System.nanoTime() - pipelineStartedNanos

            assertTrue(
                "multi-binding foreach must remain fail-closed at materialization",
                materialization is MaterializationResult.Failed,
            )
            val failure = (materialization as MaterializationResult.Failed).failure
            assertEquals(MaterializationFailureKind.BINDING_COUNT_UNSUPPORTED, failure.kind)
            assertEquals("postgresql-bigint-binding-count-unsupported", failure.code)

            println(
                "ZMYBATIS_PREPARATION_SCALE phase=foreach-growth " +
                    "collection_items=$cardinality " +
                    "input_nodes=${cardinality + 1} " +
                    "ordered_bindings=${prepared.orderedBindings.size} " +
                    "placeholder_count=${prepared.sqlWithPlaceholders.count { it == '?' }} " +
                    "sql_chars=${prepared.sqlWithPlaceholders.length} " +
                    "runtime_artifact_protocol=${runtimeArtifact.protocol} " +
                    "preparation_elapsed_ms=${TimeUnit.NANOSECONDS.toMillis(preparationElapsedNanos)} " +
                    "materialization_elapsed_ms=${TimeUnit.NANOSECONDS.toMillis(materializationElapsedNanos)} " +
                    "pipeline_elapsed_ms=${TimeUnit.NANOSECONDS.toMillis(pipelineElapsedNanos)} " +
                    "materialization_failure=${failure.kind.name}",
            )
        }
    }

    private fun foreachFixture(cardinality: Int): ScaleFixture {
        require(cardinality > 1) {
            "scale cardinality must exercise unsupported multi-binding materialization"
        }

        val fileId = SourceFileId("fixture/PreparationScaleMapper.java")
        val revision = SourceRevision("fixture:494")
        val snapshot = SourceSnapshot(fileId, revision, "authoritative-preparation-scale-source")
        val range = SourceRange(0, snapshot.content.length)
        val listType = JavaTypeIdentity("java.util.List<java.lang.Long>")
        val statementId = JavaStatementId(
            sourceFileId = fileId,
            qualifiedMapperType = "fixture.PreparationScaleMapper",
            methodSignature = MethodSignature("findMany", listOf(listType)),
        )
        val graph = StatementSourceGraph(
            rootStatement = CapturedStatement(statementId, StatementKind.SELECT, range),
            sourceSnapshots = listOf(snapshot),
            dependencies = emptyList(),
        )
        val capture = JavaAnnotationStatementCapture(
            sourceGraph = graph,
            sqlSegments = listOf(
                """
                    <script>
                      select * from users where id in
                      <foreach collection="ids" item="item" open="(" separator="," close=")">
                        #{item}
                      </foreach>
                    </script>
                """.trimIndent(),
            ),
            parameters = listOf(
                JavaMethodParameterMetadata(
                    index = 0,
                    sourceName = "ids",
                    typeIdentity = listType,
                    myBatisParamAlias = "ids",
                ),
            ),
        )

        val sourceEvidence = SourceEvidence(fileId, revision, range)
        val requirementId = InputRequirementId("foreach-scale:ids")
        val provenance = InputProvenance(
            listOf(
                InputEvidence.MapperMethodParameter(0, "ids", listType, sourceEvidence),
                InputEvidence.ExplicitParamAlias(0, "ids", sourceEvidence),
                InputEvidence.OgnlExpression("ids", sourceEvidence),
                InputEvidence.ForeachCollection("ids", sourceEvidence),
            ),
        )
        val requirement = InputRequirement(
            id = requirementId,
            kind = InputKind.BOUND,
            expectedType = ExpectedInputType(
                shape = InputShape.LIST,
                javaTypeIdentity = listType,
                nullability = InputNullability.NON_NULL,
            ),
            requiredness = InputRequiredness.REQUIRED,
            provenance = provenance,
        )
        val contract = ParameterContract(
            statementId = statementId,
            requirements = listOf(requirement),
            aliases = listOf(
                InputAlias(
                    name = "ids",
                    requirementId = requirementId,
                    kind = InputAliasKind.EXPLICIT_PARAM,
                    provenance = provenance,
                ),
            ),
            internalBindings = listOf(
                InternalBinding(
                    name = "item",
                    kind = InternalBindingKind.FOREACH_ITEM,
                    provenance = InputProvenance(
                        listOf(
                            InputEvidence.ForeachLocal(
                                name = "item",
                                role = ForeachLocalRole.ITEM,
                                source = sourceEvidence,
                            ),
                        ),
                    ),
                ),
            ),
            blockingProblems = emptyList(),
            sourceRevisions = mapOf(fileId to revision),
        )
        val listValue = InputValue.ListValue(
            (0 until cardinality).map { index ->
                InputValue.IntegerValue(BigInteger.valueOf(index.toLong() + 1L))
            },
        )
        val environment = when (
            val result = InputEnvironment.validate(
                contract,
                listOf(
                    ProvidedInput(
                        requirementId = requirementId,
                        value = listValue,
                        origin = ExecutionInputOrigin.USER_ENTERED,
                    ),
                ),
            )
        ) {
            is InputEnvironmentResult.Success -> result.environment
            is InputEnvironmentResult.Failure ->
                throw AssertionError("scale fixture environment failed for $cardinality: ${result.failures}")
        }
        val target = ResolvedExecutionTarget(
            descriptor = ExecutionTargetDescriptor(
                targetId = ExecutionTargetId(
                    dataSourceId = StableDataSourceId("fixture-postgresql"),
                    schema = ExplicitSchemaIdentity("public"),
                ),
                dataSourceDisplayName = "Fixture PostgreSQL",
            ),
            effectiveDatabaseId = MyBatisDatabaseId(ConventionalMyBatisDatabaseIds.POSTGRESQL),
        )

        return ScaleFixture(
            source = PreparationSource.JavaAnnotation(capture),
            contract = contract,
            environment = environment,
            target = target,
        )
    }

    private data class ScaleFixture(
        val source: PreparationSource,
        val contract: ParameterContract,
        val environment: InputEnvironment,
        val target: ResolvedExecutionTarget,
    )

    private companion object {
        val FOREACH_CARDINALITIES = listOf(128, 1_024, 4_096)
    }
}
