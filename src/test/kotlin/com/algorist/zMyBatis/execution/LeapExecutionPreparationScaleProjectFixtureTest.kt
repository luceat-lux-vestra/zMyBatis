package com.algorist.zMyBatis.execution

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
import com.algorist.zMyBatis.mybatis.MyBatisPreparationBoundary
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.math.BigInteger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class LeapExecutionPreparationScaleProjectFixtureTest : BasePlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    fun testForeachPreparationScaleRecordsGrowthAndFailsClosedAtMaterialization() {
        assertFalse(
            "scale fixture itself must not run on EDT",
            ApplicationManager.getApplication().isDispatchThread,
        )

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

            val candidateStartedNanos = System.nanoTime()
            val candidate = LeapExecutionCandidateCoordinator.build(
                source = fixture.source,
                parameterContract = fixture.contract,
                inputEnvironment = fixture.environment,
                target = fixture.target,
            )
            val candidateElapsedNanos = System.nanoTime() - candidateStartedNanos

            assertTrue(
                "multi-binding foreach must remain fail-closed at materialization",
                candidate is LeapExecutionCandidateResult.MaterializationFailed,
            )
            val failure = (candidate as LeapExecutionCandidateResult.MaterializationFailed).failure
            assertEquals(MaterializationFailureKind.BINDING_COUNT_UNSUPPORTED, failure.kind)
            assertEquals("postgresql-bigint-binding-count-unsupported", failure.code)

            println(
                "ZMYBATIS_PREPARATION_SCALE phase=foreach-growth " +
                    "collection_items=$cardinality " +
                    "input_nodes=${cardinality + 1} " +
                    "ordered_bindings=${prepared.orderedBindings.size} " +
                    "placeholder_count=${prepared.sqlWithPlaceholders.count { it == '?' }} " +
                    "sql_chars=${prepared.sqlWithPlaceholders.length} " +
                    "preparation_elapsed_ms=${TimeUnit.NANOSECONDS.toMillis(preparationElapsedNanos)} " +
                    "candidate_elapsed_ms=${TimeUnit.NANOSECONDS.toMillis(candidateElapsedNanos)} " +
                    "materialization_failure=${failure.kind.name}",
            )
        }
    }

    fun testLargeForeachCandidateRunsOffEdtAndInvalidatesAfterPreparation() =
        withProductionStyleBackgroundTasks {
            val fixture = foreachFixture(BACKGROUND_RESPONSIVENESS_CARDINALITY)
            val builderStarted = CountDownLatch(1)
            val releaseBuilder = CountDownLatch(1)
            val gateTimedOut = AtomicBoolean(false)
            val builderWasOnEdt = AtomicBoolean(true)
            val coordinatorElapsedNanos = AtomicLong()
            val invocationProbeCount = AtomicInteger()
            val observed = CompletableFuture<LeapExecutionCandidateResult>()

            val builder: LeapExecutionCandidateBuilder =
                { source, parameterContract, inputEnvironment, target, isInvocationActive, checkCanceled ->
                    builderWasOnEdt.set(ApplicationManager.getApplication().isDispatchThread)
                    builderStarted.countDown()
                    if (!releaseBuilder.await(FIXTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        gateTimedOut.set(true)
                    }

                    val startedNanos = System.nanoTime()
                    val candidate = LeapExecutionCandidateCoordinator.build(
                        source = source,
                        parameterContract = parameterContract,
                        inputEnvironment = inputEnvironment,
                        target = target,
                        isInvocationActive = isInvocationActive,
                        checkCanceled = checkCanceled,
                    )
                    coordinatorElapsedNanos.set(System.nanoTime() - startedNanos)
                    candidate
                }

            val invocationActive = {
                invocationProbeCount.incrementAndGet() < POST_PREPARATION_INVALIDATION_PROBE
            }

            try {
                requestCandidate(
                    adapter = LeapExecutionCandidateAdapter(project, builder),
                    fixture = fixture,
                    isInvocationActive = invocationActive,
                    onResult = observed::complete,
                    onCancelled = {
                        observed.completeExceptionally(
                            AssertionError("invalidation fixture must not become ProgressIndicator cancellation"),
                        )
                    },
                )

                assertTrue(
                    "large foreach candidate background builder did not start",
                    builderStarted.await(FIXTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                )
                assertFalse("real candidate coordinator must run from a non-EDT worker", builderWasOnEdt.get())
                assertFalse("candidate must remain blocked at the deterministic fixture gate", observed.isDone)

                val edtSentinel = CompletableFuture<Unit>()
                ApplicationManager.getApplication().invokeLater(
                    { edtSentinel.complete(Unit) },
                    ModalityState.any(),
                )
                PlatformTestUtil.waitForFuture(edtSentinel, FIXTURE_TIMEOUT_MILLIS)
                assertFalse(
                    "EDT sentinel must complete while large candidate work is outstanding",
                    observed.isDone,
                )
            } finally {
                releaseBuilder.countDown()
            }

            val completed = PlatformTestUtil.waitForFuture(observed, FIXTURE_TIMEOUT_MILLIS)
            assertSame(LeapExecutionCandidateResult.InvocationInvalidated, completed)
            assertTrue(
                "invalidation must be observed at the coordinator probe immediately after preparation",
                invocationProbeCount.get() >= POST_PREPARATION_INVALIDATION_PROBE,
            )
            assertFalse(
                "large foreach fixture timed out at its deterministic gate",
                gateTimedOut.get(),
            )

            println(
                "ZMYBATIS_PREPARATION_SCALE phase=post-preparation-invalidation " +
                    "collection_items=$BACKGROUND_RESPONSIVENESS_CARDINALITY " +
                    "input_nodes=${BACKGROUND_RESPONSIVENESS_CARDINALITY + 1} " +
                    "execution_thread=BACKGROUND " +
                    "edt_sentinel_completed=true " +
                    "invalidation_probe=$POST_PREPARATION_INVALIDATION_PROBE " +
                    "observed_probe_count=${invocationProbeCount.get()} " +
                    "continuation=INVOCATION_INVALIDATED " +
                    "coordinator_elapsed_ms=${TimeUnit.NANOSECONDS.toMillis(coordinatorElapsedNanos.get())}",
            )
        }

    private fun requestCandidate(
        adapter: LeapExecutionCandidateAdapter,
        fixture: ScaleFixture,
        isInvocationActive: () -> Boolean,
        onResult: (LeapExecutionCandidateResult) -> Unit,
        onCancelled: () -> Unit,
    ) {
        runInEdtAndWait {
            adapter.requestCandidate(
                source = fixture.source,
                parameterContract = fixture.contract,
                inputEnvironment = fixture.environment,
                target = fixture.target,
                isInvocationActive = isInvocationActive,
                onResult = onResult,
                onCancelled = onCancelled,
            )
        }
    }

    private fun foreachFixture(cardinality: Int): ScaleFixture {
        require(cardinality > 1) { "scale cardinality must exercise unsupported multi-binding materialization" }

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

    private fun withProductionStyleBackgroundTasks(block: () -> Unit) {
        PlatformTestUtil.withSystemProperty<Nothing>(
            "intellij.progress.task.ignoreHeadless",
            "true",
        ) {
            block()
        }
    }

    private data class ScaleFixture(
        val source: PreparationSource,
        val contract: ParameterContract,
        val environment: InputEnvironment,
        val target: ResolvedExecutionTarget,
    )

    companion object {
        private val FOREACH_CARDINALITIES = listOf(128, 1_024, 4_096)
        private const val BACKGROUND_RESPONSIVENESS_CARDINALITY = 4_096

        // Adapter preflight is probe 1; coordinator probes 2/3 before preparation and probe 4 after it.
        private const val POST_PREPARATION_INVALIDATION_PROBE = 4
        private const val FIXTURE_TIMEOUT_SECONDS = 30L
        private const val FIXTURE_TIMEOUT_MILLIS = FIXTURE_TIMEOUT_SECONDS * 1_000
    }
}
