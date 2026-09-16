package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.ExecutionInputOrigin
import com.algorist.zMyBatis.core.input.ExpectedInputType
import com.algorist.zMyBatis.core.input.ForeachLocalRole
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
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MyBatisForeachTypeFidelityTest {
    @Test
    fun listLongCollectionPreservesElementRuntimeTypeForMyBatisMapping() {
        val result = prepare(
            declaredType = "java.util.List<java.lang.Long>",
            value = InputValue.ListValue(
                listOf(
                    InputValue.IntegerValue(BigInteger.ONE),
                    InputValue.IntegerValue(BigInteger.valueOf(2)),
                ),
            ),
        )

        assertTrue(result is PreparationResult.Success)
        result as PreparationResult.Success
        assertEquals(2, result.execution.orderedBindings.size)
        result.execution.orderedBindings.forEachIndexed { index, binding ->
            assertEquals(
                InputValue.IntegerValue(BigInteger.valueOf((index + 1).toLong())),
                binding.value,
            )
            assertEquals("java.lang.Long", binding.metadata.mappingJavaTypeIdentity)
            assertEquals("org.apache.ibatis.type.LongTypeHandler", binding.metadata.typeHandlerIdentity)
            assertEquals(InternalBindingKind.FOREACH_ITEM, binding.origin.let {
                (it as com.algorist.zMyBatis.core.preparation.PreparedBindingOrigin.MyBatisAdditional)
                    .internalBinding.kind
            })
        }
    }

    @Test
    fun rawListCollectionTypeFailsClosedInsteadOfUsingUntypedElements() {
        val result = prepare(
            declaredType = "java.util.List",
            value = InputValue.ListValue(listOf(InputValue.IntegerValue(BigInteger.ONE))),
        )

        assertUnsupportedCollection(result)
    }

    @Test
    fun wildcardCollectionTypeFailsClosedInsteadOfGuessingElementType() {
        val result = prepare(
            declaredType = "java.util.List<? extends java.lang.Long>",
            value = InputValue.ListValue(listOf(InputValue.IntegerValue(BigInteger.ONE))),
        )

        assertUnsupportedCollection(result)
    }

    @Test
    fun applicationPojoElementTypeFailsClosedInsteadOfBecomingAMap() {
        val result = prepare(
            declaredType = "java.util.List<fixture.Row>",
            value = InputValue.ListValue(
                listOf(
                    InputValue.ObjectValue(
                        linkedMapOf("id" to InputValue.IntegerValue(BigInteger.ONE)),
                    ),
                ),
            ),
        )

        assertUnsupportedCollection(result)
    }

    private fun assertUnsupportedCollection(result: PreparationResult) {
        assertTrue(result is PreparationResult.Failed)
        result as PreparationResult.Failed
        assertEquals(PreparationFailureKind.UNSUPPORTED_BINDING_VALUE, result.failure.kind)
        assertEquals("mybatis-binding-value-unsupported", result.failure.code)
        assertEquals("ids", result.failure.bindingProperty)
    }

    private fun prepare(declaredType: String, value: InputValue): PreparationResult {
        val fileId = SourceFileId("fixture/ForeachTypeFidelity.java")
        val revision = SourceRevision("foreach-type-fidelity-revision")
        val snapshot = SourceSnapshot(fileId, revision, "authoritative-foreach-type-source")
        val sourceRange = SourceRange(0, snapshot.content.length)
        val source = SourceEvidence(fileId, revision, sourceRange)
        val type = JavaTypeIdentity(declaredType)
        val statementId = JavaStatementId(
            fileId,
            "fixture.ForeachTypeFidelity",
            MethodSignature("query", listOf(type)),
        )
        val graph = StatementSourceGraph(
            rootStatement = CapturedStatement(statementId, StatementKind.SELECT, sourceRange),
            sourceSnapshots = listOf(snapshot),
            dependencies = emptyList(),
        )
        val script = """
            <script>
              select
              <foreach collection="ids" item="item" separator=",">#{item}</foreach>
            </script>
        """.trimIndent()
        val capture = JavaAnnotationStatementCapture(
            sourceGraph = graph,
            sqlSegments = listOf(script),
            parameters = listOf(JavaMethodParameterMetadata(0, "ids", type, "ids")),
        )
        val requirementId = InputRequirementId("foreach-type:ids")
        val provenance = InputProvenance(
            listOf(
                InputEvidence.MapperMethodParameter(0, "ids", type, source),
                InputEvidence.ExplicitParamAlias(0, "ids", source),
                InputEvidence.ForeachCollection("ids", source),
            ),
        )
        val requirement = InputRequirement(
            id = requirementId,
            kind = InputKind.BOUND,
            expectedType = ExpectedInputType(
                shape = InputShape.LIST,
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
                InputAlias("ids", requirementId, InputAliasKind.EXPLICIT_PARAM, provenance),
            ),
            internalBindings = listOf(
                InternalBinding(
                    "item",
                    InternalBindingKind.FOREACH_ITEM,
                    InputProvenance(listOf(InputEvidence.ForeachLocal("item", ForeachLocalRole.ITEM, source))),
                ),
            ),
            blockingProblems = emptyList(),
            sourceRevisions = mapOf(fileId to revision),
        )
        val environment = InputEnvironment.validate(
            contract,
            listOf(ProvidedInput(requirementId, value, ExecutionInputOrigin.USER_ENTERED)),
        )
        check(environment is InputEnvironmentResult.Success)
        val request = MyBatisPreparationRequest.create(
            PreparationSource.JavaAnnotation(capture),
            contract,
            environment.environment,
        )
        check(request is PreparationRequestResult.Ready)
        return MyBatisPreparationEngine.prepare(request.request)
    }
}
