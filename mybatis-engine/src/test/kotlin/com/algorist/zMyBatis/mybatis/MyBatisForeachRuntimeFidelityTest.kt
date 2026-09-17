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
import java.net.URLClassLoader
import org.apache.ibatis.session.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MyBatisForeachRuntimeFidelityTest {
    @Test
    fun foreachCollectionGenericTypeControlsMyBatisMappingTypeAndHandler() {
        val script = """
            <script>
              select
              <foreach collection="ids" item="item" separator=",">#{item}</foreach>
            </script>
        """.trimIndent()
        val request = request(script, listOf(7L, 9L))

        val result = MyBatisPreparationEngine.prepare(request)

        assertTrue(result is PreparationResult.Success)
        result as PreparationResult.Success
        assertEquals(
            listOf(InputValue.IntegerValue(BigInteger.valueOf(7)), InputValue.IntegerValue(BigInteger.valueOf(9))),
            result.execution.orderedBindings.map { it.value },
        )
        assertEquals(
            listOf("java.lang.Long", "java.lang.Long"),
            result.execution.orderedBindings.map { it.metadata.mappingJavaTypeIdentity },
        )
        assertEquals(
            listOf("org.apache.ibatis.type.LongTypeHandler", "org.apache.ibatis.type.LongTypeHandler"),
            result.execution.orderedBindings.map { it.metadata.typeHandlerIdentity },
        )
    }

    @Test
    fun generatedSnapshotIdentityMatchesPinnedMyBatisItemizeItem() {
        val script = """
            <script>
              select
              <foreach collection="ids" item="item" separator=",">#{item}</foreach>
            </script>
        """.trimIndent()
        val isolated = IsolatedDynamicMyBatisPreparation.prepare(
            script = script,
            parameterType = MyBatisParameterType.Multi,
            parameterValues = mapOf("ids" to listOf(17L)),
            admittedForeachLocals = mapOf("item" to InternalBindingKind.FOREACH_ITEM),
        )

        assertTrue(isolated is IsolatedDynamicMyBatisPreparation.Result.Ready)
        isolated as IsolatedDynamicMyBatisPreparation.Result.Ready
        val mapping = isolated.boundSql.mappings.single()
        val generated = mapping.generatedLocal
        assertNotNull(generated)
        generated!!

        val myBatisLocation = Configuration::class.java.protectionDomain.codeSource.location
        URLClassLoader(arrayOf(myBatisLocation), ClassLoader.getPlatformClassLoader()).use { loader ->
            val foreachClass = Class.forName(
                "org.apache.ibatis.scripting.xmltags.ForEachSqlNode",
                true,
                loader,
            )
            val itemize = foreachClass.getDeclaredMethod(
                "itemizeItem",
                String::class.java,
                Int::class.javaPrimitiveType,
            )
            itemize.isAccessible = true
            val expectedRoot = itemize.invoke(null, "item", generated.uniqueNumber) as String
            assertEquals(expectedRoot, mapping.property!!.substringBefore('.'))
        }
        assertEquals("item", generated.sourceLocalName)
        assertEquals(InternalBindingKind.FOREACH_ITEM, generated.kind)
    }

    private fun request(script: String, values: List<Long>): MyBatisPreparationRequest {
        val fileId = SourceFileId("fixture/ForeachRuntimeFidelityMapper.java")
        val revision = SourceRevision("foreach-runtime-fidelity-revision")
        val snapshot = SourceSnapshot(fileId, revision, "authoritative-foreach-runtime-fidelity-source")
        val range = SourceRange(0, snapshot.content.length)
        val listType = JavaTypeIdentity("java.util.List<java.lang.Long>")
        val statementId = JavaStatementId(
            fileId,
            "fixture.ForeachRuntimeFidelityMapper",
            MethodSignature("query", listOf(listType)),
        )
        val graph = StatementSourceGraph(
            rootStatement = CapturedStatement(statementId, StatementKind.SELECT, range),
            sourceSnapshots = listOf(snapshot),
            dependencies = emptyList(),
        )
        val capture = JavaAnnotationStatementCapture(
            sourceGraph = graph,
            sqlSegments = listOf(script),
            parameters = listOf(
                JavaMethodParameterMetadata(
                    index = 0,
                    sourceName = "ids",
                    typeIdentity = listType,
                    myBatisParamAlias = "ids",
                ),
            ),
        )
        val source = SourceEvidence(fileId, revision, range)
        val requirementId = InputRequirementId("foreach-runtime-fidelity:0")
        val provenance = InputProvenance(
            listOf(
                InputEvidence.MapperMethodParameter(0, "ids", listType, source),
                InputEvidence.ExplicitParamAlias(0, "ids", source),
                InputEvidence.OgnlExpression("ids", source),
                InputEvidence.ForeachCollection("ids", source),
            ),
        )
        val requirement = InputRequirement(
            id = requirementId,
            kind = InputKind.BOUND,
            expectedType = ExpectedInputType(
                shape = InputShape.LIST,
                javaTypeIdentity = listType,
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
                    name = "ids",
                    requirementId = requirementId,
                    kind = InputAliasKind.EXPLICIT_PARAM,
                    provenance = provenance,
                ),
            ),
            internalBindings = listOf(
                InternalBinding(
                    name = "item",
                    kind = InternalBindingKind.FOREACH_ITEM,
                    provenance = InputProvenance(
                        listOf(InputEvidence.ForeachLocal("item", ForeachLocalRole.ITEM, source)),
                    ),
                ),
            ),
            blockingProblems = emptyList(),
            sourceRevisions = mapOf(fileId to revision),
        )
        val environment = InputEnvironment.validate(
            contract,
            listOf(
                ProvidedInput(
                    requirementId = requirementId,
                    value = InputValue.ListValue(
                        values.map { InputValue.IntegerValue(BigInteger.valueOf(it)) },
                    ),
                    origin = ExecutionInputOrigin.USER_ENTERED,
                ),
            ),
        )
        check(environment is InputEnvironmentResult.Success) { "runtime fidelity fixture invalid: $environment" }
        val request = MyBatisPreparationRequest.create(
            source = PreparationSource.JavaAnnotation(capture),
            parameterContract = contract,
            inputEnvironment = environment.environment,
        )
        check(request is PreparationRequestResult.Ready) { "runtime fidelity request invalid: $request" }
        return request.request
    }
}
