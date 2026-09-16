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

class MyBatisFinalSafetyAdversarialTest {
    @Test
    fun dynamicScriptRejectsRawContractEvenWithoutRawSourceToken() {
        val result = MyBatisPreparationEngine.prepare(
            rawContractRequest(
                script = "<script>select 1</script>",
                alias = "fragment",
                value = "users",
            ),
        )

        assertFailure(
            result,
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "java-annotation-dynamic-raw-interpolation-unsupported",
            null,
        )
    }

    @Test
    fun deeplyNestedDynamicTagsAreRejectedBeforeStockMyBatisEvaluation() {
        val depth = 257
        val script = buildString {
            append("<script>")
            repeat(depth) { append("<if test=\"flag\">") }
            append("select 1")
            repeat(depth) { append("</if>") }
            append("</script>")
        }

        val result = DynamicOgnlAdmission.inspect(
            script = script,
            callerRootProperties = setOf("flag"),
            internalBindings = emptyList(),
        )

        assertEquals(
            DynamicOgnlAdmission.Result.Unsupported("DynamicScript[depth]"),
            result,
        )
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
            bindOnlyRequest(
                script = "<script><bind name=\"ratio\" value=\"$expression\"/>select #{ratio}</script>",
                bindName = "ratio",
                bindExpression = expression,
            ),
        )

        assertFailure(
            result,
            PreparationFailureKind.UNSUPPORTED_BINDING_VALUE,
            "mybatis-binding-value-unsupported",
            "ratio",
        )
    }

    private fun rawContractRequest(
        script: String,
        alias: String,
        value: String,
    ): MyBatisPreparationRequest {
        val source = sourceFixture("FinalRawMapper.java", "final-raw-revision")
        val type = JavaTypeIdentity("java.lang.String")
        val statementId = JavaStatementId(
            source.fileId,
            "fixture.FinalRawMapper",
            MethodSignature("query", listOf(type)),
        )
        val graph = StatementSourceGraph(
            CapturedStatement(statementId, StatementKind.SELECT, source.range),
            listOf(source.snapshot),
            emptyList(),
        )
        val capture = JavaAnnotationStatementCapture(
            graph,
            listOf(script),
            listOf(JavaMethodParameterMetadata(0, alias, type, alias)),
        )
        val evidence = SourceEvidence(source.fileId, source.revision, source.range)
        val requirementId = InputRequirementId("final-raw-param:0")
        val provenance = InputProvenance(
            listOf(
                InputEvidence.MapperMethodParameter(0, alias, type, evidence),
                InputEvidence.ExplicitParamAlias(0, alias, evidence),
                InputEvidence.Placeholder(InputKind.RAW_INTERPOLATION, alias, evidence),
            ),
        )
        val requirement = InputRequirement(
            id = requirementId,
            kind = InputKind.RAW_INTERPOLATION,
            expectedType = ExpectedInputType(
                shape = InputShape.RAW_TEXT,
                scalarType = InputScalarType.STRING,
                javaTypeIdentity = type,
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
            sourceRevisions = mapOf(source.fileId to source.revision),
        )
        val environment = InputEnvironment.validate(
            contract,
            listOf(
                ProvidedInput(
                    requirementId,
                    InputValue.RawText(value),
                    ExecutionInputOrigin.USER_ENTERED,
                ),
            ),
        )
        check(environment is InputEnvironmentResult.Success)
        return request(capture, contract, environment.environment)
    }

    private fun bindOnlyRequest(
        script: String,
        bindName: String,
        bindExpression: String,
    ): MyBatisPreparationRequest {
        val source = sourceFixture("FinalBindMapper.java", "final-bind-revision")
        val statementId = JavaStatementId(
            source.fileId,
            "fixture.FinalBindMapper",
            MethodSignature("query", emptyList()),
        )
        val graph = StatementSourceGraph(
            CapturedStatement(statementId, StatementKind.SELECT, source.range),
            listOf(source.snapshot),
            emptyList(),
        )
        val capture = JavaAnnotationStatementCapture(graph, listOf(script), emptyList())
        val evidence = SourceEvidence(source.fileId, source.revision, source.range)
        val contract = ParameterContract(
            statementId = statementId,
            requirements = emptyList(),
            aliases = emptyList(),
            internalBindings = listOf(
                InternalBinding(
                    name = bindName,
                    kind = InternalBindingKind.BIND,
                    provenance = InputProvenance(
                        listOf(InputEvidence.BindLocal(bindName, bindExpression, evidence)),
                    ),
                ),
            ),
            blockingProblems = emptyList(),
            sourceRevisions = mapOf(source.fileId to source.revision),
        )
        val environment = InputEnvironment.validate(contract, emptyList())
        check(environment is InputEnvironmentResult.Success)
        return request(capture, contract, environment.environment)
    }

    private fun request(
        capture: JavaAnnotationStatementCapture,
        contract: ParameterContract,
        environment: InputEnvironment,
    ): MyBatisPreparationRequest {
        val result = MyBatisPreparationRequest.create(
            PreparationSource.JavaAnnotation(capture),
            contract,
            environment,
        )
        check(result is PreparationRequestResult.Ready) { "test request was not ready: $result" }
        return result.request
    }

    private fun sourceFixture(fileName: String, revisionValue: String): SourceFixture {
        val fileId = SourceFileId("fixture/$fileName")
        val revision = SourceRevision(revisionValue)
        val snapshot = SourceSnapshot(fileId, revision, "authoritative-final-safety-source")
        return SourceFixture(
            fileId = fileId,
            revision = revision,
            snapshot = snapshot,
            range = SourceRange(0, snapshot.content.length),
        )
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

    private data class SourceFixture(
        val fileId: SourceFileId,
        val revision: SourceRevision,
        val snapshot: SourceSnapshot,
        val range: SourceRange,
    )
}
