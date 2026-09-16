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

class MyBatisBoundaryRegressionTest {
    @Test
    fun staticRawKeepsEscapedSourceBoundTokenLiteral() {
        val result = MyBatisPreparationEngine.prepare(
            request(
                script = "select '\\#{literal}' from ${'$'}{table}",
                alias = "table",
                expectedType = rawStringType(),
                value = InputValue.RawText("users"),
                kind = InputKind.RAW_INTERPOLATION,
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
                script = "select ${'$'}{fragment}",
                alias = "fragment",
                expectedType = rawStringType(),
                value = InputValue.RawText("\\#{literal}"),
                kind = InputKind.RAW_INTERPOLATION,
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
                expectedType = ExpectedInputType(
                    shape = InputShape.LIST,
                    javaTypeIdentity = JavaTypeIdentity("java.util.List<java.lang.Object>"),
                    nullability = InputNullability.NON_NULL,
                ),
                value = nested,
                kind = InputKind.BOUND,
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
        expectedType: ExpectedInputType,
        value: InputValue,
        kind: InputKind,
    ): MyBatisPreparationRequest {
        val fileId = SourceFileId("fixture/BoundaryRegressionMapper.java")
        val revision = SourceRevision("boundary-regression-revision-1")
        val snapshot = SourceSnapshot(fileId, revision, "authoritative-boundary-regression-source")
        val typeIdentity = expectedType.javaTypeIdentity ?: error("test type identity required")
        val statementId = JavaStatementId(
            fileId,
            "fixture.BoundaryRegressionMapper",
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
        val requirementId = InputRequirementId("boundary-regression-param:0")
        val provenance = InputProvenance(
            listOf(
                InputEvidence.MapperMethodParameter(0, alias, typeIdentity, source),
                InputEvidence.ExplicitParamAlias(0, alias, source),
                if (kind == InputKind.RAW_INTERPOLATION) {
                    InputEvidence.Placeholder(InputKind.RAW_INTERPOLATION, alias, source)
                } else {
                    InputEvidence.Placeholder(InputKind.BOUND, alias, source)
                },
            ),
        )
        val requirement = InputRequirement(
            id = requirementId,
            kind = kind,
            expectedType = expectedType,
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
        val environment = InputEnvironment.validate(
            contract,
            listOf(ProvidedInput(requirementId, value, ExecutionInputOrigin.USER_ENTERED)),
        )
        check(environment is InputEnvironmentResult.Success) { "test environment invalid: $environment" }
        val result = MyBatisPreparationRequest.create(
            PreparationSource.JavaAnnotation(capture),
            contract,
            environment.environment,
        )
        check(result is PreparationRequestResult.Ready) { "test request not ready: $result" }
        return result.request
    }

    private fun rawStringType() = ExpectedInputType(
        shape = InputShape.RAW_TEXT,
        scalarType = InputScalarType.STRING,
        javaTypeIdentity = JavaTypeIdentity("java.lang.String"),
        nullability = InputNullability.NON_NULL,
    )
}
