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
import com.algorist.zMyBatis.core.preparation.PreparationSource
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MyBatisIsolationEdgeCaseTest {
    @Test
    fun sourceLiteralCannotCollideWithRawTopologyProofMarker() {
        val firstCandidate = "\u0000zmybatis_raw_slot_0_0\u0000"
        val result = MyBatisPreparationEngine.prepare(
            request(
                script = "select '$firstCandidate' " + "$" + "{fragment}",
                alias = "fragment",
                value = "#{id,typeHandler=fixture.DoesNotExist}",
            ),
        )

        assertTrue(result is PreparationResult.Failed)
        result as PreparationResult.Failed
        assertEquals(PreparationFailureKind.UNSUPPORTED_SEMANTIC, result.failure.kind)
        assertEquals("raw-interpolation-mybatis-token-topology-unsupported", result.failure.code)
    }

    @Test
    fun reservedRawCallerAliasCannotBeReinterpretedAsMyBatisContext() {
        val result = MyBatisPreparationEngine.prepare(
            request(
                script = "select " + "$" + "{_databaseId}",
                alias = "_databaseId",
                value = "users",
            ),
        )

        assertTrue(result is PreparationResult.Failed)
        result as PreparationResult.Failed
        assertEquals(PreparationFailureKind.UNSUPPORTED_SEMANTIC, result.failure.kind)
        assertEquals("raw-interpolation-expression-unsupported", result.failure.code)
    }

    @Test
    fun zeroParameterDynamicScriptPreparesThroughIsolatedRuntime() {
        val result = MyBatisPreparationEngine.prepare(
            noParameterRequest("<script>select 1</script>"),
        )

        assertTrue(result is PreparationResult.Success)
        result as PreparationResult.Success
        assertEquals("select 1", result.execution.sqlWithPlaceholders.trim())
        assertTrue(result.execution.orderedBindings.isEmpty())
        assertTrue(result.execution.rawInterpolations.isEmpty())
    }

    private fun noParameterRequest(script: String): MyBatisPreparationRequest {
        val fileId = SourceFileId("fixture/IsolationEdgeMapper.java")
        val revision = SourceRevision("isolation-edge-revision-1")
        val snapshot = SourceSnapshot(fileId, revision, "authoritative-isolation-edge-source")
        val statementId = JavaStatementId(
            fileId,
            "fixture.IsolationEdgeMapper",
            MethodSignature("query", emptyList()),
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
            emptyList(),
        )
        val source = PreparationSource.JavaAnnotation(capture)
        val contract = MyBatisInvocationContractFactory.build(source)
        val environment = InputEnvironment.validate(contract, emptyList())
        check(environment is InputEnvironmentResult.Success)
        val request = MyBatisPreparationRequest.create(source, contract, environment.environment)
        check(request is PreparationRequestResult.Ready)
        return request.request
    }

    private fun request(
        script: String,
        alias: String,
        value: String,
    ): MyBatisPreparationRequest {
        val fileId = SourceFileId("fixture/IsolationEdgeRawMapper.java")
        val revision = SourceRevision("isolation-edge-raw-revision-1")
        val snapshot = SourceSnapshot(fileId, revision, "authoritative-isolation-edge-raw-source")
        val typeIdentity = JavaTypeIdentity("java.lang.String")
        val statementId = JavaStatementId(
            fileId,
            "fixture.IsolationEdgeRawMapper",
            MethodSignature("query", listOf(typeIdentity)),
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
            listOf(JavaMethodParameterMetadata(0, alias, typeIdentity, alias)),
        )
        val source = PreparationSource.JavaAnnotation(capture)
        val contract = MyBatisInvocationContractFactory.build(source)
        check(!contract.isPreparationBlocked)
        val requirement = contract.requirements.single()
        val environment = InputEnvironment.validate(
            contract,
            listOf(
                ProvidedInput(
                    requirement.id,
                    InputValue.Text(value),
                    ExecutionInputOrigin.USER_ENTERED,
                ),
            ),
        )
        check(environment is InputEnvironmentResult.Success)
        val request = MyBatisPreparationRequest.create(source, contract, environment.environment)
        check(request is PreparationRequestResult.Ready)
        return request.request
    }
}
