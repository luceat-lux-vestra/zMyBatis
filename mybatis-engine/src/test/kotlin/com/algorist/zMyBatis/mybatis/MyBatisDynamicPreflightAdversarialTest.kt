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
                expectedType = ExpectedInputType(
                    shape = InputShape.RAW_TEXT,
                    scalarType = InputScalarType.STRING,
                    javaTypeIdentity = JavaTypeIdentity("java.lang.String"),
                    nullability = InputNullability.NON_NULL,
                ),
                value = InputValue.RawText("users"),
                kind = InputKind.RAW_INTERPOLATION,
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
                expectedType = ExpectedInputType(
                    shape = InputShape.SCALAR,
                    scalarType = InputScalarType.INTEGER,
                    javaTypeIdentity = JavaTypeIdentity("java.lang.Long"),
                    nullability = InputNullability.NON_NULL,
                ),
                value = InputValue.IntegerValue(BigInteger.ONE),
                kind = InputKind.BOUND,
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
        val evidence = SourceEvidence(fileId, revision, sourceRange)
        val requirementId = InputRequirementId("encoded-param:0")
        val provenance = InputProvenance(
            listOf(
                InputEvidence.MapperMethodParameter(0, parameter.alias, typeIdentity, evidence),
                InputEvidence.ExplicitParamAlias(0, parameter.alias, evidence),
                if (parameter.kind == InputKind.RAW_INTERPOLATION) {
                    InputEvidence.Placeholder(InputKind.RAW_INTERPOLATION, parameter.alias, evidence)
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

    private data class Parameter(
        val javaType: String,
        val alias: String,
        val expectedType: ExpectedInputType,
        val value: InputValue,
        val kind: InputKind,
    )
}
