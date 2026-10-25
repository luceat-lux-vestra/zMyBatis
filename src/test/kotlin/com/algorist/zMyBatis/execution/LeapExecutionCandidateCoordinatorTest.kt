package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.execution.ExecutionTargetDescriptor
import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.ExplicitSchemaIdentity
import com.algorist.zMyBatis.core.execution.ResolvedExecutionTarget
import com.algorist.zMyBatis.core.execution.StableDataSourceId
import com.algorist.zMyBatis.core.input.ExecutionInputOrigin
import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.InputEnvironmentResult
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.input.JavaAnnotationParameterContractFactory
import com.algorist.zMyBatis.core.input.ProvidedInput
import com.algorist.zMyBatis.core.materialization.MaterializationFailureKind
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
import com.intellij.openapi.progress.ProcessCanceledException
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LeapExecutionCandidateCoordinatorTest {
    @Test
    fun zeroBindingCandidateIsBoundToExactResolvedTarget() {
        val target = postgresTarget()

        val result = candidate("select 1", emptyList(), target)

        assertTrue(result is LeapExecutionCandidateResult.Ready)
        val execution = (result as LeapExecutionCandidateResult.Ready).execution
        assertEquals("select 1", execution.executionSql)
        assertEquals(target.targetId, execution.executionTargetId)
        assertEquals(ConventionalMyBatisDatabaseIds.POSTGRESQL, execution.executionDialectId)
    }

    @Test
    fun postgresqlBigintCandidateUsesProvenBoundedMaterializer() {
        val target = postgresTarget()

        val result = candidate(
            "select #{id,jdbcType=BIGINT}",
            listOf(Parameter("java.lang.Long", "id", InputValue.IntegerValue(BigInteger.valueOf(41)))),
            target,
        )

        assertTrue(result is LeapExecutionCandidateResult.Ready)
        val execution = (result as LeapExecutionCandidateResult.Ready).execution
        assertEquals("select 41", execution.executionSql)
        assertEquals(target.targetId, execution.executionTargetId)
        assertEquals(ConventionalMyBatisDatabaseIds.POSTGRESQL, execution.executionDialectId)
    }

    @Test
    fun unsupportedBoundTypeFailsWithoutLegacyFallback() {
        val result = candidate(
            "select #{name}",
            listOf(Parameter("java.lang.String", "name", InputValue.Text("Ada"))),
            postgresTarget(),
        )

        assertTrue(result is LeapExecutionCandidateResult.MaterializationFailed)
        val failure = (result as LeapExecutionCandidateResult.MaterializationFailed).failure
        assertEquals(MaterializationFailureKind.BINDING_METADATA_UNSUPPORTED, failure.kind)
    }


    @Test
    fun inactiveInvocationStopsBeforeCandidatePreparation() {
        var cancellationChecks = 0

        val result = candidate(
            "select 1",
            emptyList(),
            postgresTarget(),
            isInvocationActive = { false },
            checkCanceled = { cancellationChecks++ },
        )

        assertSame(LeapExecutionCandidateResult.InvocationInvalidated, result)
        assertEquals(1, cancellationChecks)
    }

    @Test
    fun successfulCandidateChecksCancellationAtEveryStageBoundary() {
        var cancellationChecks = 0

        val result = candidate(
            "select 1",
            emptyList(),
            postgresTarget(),
            checkCanceled = { cancellationChecks++ },
        )

        assertTrue(result is LeapExecutionCandidateResult.Ready)
        assertEquals(4, cancellationChecks)
    }

    @Test
    fun cancellationProbePreservesProcessCanceledExceptionIdentity() {
        val cancellation = ProcessCanceledException()

        try {
            candidate(
                "select 1",
                emptyList(),
                postgresTarget(),
                checkCanceled = { throw cancellation },
            )
            error("cancellation must escape candidate construction")
        } catch (actual: ProcessCanceledException) {
            assertSame(cancellation, actual)
        }
    }

    private fun candidate(
        sql: String,
        parameters: List<Parameter>,
        target: ResolvedExecutionTarget,
        isInvocationActive: () -> Boolean = { true },
        checkCanceled: () -> Unit = {},
    ): LeapExecutionCandidateResult {
        val capture = capture(sql, parameters)
        val contract = JavaAnnotationParameterContractFactory.build(capture)
        assertFalse("test fixture unexpectedly produced a blocked contract", contract.isPreparationBlocked)

        val provided = contract.requirements.map { requirement ->
            val index = requirement.id.value.substringAfter("java-param:").toInt()
            ProvidedInput(requirement.id, parameters[index].value, ExecutionInputOrigin.USER_ENTERED)
        }
        val environment = InputEnvironment.validate(contract, provided)
        assertTrue(environment is InputEnvironmentResult.Success)

        return LeapExecutionCandidateCoordinator.build(
            source = PreparationSource.JavaAnnotation(capture),
            parameterContract = contract,
            inputEnvironment = (environment as InputEnvironmentResult.Success).environment,
            target = target,
            isInvocationActive = isInvocationActive,
            checkCanceled = checkCanceled,
        )
    }

    private fun capture(
        sql: String,
        parameters: List<Parameter>,
    ): JavaAnnotationStatementCapture {
        val fileId = SourceFileId("fixture/Mapper.java")
        val types = parameters.map { JavaTypeIdentity(it.type) }
        val statementId = JavaStatementId(
            fileId,
            "fixture.Mapper",
            MethodSignature("query", types),
        )
        val snapshot = SourceSnapshot(fileId, SourceRevision("revision-1"), "authoritative-java-source")
        val graph = StatementSourceGraph(
            CapturedStatement(statementId, StatementKind.SELECT, SourceRange(0, snapshot.content.length)),
            listOf(snapshot),
            emptyList(),
        )
        return JavaAnnotationStatementCapture(
            graph,
            listOf(sql),
            parameters.mapIndexed { index, parameter ->
                JavaMethodParameterMetadata(
                    index = index,
                    sourceName = parameter.alias,
                    typeIdentity = types[index],
                    myBatisParamAlias = parameter.alias,
                )
            },
        )
    }

    private fun postgresTarget(): ResolvedExecutionTarget = ResolvedExecutionTarget(
        descriptor = ExecutionTargetDescriptor(
            targetId = ExecutionTargetId(
                dataSourceId = StableDataSourceId("fixture-postgresql"),
                schema = ExplicitSchemaIdentity("public"),
            ),
            dataSourceDisplayName = "Fixture PostgreSQL",
        ),
        effectiveDatabaseId = MyBatisDatabaseId(ConventionalMyBatisDatabaseIds.POSTGRESQL),
    )

    private data class Parameter(
        val type: String,
        val alias: String,
        val value: InputValue,
    )
}
