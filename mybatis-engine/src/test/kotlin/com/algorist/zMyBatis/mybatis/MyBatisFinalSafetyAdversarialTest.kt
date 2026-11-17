package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.InputEnvironmentResult
import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import com.algorist.zMyBatis.core.preparation.PreparationRequestResult
import com.algorist.zMyBatis.core.preparation.PreparationResult
import com.algorist.zMyBatis.core.preparation.PreparationSource
import com.algorist.zMyBatis.core.source.CapturedStatement
import com.algorist.zMyBatis.core.source.JavaAnnotationStatementCapture
import com.algorist.zMyBatis.core.source.JavaStatementId
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

class MyBatisFinalSafetyAdversarialTest {
    @Test
    fun deeplyNestedDynamicTagsAreRejectedBeforeStockMyBatisEvaluation() {
        val depth = 129
        val script = buildString {
            append("<script>")
            repeat(depth) { append("<if test=\"flag\">") }
            append("select 1")
            repeat(depth) { append("</if>") }
            append("</script>")
        }

        val failure = DynamicOgnlSafetyAdmission.failureForScript(script)
        requireNotNull(failure)
        assertEquals(PreparationFailureKind.UNSUPPORTED_SEMANTIC, failure.kind)
        assertEquals("mybatis-ognl-runtime-capability-unsupported", failure.code)
        assertEquals("DynamicScript[depth]", failure.bindingProperty)
    }

    @Test
    fun deeplyNestedOgnlIsRejectedByAdmissionBudget() {
        val expression = "!".repeat(300) + "flag"

        val result = IsolatedOgnlAstAdmission.inspect(
            expressions = listOf(expression),
            allowedRootProperties = setOf("flag"),
        )

        assertTrue(result is IsolatedOgnlAstAdmission.Result.Unsupported)
        result as IsolatedOgnlAstAdmission.Result.Unsupported
        assertTrue(
            result.nodeType == "OGNL[ast-depth]" ||
                result.nodeType == "OGNL[parser-depth]",
        )
    }

    @Test
    fun overlongOgnlIsRejectedBeforeParserInvocation() {
        val result = IsolatedOgnlAstAdmission.inspect(
            expressions = listOf("x".repeat(65_537)),
            allowedRootProperties = setOf("x"),
        )

        assertEquals(
            IsolatedOgnlAstAdmission.Result.Unsupported("OGNL[expression-length]"),
            result,
        )
    }

    @Test
    fun nonFiniteBindResultBecomesTypedUnsupportedValueFailure() {
        val expression = "1.0 / 0.0"
        val result = MyBatisPreparationEngine.prepare(
            request(
                "<script><bind name=\"ratio\" value=\"$expression\"/>select #{ratio}</script>",
            ),
        )

        assertTrue(result is PreparationResult.Failed)
        result as PreparationResult.Failed
        assertEquals(PreparationFailureKind.UNSUPPORTED_BINDING_VALUE, result.failure.kind)
        assertEquals("mybatis-binding-value-unsupported", result.failure.code)
        assertEquals("ratio", result.failure.bindingProperty)
    }

    private fun request(script: String): MyBatisPreparationRequest {
        val fileId = SourceFileId("fixture/FinalSafetyMapper.java")
        val revision = SourceRevision("final-safety-revision")
        val snapshot = SourceSnapshot(fileId, revision, "authoritative-final-safety-source")
        val statementId = JavaStatementId(
            fileId,
            "fixture.FinalSafetyMapper",
            MethodSignature("query", emptyList()),
        )
        val capture = JavaAnnotationStatementCapture(
            StatementSourceGraph(
                CapturedStatement(statementId, StatementKind.SELECT, SourceRange(0, snapshot.content.length)),
                listOf(snapshot),
                emptyList(),
            ),
            listOf(script),
            emptyList(),
        )
        val source = PreparationSource.JavaAnnotation(capture)
        val contract = MyBatisInvocationContractFactory.build(source)
        check(!contract.isPreparationBlocked)
        val environment = InputEnvironment.validate(contract, emptyList())
        check(environment is InputEnvironmentResult.Success)
        val request = MyBatisPreparationRequest.create(source, contract, environment.environment)
        check(request is PreparationRequestResult.Ready)
        return request.request
    }
}
