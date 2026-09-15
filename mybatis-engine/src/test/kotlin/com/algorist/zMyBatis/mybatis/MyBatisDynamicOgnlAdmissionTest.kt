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
            internalBindings = listOf(InternalSpec("probe", expression)),
        )

        assertFailure(
            result,
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "java-annotation-dynamic-ognl-node-unsupported",
            null,
        )
        assertNull(System.getProperty(OGNL_LOAD_PROBE_PROPERTY))
    }

    @Test
    fun instanceMethodInvocationIsRejectedBeforeMyBatisEvaluation() {
        val result = prepare(
            script = "<script><bind name=\"upper\" value=\"name.toUpperCase()\"/>select 1</script>",
            parameter = Parameter("name", InputValue.Text("Ada")),
            internalBindings = listOf(InternalSpec("upper", "name.toUpperCase()")),
        )

        assertFailure(
            result,
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "java-annotation-dynamic-ognl-node-unsupported",
            null,
        )
    }

    @Test
    fun constructorExpressionIsRejectedBeforeMyBatisEvaluation() {
        val expression = "new java.lang.String('x')"
        val result = prepare(
            script = "<script><bind name=\"value\" value=\"$expression\"/>select 1</script>",
            internalBindings = listOf(InternalSpec("value", expression)),
        )

        assertFailure(
            result,
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "java-annotation-dynamic-ognl-node-unsupported",
            null,
        )
    }

    @Test
    fun implicitMyBatisContextPropertyIsNotCallerAuthority() {
        val result = prepare(
            script = "<script><if test=\"_parameter != null\">select 1</if></script>",
        )

        assertFailure(
            result,
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "java-annotation-dynamic-ognl-property-unproven",
            "_parameter",
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
            "java-annotation-dynamic-ognl-node-unsupported",
            null,
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
            "java-annotation-dynamic-ognl-parse-failure",
            null,
        )
    }

    private fun prepare(
        script: String,
        parameter: Parameter? = null,
        internalBindings: List<InternalSpec> = emptyList(),
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
        val source = SourceEvidence(fileId, revision, SourceRange(0, snapshot.content.length))
        val requirements = parameters.mapIndexed { index, current ->
            val id = InputRequirementId("dynamic-ognl-param:$index")
            InputRequirement(
                id = id,
                kind = InputKind.BOUND,
                expectedType = ExpectedInputType(
                    shape = InputShape.SCALAR,
                    scalarType = InputScalarType.STRING,
                    javaTypeIdentity = JavaTypeIdentity("java.lang.String"),
                    nullability = InputNullability.NON_NULL,
                ),
                requiredness = InputRequiredness.REQUIRED,
                provenance = InputProvenance(
                    listOf(
                        InputEvidence.MapperMethodParameter(index, current.alias, types[index], source),
                        InputEvidence.ExplicitParamAlias(index, current.alias, source),
                        InputEvidence.OgnlExpression(current.alias, source),
                    ),
                ),
            )
        }
        val aliases = parameters.mapIndexed { index, current ->
            InputAlias(
                name = current.alias,
                requirementId = requirements[index].id,
                kind = InputAliasKind.EXPLICIT_PARAM,
                provenance = requirements[index].provenance,
            )
        }
        val internal = internalBindings.map { spec ->
            InternalBinding(
                name = spec.name,
                kind = InternalBindingKind.BIND,
                provenance = InputProvenance(listOf(InputEvidence.BindLocal(spec.name, spec.expression, source))),
            )
        }
        val contract = ParameterContract(
            statementId = statementId,
            requirements = requirements,
            aliases = aliases,
            internalBindings = internal,
            blockingProblems = emptyList(),
            sourceRevisions = mapOf(fileId to revision),
        )
        val environmentResult = InputEnvironment.validate(
            contract,
            requirements.mapIndexed { index, requirement ->
                ProvidedInput(requirement.id, parameters[index].value, ExecutionInputOrigin.USER_ENTERED)
            },
        )
        check(environmentResult is InputEnvironmentResult.Success) { "fixture environment invalid: $environmentResult" }
        val requestResult = MyBatisPreparationRequest.create(
            PreparationSource.JavaAnnotation(capture),
            contract,
            environmentResult.environment,
        )
        check(requestResult is PreparationRequestResult.Ready) { "fixture request was not ready: $requestResult" }
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

    private data class InternalSpec(
        val name: String,
        val expression: String,
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
