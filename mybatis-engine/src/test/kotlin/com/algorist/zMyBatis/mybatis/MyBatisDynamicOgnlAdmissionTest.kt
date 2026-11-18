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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val OGNL_LOAD_PROBE_PROPERTY = "zmybatis.test.dynamic-ognl-load-probe"

class MyBatisDynamicOgnlAdmissionTest {
    @Test
    fun staticMethodClassLoadingIsRejectedBeforeClassInitialization() {
        System.clearProperty(OGNL_LOAD_PROBE_PROPERTY)
        val expression = "@com.algorist.zMyBatis.mybatis.DynamicOgnlLoadProbe@touch()"
        val result = prepare(
            script = "<script><bind name=\"probe\" value=\"$expression\"/>select 1</script>",
        )

        assertFailure(
            result,
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "mybatis-ognl-runtime-capability-unsupported",
            "ASTStaticMethod",
        )
        assertNull(System.getProperty(OGNL_LOAD_PROBE_PROPERTY))
    }

    @Test
    fun instanceMethodInvocationIsRejectedBeforeMyBatisEvaluation() {
        val result = prepare(
            script = "<script><bind name=\"upper\" value=\"name.toUpperCase()\"/>select 1</script>",
            parameter = Parameter("name", InputValue.Text("Ada")),
        )

        assertFailure(
            result,
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "mybatis-ognl-runtime-capability-unsupported",
            "ASTMethod",
        )
    }

    @Test
    fun constructorExpressionIsRejectedBeforeMyBatisEvaluation() {
        val expression = "new java.lang.String('x')"
        val result = prepare(
            script = "<script><bind name=\"value\" value=\"$expression\"/>select 1</script>",
        )

        assertFailure(
            result,
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "mybatis-ognl-runtime-capability-unsupported",
            "ASTCtor",
        )
    }

    @Test
    fun implicitMyBatisContextPropertyRemainsStockOwned() {
        val result = prepare(
            script = "<script><if test=\"_parameter != null\">select 1</if></script>",
            parameter = Parameter("name", InputValue.Text("Ada")),
        )

        assertTrue(result is PreparationResult.Success)
        assertEquals(
            "select 1",
            (result as PreparationResult.Success).execution.sqlWithPlaceholders.trim(),
        )
    }

    @Test
    fun classPropertyTraversalIsRejected() {
        val result = prepare(
            script = "<script><if test=\"name.class != null\">select 1</if></script>",
            parameter = Parameter("name", InputValue.Text("Ada")),
        )

        assertFailure(
            result,
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "mybatis-ognl-runtime-capability-unsupported",
            "ASTProperty[class]",
        )
    }

    @Test
    fun malformedOgnlIsTypedBeforeEvaluation() {
        val result = prepare(
            script = "<script><if test=\"name !=\">select 1</if></script>",
            parameter = Parameter("name", InputValue.Text("Ada")),
        )

        assertFailure(
            result,
            PreparationFailureKind.OGNL,
            "mybatis-ognl-expression-malformed",
            null,
        )
    }

    private fun prepare(
        script: String,
        parameter: Parameter? = null,
    ): PreparationResult {
        val fileId = SourceFileId("fixture/DynamicOgnlMapper.java")
        val revision = SourceRevision("revision-dynamic-ognl-1")
        val snapshot = SourceSnapshot(fileId, revision, "authoritative-dynamic-ognl-java-source")
        val parameters = listOfNotNull(parameter)
        val types = parameters.map { JavaTypeIdentity("java.lang.String") }
        val statementId = JavaStatementId(
            fileId,
            "fixture.DynamicOgnlMapper",
            MethodSignature("query", types),
        )
        val graph = StatementSourceGraph(
            CapturedStatement(statementId, StatementKind.SELECT, SourceRange(0, snapshot.content.length)),
            listOf(snapshot),
            emptyList(),
        )
        val capture = JavaAnnotationStatementCapture(
            graph,
            listOf(script),
            parameters.mapIndexed { index, current ->
                JavaMethodParameterMetadata(index, current.alias, types[index], current.alias)
            },
        )
        val source = PreparationSource.JavaAnnotation(capture)
        val contract = MyBatisInvocationContractFactory.build(source)
        check(!contract.isPreparationBlocked) {
            "fixture contract was blocked: ${contract.blockingProblems.map { it.code }}"
        }
        val environmentResult = InputEnvironment.validate(
            contract,
            contract.requirements.map { requirement ->
                ProvidedInput(
                    requirement.id,
                    checkNotNull(parameter).value,
                    ExecutionInputOrigin.USER_ENTERED,
                )
            },
        )
        check(environmentResult is InputEnvironmentResult.Success) {
            "fixture environment invalid: $environmentResult"
        }
        val requestResult = MyBatisPreparationRequest.create(
            source,
            contract,
            environmentResult.environment,
        )
        check(requestResult is PreparationRequestResult.Ready) {
            "fixture request was not ready: $requestResult"
        }
        return MyBatisPreparationEngine.prepare(requestResult.request)
    }

    private fun assertFailure(
        result: PreparationResult,
        kind: PreparationFailureKind,
        code: String,
        property: String?,
    ) {
        assertTrue(result is PreparationResult.Failed)
        result as PreparationResult.Failed
        assertEquals(kind, result.failure.kind)
        assertEquals(code, result.failure.code)
        assertEquals(property, result.failure.bindingProperty)
    }

    private data class Parameter(
        val alias: String,
        val value: InputValue,
    )

}

class DynamicOgnlLoadProbe {
    companion object {
        init {
            System.setProperty(OGNL_LOAD_PROBE_PROPERTY, "initialized")
        }

        @JvmStatic
        fun touch(): String {
            System.setProperty(OGNL_LOAD_PROBE_PROPERTY, "invoked")
            return "touched"
        }
    }
}
