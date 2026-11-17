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
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MyBatisDynamicPreflightAdversarialTest {
    @Test
    fun numericCharacterReferenceCannotSynthesizeDynamicRawInterpolation() {
        val result = prepare(
            script = "<script>select * from &#36;{table}</script>",
            parameter = Parameter(
                javaType = "java.lang.String",
                alias = "table",
                value = InputValue.Text("users"),
            ),
        )

        assertNumericReferenceFailure(result)
    }

    @Test
    fun numericCharacterReferenceCannotSynthesizeTypeHandlerOptionAfterPreflight() {
        val result = prepare(
            script = "<script>select #{id,typeHandler&#61;fixture.DoesNotExist}</script>",
            parameter = Parameter(
                javaType = "java.lang.Long",
                alias = "id",
                value = InputValue.IntegerValue(BigInteger.ONE),
            ),
        )

        assertNumericReferenceFailure(result)
    }

    private fun assertNumericReferenceFailure(result: PreparationResult) {
        assertTrue(result is PreparationResult.Failed)
        result as PreparationResult.Failed
        assertEquals(PreparationFailureKind.UNSUPPORTED_SEMANTIC, result.failure.kind)
        assertEquals(
            "java-annotation-dynamic-numeric-character-reference-unsupported",
            result.failure.code,
        )
        assertEquals(null, result.failure.bindingProperty)
    }

    private fun prepare(script: String, parameter: Parameter): PreparationResult {
        val fileId = SourceFileId("fixture/EncodedDynamicMapper.java")
        val revision = SourceRevision("encoded-dynamic-revision-1")
        val snapshot = SourceSnapshot(fileId, revision, "authoritative-encoded-dynamic-source")
        val typeIdentity = JavaTypeIdentity(parameter.javaType)
        val statementId = JavaStatementId(
            fileId,
            "fixture.EncodedDynamicMapper",
            MethodSignature("query", listOf(typeIdentity)),
        )
        val sourceRange = SourceRange(0, snapshot.content.length)
        val graph = StatementSourceGraph(
            rootStatement = CapturedStatement(statementId, StatementKind.SELECT, sourceRange),
            sourceSnapshots = listOf(snapshot),
            dependencies = emptyList(),
        )
        val capture = JavaAnnotationStatementCapture(
            sourceGraph = graph,
            sqlSegments = listOf(script),
            parameters = listOf(
                JavaMethodParameterMetadata(
                    index = 0,
                    sourceName = parameter.alias,
                    typeIdentity = typeIdentity,
                    myBatisParamAlias = parameter.alias,
                ),
            ),
        )
        val source = PreparationSource.JavaAnnotation(capture)
        val contract = MyBatisInvocationContractFactory.build(source)
        check(!contract.isPreparationBlocked) {
            "adversarial fixture contract must be usable: ${contract.blockingProblems.map { it.code }}"
        }
        val requirement = contract.requirements.single()
        val environmentResult = InputEnvironment.validate(
            contract,
            listOf(ProvidedInput(requirement.id, parameter.value, ExecutionInputOrigin.USER_ENTERED)),
        )
        check(environmentResult is InputEnvironmentResult.Success) {
            "adversarial fixture environment must be valid: $environmentResult"
        }
        val requestResult = MyBatisPreparationRequest.create(
            source,
            contract,
            environmentResult.environment,
        )
        check(requestResult is PreparationRequestResult.Ready) {
            "adversarial fixture request must be ready: $requestResult"
        }
        return MyBatisPreparationEngine.prepare(requestResult.request)
    }

    private data class Parameter(
        val javaType: String,
        val alias: String,
        val value: InputValue,
    )
}
