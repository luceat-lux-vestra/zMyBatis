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

class MyBatisBoundaryRegressionTest {
    @Test
    fun staticRawKeepsEscapedSourceBoundTokenLiteral() {
        val result = MyBatisPreparationEngine.prepare(
            request(
                script = "select '\\#{literal}' from " + "$" + "{table}",
                alias = "table",
                javaType = "java.lang.String",
                value = InputValue.Text("users"),
            ),
        )

        assertTrue(result is PreparationResult.Success)
        result as PreparationResult.Success
        assertEquals("select '#{literal}' from users", result.execution.sqlWithPlaceholders)
        assertTrue(result.execution.orderedBindings.isEmpty())
        assertEquals(1, result.execution.rawInterpolations.size)
    }

    @Test
    fun staticRawValueMayContainEscapedBoundTokenWithoutCreatingBinding() {
        val result = MyBatisPreparationEngine.prepare(
            request(
                script = "select " + "$" + "{fragment}",
                alias = "fragment",
                javaType = "java.lang.String",
                value = InputValue.Text("\\#{literal}"),
            ),
        )

        assertTrue(result is PreparationResult.Success)
        result as PreparationResult.Success
        assertEquals("select #{literal}", result.execution.sqlWithPlaceholders)
        assertTrue(result.execution.orderedBindings.isEmpty())
        assertEquals(1, result.execution.rawInterpolations.size)
    }

    @Test
    fun manuallyConstructedDeepStructuredInputFailsBeforeRecursiveConversion() {
        var nested: InputValue = InputValue.Text("leaf")
        repeat(129) {
            nested = InputValue.ListValue(listOf(nested))
        }

        val result = MyBatisPreparationEngine.prepare(
            request(
                script = "select #{value}",
                alias = "value",
                javaType = "java.util.List<java.lang.Object>",
                value = nested,
            ),
        )

        assertTrue(result is PreparationResult.Failed)
        result as PreparationResult.Failed
        assertEquals(PreparationFailureKind.UNSUPPORTED_BINDING_VALUE, result.failure.kind)
        assertEquals("mybatis-binding-value-too-complex", result.failure.code)
    }

    private fun request(
        script: String,
        alias: String,
        javaType: String,
        value: InputValue,
    ): MyBatisPreparationRequest {
        val fileId = SourceFileId("fixture/BoundaryRegressionMapper.java")
        val revision = SourceRevision("boundary-regression-revision-1")
        val snapshot = SourceSnapshot(fileId, revision, "authoritative-boundary-regression-source")
        val typeIdentity = JavaTypeIdentity(javaType)
        val statementId = JavaStatementId(
            fileId,
            "fixture.BoundaryRegressionMapper",
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
            listOf(ProvidedInput(requirement.id, value, ExecutionInputOrigin.USER_ENTERED)),
        )
        check(environment is InputEnvironmentResult.Success)
        val request = MyBatisPreparationRequest.create(source, contract, environment.environment)
        check(request is PreparationRequestResult.Ready)
        return request.request
    }
}
