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
import java.lang.reflect.Proxy
import java.math.BigInteger
import org.apache.ibatis.session.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MyBatisGlobalStateIsolationTest {
    @Test
    fun dynamicAndStaticRawPreparationDoNotUseOrMutateParentOgnlContextAccessor() = synchronized(ognlLock) {
        val myBatisLoader = Configuration::class.java.classLoader
        Class.forName(
            "org.apache.ibatis.scripting.xmltags.DynamicContext",
            true,
            myBatisLoader,
        )
        val contextMapClass = Class.forName(
            "org.apache.ibatis.scripting.xmltags.DynamicContext\$ContextMap",
            false,
            myBatisLoader,
        )
        val ognlRuntimeClass = Class.forName("org.apache.ibatis.ognl.OgnlRuntime", true, myBatisLoader)
        val propertyAccessorClass = Class.forName("org.apache.ibatis.ognl.PropertyAccessor", true, myBatisLoader)
        val getPropertyAccessor = ognlRuntimeClass.getMethod("getPropertyAccessor", Class::class.java)
        val setPropertyAccessor = ognlRuntimeClass.getMethod(
            "setPropertyAccessor",
            Class::class.java,
            propertyAccessorClass,
        )

        val original = getPropertyAccessor.invoke(null, contextMapClass)
        val poison = Proxy.newProxyInstance(
            myBatisLoader,
            arrayOf(propertyAccessorClass),
        ) { _, method, _ ->
            throw AssertionError("parent OGNL ContextMap accessor was invoked: ${method.name}")
        }

        setPropertyAccessor.invoke(null, contextMapClass, poison)
        try {
            val dynamic = prepare(
                fixture(
                    script = "<script><if test=\"id != null\">select #{id}</if></script>",
                    parameter = Parameter.boundLong("id", 7),
                ),
            )
            assertTrue(dynamic is PreparationResult.Success)
            dynamic as PreparationResult.Success
            assertEquals("select ?", normalizeSql(dynamic.execution.sqlWithPlaceholders))
            assertEquals(InputValue.IntegerValue(BigInteger.valueOf(7)), dynamic.execution.orderedBindings.single().value)
            assertSame(poison, getPropertyAccessor.invoke(null, contextMapClass))

            val staticRaw = prepare(
                fixture(
                    script = "select * from ${'$'}{table}",
                    parameter = Parameter.rawString("table", "users"),
                ),
            )
            assertTrue(staticRaw is PreparationResult.Success)
            staticRaw as PreparationResult.Success
            assertEquals("select * from users", normalizeSql(staticRaw.execution.sqlWithPlaceholders))
            assertTrue(staticRaw.execution.orderedBindings.isEmpty())
            assertEquals(1, staticRaw.execution.rawInterpolations.size)
            assertSame(poison, getPropertyAccessor.invoke(null, contextMapClass))
        } finally {
            setPropertyAccessor.invoke(null, contextMapClass, original)
        }
    }

    @Test
    fun reservedBindContextNameFailsBeforeMyBatisEvaluation() {
        val fixture = fixture(
            script = "<script><bind name=\"_parameter\" value=\"id\"/>select 1</script>",
            parameter = Parameter.boundLong("id", 1),
            internalBindings = listOf(bind("_parameter", "id")),
        )

        val result = prepare(fixture)

        assertTrue(result is PreparationResult.Failed)
        result as PreparationResult.Failed
        assertEquals(PreparationFailureKind.UNSUPPORTED_SEMANTIC, result.failure.kind)
        assertEquals("mybatis-bind-reserved-context-name-unsupported", result.failure.code)
        assertEquals("_parameter", result.failure.bindingProperty)
    }

    private fun prepare(fixture: Fixture): PreparationResult {
        val requestResult = MyBatisPreparationRequest.create(
            PreparationSource.JavaAnnotation(fixture.capture),
            fixture.contract,
            fixture.environment,
        )
        check(requestResult is PreparationRequestResult.Ready) { "fixture request was not ready: $requestResult" }
        return MyBatisPreparationEngine.prepare(requestResult.request)
    }

    private fun fixture(
        script: String,
        parameter: Parameter,
        internalBindings: List<InternalSpec> = emptyList(),
    ): Fixture {
        val fileId = SourceFileId("fixture/IsolationMapper.java")
        val revision = SourceRevision("isolation-revision-1")
        val snapshot = SourceSnapshot(fileId, revision, "authoritative-isolation-source")
        val typeIdentity = JavaTypeIdentity(parameter.javaType)
        val statementId = JavaStatementId(
            fileId,
            "fixture.IsolationMapper",
            MethodSignature("query", listOf(typeIdentity)),
        )
        val range = SourceRange(0, snapshot.content.length)
        val graph = StatementSourceGraph(
            CapturedStatement(statementId, StatementKind.SELECT, range),
            listOf(snapshot),
            emptyList(),
        )
        val capture = JavaAnnotationStatementCapture(
            graph,
            listOf(script),
            listOf(JavaMethodParameterMetadata(0, parameter.alias, typeIdentity, parameter.alias)),
        )
        val source = SourceEvidence(fileId, revision, range)
        val requirementId = InputRequirementId("isolation-param:0")
        val provenance = InputProvenance(
            listOf(
                InputEvidence.MapperMethodParameter(0, parameter.alias, typeIdentity, source),
                InputEvidence.ExplicitParamAlias(0, parameter.alias, source),
                if (parameter.kind == InputKind.RAW_INTERPOLATION) {
                    InputEvidence.Placeholder(InputKind.RAW_INTERPOLATION, parameter.alias, source)
                } else {
                    InputEvidence.OgnlExpression(parameter.alias, source)
                },
            ),
        )
        val requirement = InputRequirement(
            id = requirementId,
            kind = parameter.kind,
            expectedType = parameter.expectedType,
            requiredness = InputRequiredness.REQUIRED,
            provenance = provenance,
        )
        val alias = InputAlias(
            name = parameter.alias,
            requirementId = requirementId,
            kind = InputAliasKind.EXPLICIT_PARAM,
            provenance = provenance,
        )
        val internals = internalBindings.map { spec ->
            InternalBinding(
                name = spec.name,
                kind = InternalBindingKind.BIND,
                provenance = InputProvenance(
                    listOf(InputEvidence.BindLocal(spec.name, spec.expression, source)),
                ),
            )
        }
        val contract = ParameterContract(
            statementId = statementId,
            requirements = listOf(requirement),
            aliases = listOf(alias),
            internalBindings = internals,
            blockingProblems = emptyList(),
            sourceRevisions = mapOf(fileId to revision),
        )
        val environmentResult = InputEnvironment.validate(
            contract,
            listOf(ProvidedInput(requirementId, parameter.value, ExecutionInputOrigin.USER_ENTERED)),
        )
        check(environmentResult is InputEnvironmentResult.Success) { "fixture environment invalid: $environmentResult" }
        return Fixture(capture, contract, environmentResult.environment)
    }

    private fun bind(name: String, expression: String) = InternalSpec(name, expression)

    private fun normalizeSql(sql: String): String = sql.trim().replace(Regex("\\s+"), " ")

    private data class Fixture(
        val capture: JavaAnnotationStatementCapture,
        val contract: ParameterContract,
        val environment: InputEnvironment,
    )

    private data class InternalSpec(
        val name: String,
        val expression: String,
    )

    private data class Parameter(
        val javaType: String,
        val alias: String,
        val expectedType: ExpectedInputType,
        val value: InputValue,
        val kind: InputKind,
    ) {
        companion object {
            fun boundLong(alias: String, value: Long) = Parameter(
                javaType = "java.lang.Long",
                alias = alias,
                expectedType = ExpectedInputType(
                    shape = InputShape.SCALAR,
                    scalarType = InputScalarType.INTEGER,
                    javaTypeIdentity = JavaTypeIdentity("java.lang.Long"),
                    nullability = InputNullability.NON_NULL,
                ),
                value = InputValue.IntegerValue(BigInteger.valueOf(value)),
                kind = InputKind.BOUND,
            )

            fun rawString(alias: String, value: String) = Parameter(
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
            )
        }
    }

    companion object {
        private val ognlLock = Any()
    }
}
