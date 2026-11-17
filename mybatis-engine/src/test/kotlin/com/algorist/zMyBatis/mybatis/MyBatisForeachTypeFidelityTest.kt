package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.ExecutionInputOrigin
import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.InputEnvironmentResult
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.input.InternalBindingKind
import com.algorist.zMyBatis.core.input.ProvidedInput
import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import com.algorist.zMyBatis.core.preparation.PreparationRequestResult
import com.algorist.zMyBatis.core.preparation.PreparationResult
import com.algorist.zMyBatis.core.preparation.PreparationSource
import com.algorist.zMyBatis.core.preparation.PreparedBindingOrigin
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

class MyBatisForeachTypeFidelityTest {
    @Test
    fun listLongCollectionPreservesElementRuntimeTypeForMyBatisMapping() {
        assertLongBindings(
            prepare(
                "java.util.List<java.lang.Long>",
                InputValue.ListValue(listOf(integer(1), integer(2))),
            ),
            1,
            2,
        )
    }

    @Test
    fun collectionLongPreservesElementRuntimeTypeForMyBatisMapping() {
        assertLongBindings(
            prepare(
                "java.util.Collection<java.lang.Long>",
                InputValue.ListValue(listOf(integer(3), integer(4))),
            ),
            3,
            4,
        )
    }

    @Test
    fun boxedLongArrayPreservesElementRuntimeTypeForMyBatisMapping() {
        assertLongBindings(
            prepare(
                "java.lang.Long[]",
                InputValue.ArrayValue(listOf(integer(5), integer(6))),
            ),
            5,
            6,
        )
    }

    @Test
    fun primitiveLongArrayPreservesElementRuntimeTypeForMyBatisMapping() {
        assertLongBindings(
            prepare(
                "long[]",
                InputValue.ArrayValue(listOf(integer(7), integer(8))),
            ),
            7,
            8,
        )
    }

    @Test
    fun mapStringLongPreservesValueRuntimeTypeForMyBatisMapping() {
        assertLongBindings(
            prepare(
                "java.util.Map<java.lang.String,java.lang.Long>",
                InputValue.MapValue(
                    linkedMapOf(
                        "left" to integer(9),
                        "right" to integer(10),
                    ),
                ),
            ),
            9,
            10,
        )
    }

    @Test
    fun preparedForeachLongBindingsRemainOrderedForExecutionAdapter() {
        assertLongBindings(
            prepare(
                "java.util.List<java.lang.Long>",
                InputValue.ListValue(listOf(integer(17), integer(19))),
            ),
            17,
            19,
        )
    }

    @Test
    fun rawListCollectionTypeFailsClosedInsteadOfUsingUntypedElements() {
        assertUnsupportedCollection(
            prepare(
                "java.util.List",
                InputValue.ListValue(listOf(integer(1))),
            ),
        )
    }

    @Test
    fun wildcardCollectionTypeFailsClosedInsteadOfGuessingElementType() {
        assertUnsupportedCollection(
            prepare(
                "java.util.List<? extends java.lang.Long>",
                InputValue.ListValue(listOf(integer(1))),
            ),
        )
    }

    @Test
    fun applicationPojoElementTypeFailsClosedInsteadOfBecomingAMap() {
        assertUnsupportedCollection(
            prepare(
                "java.util.List<fixture.Row>",
                InputValue.ListValue(
                    listOf(
                        InputValue.ObjectValue(
                            linkedMapOf("id" to integer(1)),
                        ),
                    ),
                ),
            ),
        )
    }

    private fun assertLongBindings(result: PreparationResult, vararg expected: Long) {
        assertTrue(result is PreparationResult.Success)
        result as PreparationResult.Success
        assertEquals(expected.size, result.execution.orderedBindings.size)
        result.execution.orderedBindings.forEachIndexed { index, binding ->
            assertEquals(integer(expected[index]), binding.value)
            assertEquals("java.lang.Long", binding.metadata.mappingJavaTypeIdentity)
            assertEquals("org.apache.ibatis.type.LongTypeHandler", binding.metadata.typeHandlerIdentity)
            assertTrue(binding.additionalParameter)
            val origin = binding.origin as PreparedBindingOrigin.MyBatisAdditional
            assertEquals(InternalBindingKind.ADDITIONAL_PARAMETER, origin.internalBinding.kind)
        }
    }

    private fun assertUnsupportedCollection(result: PreparationResult) {
        assertTrue(result is PreparationResult.Failed)
        result as PreparationResult.Failed
        assertEquals(PreparationFailureKind.UNSUPPORTED_BINDING_VALUE, result.failure.kind)
        assertEquals("java-parameter-value-unsupported", result.failure.code)
        assertNull(result.failure.bindingProperty)
    }

    private fun prepare(
        declaredType: String,
        value: InputValue,
    ): PreparationResult {
        val fileId = SourceFileId("fixture/ForeachTypeFidelity.java")
        val revision = SourceRevision("foreach-type-fidelity-revision")
        val snapshot = SourceSnapshot(fileId, revision, "authoritative-foreach-type-source")
        val type = JavaTypeIdentity(declaredType)
        val statementId = JavaStatementId(
            fileId,
            "fixture.ForeachTypeFidelity",
            MethodSignature("query", listOf(type)),
        )
        val script = """
            <script>
              select
              <foreach collection="ids" item="item" separator=",">#{item}</foreach>
            </script>
        """.trimIndent()
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
            listOf(JavaMethodParameterMetadata(0, "ids", type, "ids")),
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
        return MyBatisPreparationEngine.prepare(request.request)
    }

    private fun integer(value: Long) = InputValue.IntegerValue(BigInteger.valueOf(value))
}
