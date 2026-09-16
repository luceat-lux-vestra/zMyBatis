package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.ExecutionInputOrigin
import com.algorist.zMyBatis.core.input.ExpectedInputType
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
import com.algorist.zMyBatis.core.input.InputScalarType
import com.algorist.zMyBatis.core.input.InputShape
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.input.ProvidedInput
import com.algorist.zMyBatis.core.input.SourceEvidence
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
        val request = rawRequest(
            script = "select '$firstCandidate' ${'$'}{fragment}",
            alias = "fragment",
            value = "#{id,typeHandler=fixture.DoesNotExist}",
        )

        val result = MyBatisPreparationEngine.prepare(request)

        assertTrue(result is PreparationResult.Failed)
        result as PreparationResult.Failed
        assertEquals(PreparationFailureKind.UNSUPPORTED_SEMANTIC, result.failure.kind)
        assertEquals("raw-interpolation-mybatis-token-topology-unsupported", result.failure.code)
    }

    @Test
    fun reservedRawCallerAliasCannotBeReinterpretedAsMyBatisContext() {
        val request = rawRequest(
            script = "select ${'$'}{_databaseId}",
            alias = "_databaseId",
            value = "users",
        )

        val result = MyBatisPreparationEngine.prepare(request)

        assertTrue(result is PreparationResult.Failed)
        result as PreparationResult.Failed
        assertEquals(PreparationFailureKind.UNSUPPORTED_SEMANTIC, result.failure.kind)
        assertEquals("raw-interpolation-expression-unsupported", result.failure.code)
    }

    @Test
    fun zeroParameterDynamicScriptPreparesThroughIsolatedRuntime() {
        val result = MyBatisPreparationEngine.prepare(noParameterRequest("<script>select 1</script>"))

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
        val range = SourceRange(0, snapshot.content.length)
        val capture = JavaAnnotationStatementCapture(
            StatementSourceGraph(
                CapturedStatement(statementId, StatementKind.SELECT, range),
                listOf(snapshot),
                emptyList(),
            ),
            listOf(script),
            emptyList(),
        )
        val contract = ParameterContract(
            statementId = statementId,
            requirements = emptyList(),
            aliases = emptyList(),
            internalBindings = emptyList(),
            blockingProblems = emptyList(),
            sourceRevisions = mapOf(fileId to revision),
        )
        val environmentResult = InputEnvironment.validate(contract, emptyList())
        check(environmentResult is InputEnvironmentResult.Success)
        val requestResult = MyBatisPreparationRequest.create(
            PreparationSource.JavaAnnotation(capture),
            contract,
            environmentResult.environment,
        )
        check(requestResult is PreparationRequestResult.Ready)
        return requestResult.request
    }

    private fun rawRequest(
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
        val range = SourceRange(0, snapshot.content.length)
        val capture = JavaAnnotationStatementCapture(
            StatementSourceGraph(
                CapturedStatement(statementId, StatementKind.SELECT, range),
                listOf(snapshot),
                emptyList(),
            ),
            listOf(script),
            listOf(JavaMethodParameterMetadata(0, alias, typeIdentity, alias)),
        )
        val source = SourceEvidence(fileId, revision, range)
        val requirementId = InputRequirementId("isolation-edge-raw-param:0")
        val provenance = InputProvenance(
            listOf(
                InputEvidence.MapperMethodParameter(0, alias, typeIdentity, source),
                InputEvidence.ExplicitParamAlias(0, alias, source),
                InputEvidence.Placeholder(InputKind.RAW_INTERPOLATION, alias, source),
            ),
        )
        val requirement = InputRequirement(
            id = requirementId,
            kind = InputKind.RAW_INTERPOLATION,
            expectedType = ExpectedInputType(
                shape = InputShape.RAW_TEXT,
                scalarType = InputScalarType.STRING,
                javaTypeIdentity = typeIdentity,
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
                    name = alias,
                    requirementId = requirementId,
                    kind = InputAliasKind.EXPLICIT_PARAM,
                    provenance = provenance,
                ),
            ),
            internalBindings = emptyList(),
            blockingProblems = emptyList(),
            sourceRevisions = mapOf(fileId to revision),
        )
        val environmentResult = InputEnvironment.validate(
            contract,
            listOf(
                ProvidedInput(
                    requirementId,
                    InputValue.RawText(value),
                    ExecutionInputOrigin.USER_ENTERED,
                ),
            ),
        )
        check(environmentResult is InputEnvironmentResult.Success)
        val requestResult = MyBatisPreparationRequest.create(
            PreparationSource.JavaAnnotation(capture),
            contract,
            environmentResult.environment,
        )
        check(requestResult is PreparationRequestResult.Ready)
        return requestResult.request
    }
}
