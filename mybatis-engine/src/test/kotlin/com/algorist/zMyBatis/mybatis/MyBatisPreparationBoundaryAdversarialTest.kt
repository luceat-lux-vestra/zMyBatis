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
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val STATIC_RAW_OGNL_PROBE_PROPERTY = "zmybatis.test.static-raw-ognl-probe"

class MyBatisPreparationBoundaryAdversarialTest {
    @Test
    fun runtimeClassOptionSplitAcrossAnnotationSegmentsIsStillRejectedBeforeMyBatis() {
        val result = prepare(
            segments = listOf(
                "<script>select #{id,",
                "typeHandler=fixture.DoesNotExist}</script>",
            ),
            parameter = longParameter("id", BigInteger.ONE),
        )

        assertFailure(
            result,
            PreparationFailureKind.UNSUPPORTED_TYPE_HANDLER,
            "mybatis-custom-type-handler-unsupported",
        )
    }

    @Test
    fun staticRawValueCannotSynthesizeANewBoundToken() {
        val result = prepare(
            segments = listOf("select ${'$'}{fragment}"),
            parameter = rawParameter(
                alias = "fragment",
                value = "#{id,typeHandler=fixture.DoesNotExist}",
            ),
        )

        assertFailure(
            result,
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "raw-interpolation-mybatis-token-topology-unsupported",
        )
    }

    @Test
    fun emptyStaticRawValueCannotDeleteTheBoundaryBetweenHashAndBrace() {
        val result = prepare(
            segments = listOf("select #${'$'}{gap}{id,typeHandler=fixture.DoesNotExist}"),
            parameter = rawParameter(alias = "gap", value = ""),
        )

        assertFailure(
            result,
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "raw-interpolation-mybatis-token-topology-unsupported",
        )
    }

    @Test
    fun rawBackslashCannotEscapeAnAuthoritativeSourceBoundToken() {
        val result = prepare(
            segments = listOf("select ${'$'}{prefix}#{literal}"),
            parameter = rawParameter(alias = "prefix", value = "\\"),
        )

        assertFailure(
            result,
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "raw-interpolation-mybatis-token-topology-unsupported",
        )
    }

    @Test
    fun safeEmptyStaticRawValueRemainsSupportedWhenBoundTopologyIsUnchanged() {
        val result = prepare(
            segments = listOf("select '${'$'}{fragment}'"),
            parameter = rawParameter(alias = "fragment", value = ""),
        )

        assertTrue(result is PreparationResult.Success)
        result as PreparationResult.Success
        assertEquals("select ''", result.execution.sqlWithPlaceholders.trim())
        assertTrue(result.execution.orderedBindings.isEmpty())
        assertEquals(1, result.execution.rawInterpolations.size)
    }

    @Test
    fun harmlessRawMetasyntaxCharactersRemainSupportedWhenBoundTopologyIsUnchanged() {
        val value = "archive#2026{old}"
        val result = prepare(
            segments = listOf("select '${'$'}{fragment}'"),
            parameter = rawParameter(alias = "fragment", value = value),
        )

        assertTrue(result is PreparationResult.Success)
        result as PreparationResult.Success
        assertEquals("select '$value'", result.execution.sqlWithPlaceholders.trim())
        assertTrue(result.execution.orderedBindings.isEmpty())
        assertEquals(1, result.execution.rawInterpolations.size)
    }

    @Test
    fun staticRawInterpolationCannotMutateAnExistingBoundToken() {
        val result = prepare(
            segments = listOf("select #{${'$'}{option}}"),
            parameter = rawParameter(
                alias = "option",
                value = "id,typeHandler=fixture.DoesNotExist",
            ),
        )

        assertFailure(
            result,
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "raw-interpolation-bound-context-unsupported",
        )
    }

    @Test
    fun staticRawSourceMustMatchAuthoritativePlaceholderEvidence() {
        val result = prepare(
            segments = listOf("select ${'$'}{fragment}"),
            parameter = rawParameter(
                alias = "fragment",
                value = "users",
                evidenceExpression = "different",
            ),
        )

        assertFailure(
            result,
            PreparationFailureKind.PREPARATION_INVARIANT,
            "raw-interpolation-source-authority-mismatch",
        )
    }

    @Test
    fun staticRawOgnlClassLoadingSyntaxIsRejectedBeforeClassInitialization() {
        System.clearProperty(STATIC_RAW_OGNL_PROBE_PROPERTY)
        val expression = "@com.algorist.zMyBatis.mybatis.StaticRawOgnlLoadProbe@touch()"
        val result = prepare(
            segments = listOf("select ${'$'}{$expression}"),
            parameter = rawParameter(
                alias = "fragment",
                value = "users",
                evidenceExpression = expression,
            ),
        )

        assertFailure(
            result,
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "raw-interpolation-expression-unsupported",
        )
        assertNull(System.getProperty(STATIC_RAW_OGNL_PROBE_PROPERTY))
    }

    private fun prepare(
        segments: List<String>,
        parameter: Parameter,
    ): PreparationResult {
        val fileId = SourceFileId("fixture/BoundaryAdversarialMapper.java")
        val revision = SourceRevision("boundary-adversarial-revision-1")
        val snapshot = SourceSnapshot(fileId, revision, "authoritative-boundary-adversarial-source")
        val typeIdentity = JavaTypeIdentity(parameter.javaType)
        val statementId = JavaStatementId(
            fileId,
            "fixture.BoundaryAdversarialMapper",
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
            sqlSegments = segments,
            parameters = listOf(
                JavaMethodParameterMetadata(
                    index = 0,
                    sourceName = parameter.alias,
                    typeIdentity = typeIdentity,
                    myBatisParamAlias = parameter.alias,
                ),
            ),
        )
        val evidence = SourceEvidence(fileId, revision, sourceRange)
        val requirementId = InputRequirementId("boundary-param:0")
        val provenance = InputProvenance(
            listOf(
                InputEvidence.MapperMethodParameter(0, parameter.alias, typeIdentity, evidence),
                InputEvidence.ExplicitParamAlias(0, parameter.alias, evidence),
                if (parameter.kind == InputKind.RAW_INTERPOLATION) {
                    InputEvidence.Placeholder(
                        InputKind.RAW_INTERPOLATION,
                        parameter.evidenceExpression ?: parameter.alias,
                        evidence,
                    )
                } else {
                    InputEvidence.Placeholder(InputKind.BOUND, parameter.alias, evidence)
                },
            ),
        )
        val contract = ParameterContract(
            statementId = statementId,
            requirements = listOf(
                InputRequirement(
                    id = requirementId,
                    kind = parameter.kind,
                    expectedType = parameter.expectedType,
                    requiredness = InputRequiredness.REQUIRED,
                    provenance = provenance,
                ),
            ),
            aliases = listOf(
                InputAlias(
                    name = parameter.alias,
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
            listOf(ProvidedInput(requirementId, parameter.value, ExecutionInputOrigin.USER_ENTERED)),
        )
        check(environmentResult is InputEnvironmentResult.Success) {
            "adversarial fixture environment must be valid: $environmentResult"
        }
        val requestResult = MyBatisPreparationRequest.create(
            PreparationSource.JavaAnnotation(capture),
            contract,
            environmentResult.environment,
        )
        check(requestResult is PreparationRequestResult.Ready) {
            "adversarial fixture request must be ready: $requestResult"
        }
        return MyBatisPreparationEngine.prepare(requestResult.request)
    }

    private fun assertFailure(
        result: PreparationResult,
        kind: PreparationFailureKind,
        code: String,
    ) {
        assertTrue(result is PreparationResult.Failed)
        result as PreparationResult.Failed
        assertEquals(kind, result.failure.kind)
        assertEquals(code, result.failure.code)
        assertEquals(null, result.failure.bindingProperty)
    }

    private fun rawParameter(
        alias: String,
        value: String,
        evidenceExpression: String? = null,
    ) = Parameter(
        javaType = "java.lang.String",
        alias = alias,
        expectedType = ExpectedInputType(
            shape = InputShape.RAW_TEXT,
            scalarType = InputScalarType.STRING,
            javaTypeIdentity = JavaTypeIdentity("java.lang.String"),
            nullability = InputNullability.NON_NULL,
        ),
        value = InputValue.RawText(value),
        kind = InputKind.RAW_INTERPOLATION,
        evidenceExpression = evidenceExpression,
    )

    private fun longParameter(alias: String, value: BigInteger) = Parameter(
        javaType = "java.lang.Long",
        alias = alias,
        expectedType = ExpectedInputType(
            shape = InputShape.SCALAR,
            scalarType = InputScalarType.INTEGER,
            javaTypeIdentity = JavaTypeIdentity("java.lang.Long"),
            nullability = InputNullability.NON_NULL,
        ),
        value = InputValue.IntegerValue(value),
        kind = InputKind.BOUND,
    )

    private data class Parameter(
        val javaType: String,
        val alias: String,
        val expectedType: ExpectedInputType,
        val value: InputValue,
        val kind: InputKind,
        val evidenceExpression: String? = null,
    )
}

class StaticRawOgnlLoadProbe {
    companion object {
        init {
            System.setProperty(STATIC_RAW_OGNL_PROBE_PROPERTY, "initialized")
        }

        @JvmStatic
        fun touch(): String {
            System.setProperty(STATIC_RAW_OGNL_PROBE_PROPERTY, "invoked")
            return "touched"
        }
    }
}
