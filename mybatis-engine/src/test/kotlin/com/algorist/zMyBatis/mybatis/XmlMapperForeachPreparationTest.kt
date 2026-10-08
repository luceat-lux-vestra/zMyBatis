package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.ExecutionInputOrigin
import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.InputEnvironmentFailureKind
import com.algorist.zMyBatis.core.input.InputEnvironmentResult
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.input.InternalBinding
import com.algorist.zMyBatis.core.input.InternalBindingKind
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.input.ProvidedInput
import com.algorist.zMyBatis.core.input.XmlMapperMethodParameterContractFactory
import com.algorist.zMyBatis.core.materialization.MaintainedExecutionMaterializer
import com.algorist.zMyBatis.core.materialization.MaterializationFailureKind
import com.algorist.zMyBatis.core.materialization.MaterializationResult
import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import com.algorist.zMyBatis.core.preparation.PreparationRequestResult
import com.algorist.zMyBatis.core.preparation.PreparationResult
import com.algorist.zMyBatis.core.preparation.PreparedBindingOrigin
import com.algorist.zMyBatis.core.preparation.PreparedExecution
import com.algorist.zMyBatis.core.preparation.XmlMapperPreparationSource
import com.algorist.zMyBatis.core.source.CapturedStatement
import com.algorist.zMyBatis.core.source.JavaMethodParameterMetadata
import com.algorist.zMyBatis.core.source.JavaTypeIdentity
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.StatementSourceGraph
import com.algorist.zMyBatis.core.source.XmlMapperMethodCapture
import com.algorist.zMyBatis.core.source.XmlStatementId
import java.lang.reflect.Proxy
import java.math.BigInteger
import java.net.URLClassLoader
import java.time.LocalDate
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.apache.ibatis.session.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlMapperForeachPreparationTest {
    @Test
    fun sourceDerivedListCapturesOrderedRepeatedItemsAndIndexProvenance() {
        val fixture = fixture(
            """SELECT <foreach collection="ids" item="item" index="idx" open="(" close=")" separator=",">#{idx},#{item,jdbcType=BIGINT},#{item}</foreach>""",
            listOf(parameter(0, "java.util.List<java.lang.Long>", "ids")),
        )
        val request = request(fixture, mapOf(0 to listValue(7, 9)))
        val execution = success(request)

        assertEquals("SELECT(?,?,?,?,?,?)", compact(execution.sqlWithPlaceholders))
        assertEquals(listOf(0L, 7L, 7L, 1L, 9L, 9L).map(::integer), execution.orderedBindings.map { it.value })
        assertEquals((0..5).toList(), execution.orderedBindings.map { it.index })
        assertEquals(
            listOf("__frch_idx_0", "__frch_item_0", "__frch_item_0", "__frch_idx_1", "__frch_item_1", "__frch_item_1"),
            execution.orderedBindings.map { it.property },
        )
        val locals = request.parameterContract.internalBindings.associateBy { it.name }
        for (binding in execution.orderedBindings) {
            val origin = binding.origin as PreparedBindingOrigin.MyBatisAdditional
            assertEquals(locals[origin.internalBinding.name], origin.internalBinding)
            assertEquals(origin.internalBinding.provenance, binding.provenance)
            assertEquals(null, binding.requirementId)
        }
        assertEquals("java.lang.Integer", execution.orderedBindings[0].metadata.mappingJavaTypeIdentity)
        assertEquals("org.apache.ibatis.type.IntegerTypeHandler", execution.orderedBindings[0].metadata.typeHandlerIdentity)
        assertEquals("java.lang.Long", execution.orderedBindings[1].metadata.mappingJavaTypeIdentity)
        assertEquals("org.apache.ibatis.type.LongTypeHandler", execution.orderedBindings[1].metadata.typeHandlerIdentity)
        assertEquals("BIGINT", execution.orderedBindings[1].metadata.jdbcTypeIdentity)
        assertEquals(request.sourceRevisions, execution.sourceRevisions)
        assertTrue(execution.rawInterpolations.isEmpty())
        val refusal = MaintainedExecutionMaterializer.materialize(execution) as MaterializationResult.Failed
        assertEquals(MaterializationFailureKind.BOUND_EXECUTION_REQUIRED, refusal.failure.kind)
    }

    @Test
    fun genericAndStockCollectionListArrayAliasesConsumeProducerAuthority() {
        val generic = fixture(
            """SELECT #{status}, <foreach collection="param2" item="item" separator=",">#{item}</foreach>, #{status}""",
            listOf(parameter(0, "java.lang.Long", "status"), parameter(1, "java.util.List<java.lang.Long>", "ids")),
        )
        val execution = success(request(generic, mapOf(0 to integer(3), 1 to listValue(7, 9))))
        assertEquals(listOf(3L, 7L, 9L, 3L).map(::integer), execution.orderedBindings.map { it.value })
        assertTrue(execution.orderedBindings.first().origin is PreparedBindingOrigin.CallerInput)
        assertTrue(execution.orderedBindings[1].origin is PreparedBindingOrigin.MyBatisAdditional)
        assertTrue(execution.orderedBindings.last().origin is PreparedBindingOrigin.CallerInput)

        for ((alias, type, value) in listOf(
            Triple("collection", "java.util.Collection<java.lang.Long>", listValue(4, 6)),
            Triple("list", "java.util.List<java.lang.Long>", listValue(4, 6)),
            Triple("array", "long[]", InputValue.ArrayValue(listOf(integer(4), integer(6)))),
        )) {
            val fixture = fixture(
                """SELECT <foreach collection="$alias" item="item" separator=",">#{item}</foreach>""",
                listOf(parameter(0, type, null)),
            )
            val prepared = success(request(fixture, mapOf(0 to value)))
            assertEquals(listOf(integer(4), integer(6)), prepared.orderedBindings.map { it.value })
            assertTrue(prepared.orderedBindings.all { it.additionalParameter })
            assertTrue(prepared.orderedBindings.all { it.metadata.mappingJavaTypeIdentity == "java.lang.Long" })
        }
    }

    @Test
    fun mapIndexIsKeyAndTemporalItemRetainsTypedValueAndHandler() {
        val fixture = fixture(
            """SELECT <foreach collection="entries" item="item" index="key" separator=",">#{key},#{item}</foreach>""",
            listOf(parameter(0, "java.util.Map<java.lang.String,java.time.LocalDate>", "entries")),
        )
        val date = LocalDate.of(2026, 10, 7)
        val execution = success(request(fixture, mapOf(0 to InputValue.MapValue(linkedMapOf("a" to InputValue.DateValue(date))))))

        assertEquals(listOf(InputValue.Text("a"), InputValue.DateValue(date)), execution.orderedBindings.map { it.value })
        assertEquals("org.apache.ibatis.type.StringTypeHandler", execution.orderedBindings[0].metadata.typeHandlerIdentity)
        assertEquals("org.apache.ibatis.type.LocalDateTypeHandler", execution.orderedBindings[1].metadata.typeHandlerIdentity)
        assertEquals(InternalBindingKind.FOREACH_INDEX, (execution.orderedBindings[0].origin as PreparedBindingOrigin.MyBatisAdditional).internalBinding.kind)
    }

    @Test
    fun emptyCollectionAndNullItemFollowStockMyBatisWithoutInventingBindings() {
        val fixture = fixture(
            """SELECT 1 <foreach collection="ids" item="item" separator=",">,#{item}</foreach>""",
            listOf(parameter(0, "java.util.List<java.lang.Long>", "ids")),
        )
        val empty = success(request(fixture, mapOf(0 to listValue())))
        assertEquals("SELECT1", compact(empty.sqlWithPlaceholders))
        assertTrue(empty.orderedBindings.isEmpty())
        val nullItem = success(request(fixture, mapOf(0 to InputValue.ListValue(listOf(InputValue.NullValue)))))
        assertEquals(InputValue.NullValue, nullItem.orderedBindings.single().value)
        assertTrue(nullItem.orderedBindings.single().additionalParameter)
        val nullCollection = InputEnvironment.validate(
            contract(fixture),
            listOf(ProvidedInput(contract(fixture).requirements.single().id, InputValue.NullValue, ExecutionInputOrigin.USER_ENTERED)),
        ) as InputEnvironmentResult.Failure
        assertEquals(InputEnvironmentFailureKind.NULL_NOT_ALLOWED, nullCollection.failures.single().kind)
    }

    @Test
    fun myBatisMappingFailureRestoresTheOriginalContextLoader() {
        val fixture = fixture(
            """SELECT <foreach collection="ids" item="item">#{item,jdbcType=NOT_A_JDBC_TYPE}</foreach>""",
            listOf(parameter(0, "java.util.List<java.lang.Long>", "ids")),
        )
        val contextLoader = Thread.currentThread().contextClassLoader
        val result = XmlMapperPreparationEngine.prepare(request(fixture, mapOf(0 to listValue(7)))) as PreparationResult.Failed
        assertEquals(PreparationFailureKind.MYBATIS_PARSE, result.failure.kind)
        assertEquals("mybatis-xml-mapper-parse-failure", result.failure.code)
        assertSame(contextLoader, Thread.currentThread().contextClassLoader)
    }

    @Test
    fun mappingSnapshotPropagatesWrappedFatalAndCancellationWhileRejectingOrdinaryLookupFailure() {
        val location = Configuration::class.java.protectionDomain.codeSource.location
        URLClassLoader(arrayOf(location), ClassLoader.getPlatformClassLoader()).use { loader ->
            val configurationClass = Class.forName("org.apache.ibatis.session.Configuration", true, loader)
            val configuration = configurationClass.getDeclaredConstructor().newInstance()
            val builderClass = Class.forName("org.apache.ibatis.mapping.ParameterMapping\$Builder", true, loader)
            val builder = builderClass.getConstructor(configurationClass, String::class.java, Class::class.java)
                .newInstance(configuration, "id", Long::class.javaObjectType)
            val mappings = listOf(builderClass.getMethod("build").invoke(builder))
            val boundSqlClass = Class.forName("org.apache.ibatis.mapping.BoundSql", true, loader)
            for (failure in listOf(AssertionError("fatal lookup"), LinkageError("linkage lookup"), CancellationException("cancelled lookup"), IllegalStateException("ordinary lookup"))) {
                val values = object : HashMap<String, Any?>() {
                    override fun get(key: String): Any? = throw failure
                }
                val boundSql = boundSqlClass.getConstructor(configurationClass, String::class.java, List::class.java, Any::class.java)
                    .newInstance(configuration, "SELECT ?", mappings, values)
                val result = runCatching {
                    IsolatedDynamicMyBatisPreparation.snapshotMappings(loader, configuration, boundSql, values, mappings, emptyMap())
                }
                if (failure is Exception && failure !is CancellationException) {
                    assertTrue(result.getOrThrow().single().runtimeValue is MyBatisRuntimeValueSnapshot.Failed)
                } else {
                    assertSame(failure, result.exceptionOrNull())
                }
            }
        }
    }

    @Test
    fun declaredElementMismatchFailsBeforeDynamicPreparation() {
        val fixture = fixture(
            """SELECT <foreach collection="ids" item="item">#{item}</foreach>""",
            listOf(parameter(0, "java.util.List<java.lang.Long>", "ids")),
        )
        val failure = XmlMapperPreparationEngine.prepare(
            request(fixture, mapOf(0 to InputValue.ListValue(listOf(InputValue.Text("wrong"))))),
        ) as PreparationResult.Failed
        assertEquals(PreparationFailureKind.UNSUPPORTED_BINDING_VALUE, failure.failure.kind)
        assertEquals("java-parameter-value-type-mismatch", failure.failure.code)
    }

    @Test
    fun sourceDriftAndForgedLocalAreRejectedByAdmissionBeforeRuntime() {
        val fixture = fixture(
            """SELECT <foreach collection="ids" item="item">#{item}</foreach>""",
            listOf(parameter(0, "java.util.List<java.lang.Long>", "ids")),
        )
        val authentic = contract(fixture)
        val snapshot = fixture.graph.sourceSnapshots.single()
        val drifted = fixture.copy(graph = StatementSourceGraph(
            fixture.graph.rootStatement,
            listOf(snapshot.copy(content = snapshot.content.replace("#{item}", "<if test=\"item != null\">#{item}</if>"))),
            emptyList(),
        ))
        val drift = XmlMapperPreparationEngine.prepare(request(drifted, mapOf(0 to listValue(7)), authentic)) as PreparationResult.Failed
        assertEquals("xml-foreach-preparation-source-unsupported", drift.failure.code)

        val local = authentic.internalBindings.single()
        val forged = ParameterContract(
            authentic.statementId, authentic.requirements, authentic.aliases,
            listOf(InternalBinding("forged", local.kind, local.provenance)),
            authentic.blockingProblems, authentic.sourceRevisions,
        )
        val failure = XmlMapperPreparationEngine.prepare(request(fixture, mapOf(0 to listValue(7)), forged)) as PreparationResult.Failed
        assertEquals("xml-foreach-preparation-source-contract-mismatch", failure.failure.code)
    }

    @Test
    fun additionalCaptureRejectsMissingGeneratedIdentityWrongKindAndNestedProperty() {
        val fixture = fixture(
            """SELECT <foreach collection="ids" item="item">#{item}</foreach>""",
            listOf(parameter(0, "java.util.List<java.lang.Long>", "ids")),
        )
        val request = request(fixture, mapOf(0 to listValue(7)))
        val admission = XmlForeachPreparationAdmission.inspect(fixture.graph, request.parameterContract) as XmlForeachPreparationAdmission.Result.Admitted
        val mapping = MyBatisParameterMappingSnapshot(
            "__frch_item_0", true, MyBatisGeneratedLocalSnapshot("item", InternalBindingKind.FOREACH_ITEM, 0),
            MyBatisRuntimeValueSnapshot.Ready(7L), "java.lang.Long", null, "org.apache.ibatis.type.LongTypeHandler", "IN", null,
        )
        for (forged in listOf(
            mapping.copy(generatedLocal = null),
            mapping.copy(additionalParameter = false),
            mapping.copy(generatedLocal = MyBatisGeneratedLocalSnapshot("item", InternalBindingKind.FOREACH_INDEX, 0)),
            mapping.copy(property = "__frch_item_0.id"),
        )) {
            val result = XmlForeachAdditionalBindingCapture.capture(0, forged, request, admission) as XmlForeachAdditionalBindingCapture.Result.Failed
            assertEquals("xml-foreach-additional-authority-unproven", result.failure.code)
        }
    }

    @Test
    fun unsupportedForeachSourceNeverAcquiresRuntimeAuthorityFromAnOlderContract() {
        val parameters = listOf(parameter(0, "java.util.List<java.lang.Long>", "ids"))
        val original = fixture("""SELECT <foreach collection="ids" item="item">#{item}</foreach>""", parameters)
        val authentic = contract(original)
        for (body in listOf(
            """SELECT <foreach collection="ids" item="item">#{item.id}</foreach>""",
            """SELECT <foreach collection="ids" item="item"><foreach collection="ids" item="inner">#{inner}</foreach></foreach>""",
            """SELECT <foreach collection="ids" item="item"><include refid="missing"/></foreach>""",
            """SELECT <foreach collection="ids" item="item" nullable="true">#{item}</foreach>""",
            """SELECT <foreach collection="ids" item="item">${'$'}{item}</foreach>""",
        )) {
            val result = XmlMapperPreparationEngine.prepare(request(fixture(body, parameters), mapOf(0 to listValue(7)), authentic)) as PreparationResult.Failed
            assertEquals("xml-foreach-preparation-source-unsupported", result.failure.code)
        }
    }

    @Test
    fun concurrentFreshXmlRuntimesIgnoreParentOgnlAndRestoreContextLoader() {
        val loader = Configuration::class.java.classLoader
        val contextMap = Class.forName("org.apache.ibatis.scripting.xmltags.DynamicContext\$ContextMap", false, loader)
        val ognl = Class.forName("org.apache.ibatis.ognl.OgnlRuntime", true, loader)
        val accessor = Class.forName("org.apache.ibatis.ognl.PropertyAccessor", true, loader)
        val get = ognl.getMethod("getPropertyAccessor", Class::class.java)
        val set = ognl.getMethod("setPropertyAccessor", Class::class.java, accessor)
        val original = get.invoke(null, contextMap)
        val poison = Proxy.newProxyInstance(loader, arrayOf(accessor)) { _, method, _ ->
            throw AssertionError("parent OGNL accessor invoked: ${method.name}")
        }
        set.invoke(null, contextMap, poison)
        try {
            val fixture = fixture(
                """SELECT <foreach collection="ids" item="item" separator=",">#{item}</foreach>""",
                listOf(parameter(0, "java.util.List<java.lang.Long>", "ids")),
            )
            val request = request(fixture, mapOf(0 to listValue(7, 9)))
            Executors.newFixedThreadPool(4).use { executor ->
                val futures = executor.invokeAll((0 until 12).map {
                    Callable {
                        val thread = Thread.currentThread()
                        val contextLoader = thread.contextClassLoader
                        val prepared = success(request)
                        assertSame(contextLoader, thread.contextClassLoader)
                        prepared
                    }
                }, 60, TimeUnit.SECONDS)
                val executions = futures.map { it.get(1, TimeUnit.SECONDS) }
                assertTrue(executions.all { it == executions.first() })
                assertEquals(listOf("__frch_item_0", "__frch_item_1"), executions.first().orderedBindings.map { it.property })
            }
            assertSame(poison, get.invoke(null, contextMap))
        } finally {
            set.invoke(null, contextMap, original)
        }
    }

    private fun success(request: MyBatisPreparationRequest): PreparedExecution = when (val result = XmlMapperPreparationEngine.prepare(request)) {
        is PreparationResult.Success -> result.execution
        is PreparationResult.Failed -> throw AssertionError("${result.failure.kind}: ${result.failure.code} (${result.failure.diagnosticType})")
    }

    private fun request(fixture: Fixture, values: Map<Int, InputValue>, contract: ParameterContract = contract(fixture)): MyBatisPreparationRequest {
        val provided = contract.requirements.map { requirement ->
            val index = requirement.provenance.evidence.filterIsInstance<InputEvidence.MapperMethodParameter>().first().index
            ProvidedInput(requirement.id, values.getValue(index), ExecutionInputOrigin.USER_ENTERED)
        }
        val environment = (InputEnvironment.validate(contract, provided) as InputEnvironmentResult.Success).environment
        return (MyBatisPreparationRequest.create(
            XmlMapperPreparationSource(fixture.graph, additionalAuthoritySnapshots = listOf(fixture.mapper.mapperSource)),
            contract, environment,
        ) as PreparationRequestResult.Ready).request
    }

    private fun contract(fixture: Fixture) = XmlMapperMethodParameterContractFactory.build(fixture.graph, fixture.mapper)

    private fun fixture(body: String, parameters: List<JavaMethodParameterMetadata>): Fixture {
        val file = SourceFileId("vfs:/foreach.xml")
        val id = XmlStatementId(file, "example.Mapper", "find")
        val xml = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
            <mapper namespace="example.Mapper"><select id="find">$body</select></mapper>
        """.trimIndent()
        val graph = StatementSourceGraph(
            CapturedStatement(id, StatementKind.SELECT, SourceRange(0, xml.length)),
            listOf(SourceSnapshot(file, SourceRevision("xml-foreach-r1"), xml)), emptyList(),
        )
        val mapper = XmlMapperMethodCapture(
            id, SourceSnapshot(SourceFileId("vfs:/Mapper.java"), SourceRevision("java-foreach-r1"), "x".repeat(256)),
            SourceRange(20, 120), parameters,
        )
        return Fixture(graph, mapper)
    }

    private fun parameter(index: Int, type: String, alias: String?) = JavaMethodParameterMetadata(index, "arg$index", JavaTypeIdentity(type), alias)
    private fun integer(value: Long) = InputValue.IntegerValue(BigInteger.valueOf(value))
    private fun listValue(vararg values: Long) = InputValue.ListValue(values.map(::integer))
    private fun compact(sql: String) = sql.replace(Regex("\\s+"), "")
    private data class Fixture(val graph: StatementSourceGraph, val mapper: XmlMapperMethodCapture)
}
