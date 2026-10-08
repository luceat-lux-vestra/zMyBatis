package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.ExecutionInputOrigin
import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.InputEnvironmentResult
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.input.ProvidedInput
import com.algorist.zMyBatis.core.input.XmlMapperMethodParameterContractFactory
import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import com.algorist.zMyBatis.core.preparation.PreparationRequestResult
import com.algorist.zMyBatis.core.preparation.PreparationResult
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
import java.math.BigInteger
import java.io.ByteArrayInputStream
import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.session.Configuration
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import com.algorist.zMyBatis.core.materialization.MaintainedExecutionMaterializer
import com.algorist.zMyBatis.core.materialization.MaterializationResult
import com.algorist.zMyBatis.core.materialization.MaterializationFailureKind
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class XmlBooleanIfRuntimePreparationTest {
    @Test
    fun trueAndFalseRetainStockSqlAndOrderedCallerMappings() {
        val fixture = fixture("SELECT #{id} <if test=\"enabled\">, #{enabled,jdbcType=BOOLEAN}, #{id}</if>, #{id}")
        val active = success(prepare(fixture, true))
        assertEquals("SELECT ? , ?, ? , ?", normalize(active.sqlWithPlaceholders))
        assertEquals(listOf("id", "enabled", "id", "id"), active.orderedBindings.map { it.property })
        assertEquals(listOf(integer(42), InputValue.BooleanValue(true), integer(42), integer(42)), active.orderedBindings.map { it.value })
        assertEquals("BOOLEAN", active.orderedBindings[1].metadata.jdbcTypeIdentity)
        assertEquals("boolean", active.orderedBindings[1].metadata.declaredJavaTypeIdentity?.value)
        val stock = stockBoundSql(fixture, true, 42)
        assertEquals(stock.sql, active.sqlWithPlaceholders)
        assertEquals("org.apache.ibatis.type.UnknownTypeHandler", stock.parameterMappings[1].typeHandler.javaClass.name)
        assertEquals(stock.parameterMappings[1].typeHandler.javaClass.name, active.orderedBindings[1].metadata.typeHandlerIdentity)
        assertEquals(stock.parameterMappings[1].javaType.name, active.orderedBindings[1].metadata.mappingJavaTypeIdentity)
        assertTrue(active.orderedBindings.none { it.additionalParameter })
        assertTrue(active.rawInterpolations.isEmpty())
        assertEquals(contract(fixture).sourceRevisions, active.sourceRevisions)

        val inactive = success(prepare(fixture, false))
        assertEquals("SELECT ? , ?", normalize(inactive.sqlWithPlaceholders))
        assertEquals(stockBoundSql(fixture, false, 42).sql, inactive.sqlWithPlaceholders)
        assertEquals(listOf("id", "id"), inactive.orderedBindings.map { it.property })
        assertEquals(listOf(integer(42), integer(42)), inactive.orderedBindings.map { it.value })
    }

    @Test
    fun conditionOnlyRequirementDoesNotInventAnOrderedBinding() {
        val fixture = fixture("SELECT 1 <if test=\"enabled\">WHERE 1 = 1</if>")
        for (enabled in listOf(true, false)) {
            val execution = success(prepare(fixture, enabled))
            assertEquals(if (enabled) "SELECT 1 WHERE 1 = 1" else "SELECT 1", normalize(execution.sqlWithPlaceholders))
            assertTrue(execution.orderedBindings.isEmpty())
        }
    }

    @Test
    fun falseConditionCanRemoveAllMappingsWithoutDiscardingSourceAuthority() {
        val fixture = fixture("SELECT 1 <if test=\"enabled\">WHERE id = #{id}</if>")
        val execution = success(prepare(fixture, false))
        assertTrue(execution.orderedBindings.isEmpty())
        assertEquals("SELECT 1", normalize(execution.sqlWithPlaceholders))
        assertEquals(contract(fixture).sourceRevisions, execution.sourceRevisions)
    }

    @Test
    fun boxedBooleanAndGenericAliasUseProvenMapperParameterSemantics() {
        val boxed = fixture("SELECT 1 <if test=\"enabled\">WHERE id = #{id}</if>", type = "java.lang.Boolean")
        assertEquals("SELECT 1 WHERE id = ?", normalize(success(prepare(boxed, true)).sqlWithPlaceholders))
        val generic = fixture("SELECT 1 <if test=\"param1\">WHERE id = #{param2}</if>")
        val execution = success(prepare(generic, true))
        assertEquals(listOf("param2"), execution.orderedBindings.map { it.property })
        assertEquals(integer(42), execution.orderedBindings.single().value)
    }

    @Test
    fun namedMapPropertiesRetainMissingVersusNullSemanticsAcrossBranches() {
        val fixture = fixture("SELECT #{id.first} <if test=\"enabled\">, #{id.second}</if>", idType = "java.util.Map<java.lang.String,java.lang.Long>")
        val complete = InputValue.MapValue(mapOf("first" to integer(42), "second" to InputValue.NullValue))
        val active = success(prepare(fixture, true, overrides = mapOf(1 to complete)))
        assertEquals(listOf("id.first", "id.second"), active.orderedBindings.map { it.property })
        assertEquals(listOf(integer(42), InputValue.NullValue), active.orderedBindings.map { it.value })
        val incomplete = InputValue.MapValue(mapOf("first" to integer(42)))
        val inactive = success(prepare(fixture, false, overrides = mapOf(1 to incomplete)))
        assertEquals(listOf("id.first"), inactive.orderedBindings.map { it.property })
        val missing = (prepare(fixture, true, overrides = mapOf(1 to incomplete)) as PreparationResult.Failed).failure
        assertEquals(PreparationFailureKind.BINDING_RESOLUTION, missing.kind)
        assertEquals("xml-preparation-named-map-property-missing", missing.code)
    }

    @Test
    fun mapperCaptureIsRequiredAndContractCannotAuthorizeItself() {
        val fixture = fixture("SELECT 1 <if test=\"enabled\">WHERE id = #{id}</if>")
        val failure = (prepare(fixture, true, withMapper = false) as PreparationResult.Failed).failure
        assertEquals("xml-preparation-dynamic-sql-unsupported", failure.code)
        val original = contract(fixture)
        val forged = ParameterContract(original.statementId, original.requirements, original.aliases.map { it.copy(name = "forged-${it.name}") }, original.internalBindings, emptyList(), original.sourceRevisions)
        val mismatch = (prepare(fixture, true, supplied = forged) as PreparationResult.Failed).failure
        assertEquals(PreparationFailureKind.PREPARATION_INVARIANT, mismatch.kind)
        assertEquals("xml-boolean-if-preparation-source-contract-mismatch", mismatch.code)
    }

    @Test
    fun supportedSourceDriftIsRejectedBeforeDynamicEvaluation() {
        val fixture = fixture("SELECT 1 <if test=\"enabled\">WHERE id = #{id}</if>")
        val changed = fixture("SELECT 1 <if test=\"enabled\">WHERE id = #{id}, #{id}</if>")
        val failure = (prepare(changed, true, supplied = contract(fixture)) as PreparationResult.Failed).failure
        assertEquals("xml-boolean-if-preparation-source-contract-mismatch", failure.code)
    }

    @Test
    fun falseCannotHideAnUnsupportedComplexOrNestedSource() {
        val original = fixture("SELECT 1 <if test=\"enabled\">WHERE id = #{id}</if>")
        for (body in listOf(
            "SELECT 1 <if test=\"enabled != null\">WHERE id = #{id}</if>",
            "SELECT 1 <if test=\"enabled\"><if test=\"enabled\">#{id}</if></if>",
            "SELECT 1 <if test=\"enabled\"><include refid=\"fragment\"/></if>",
            "SELECT 1 <if test=\"enabled\">\${table}</if>",
        )) {
            val failure = (prepare(fixture(body), false, supplied = contract(original)) as PreparationResult.Failed).failure
            assertEquals("xml-boolean-if-preparation-source-unsupported", failure.code)
        }
    }

    @Test
    fun dynamicTokenFragmentsCannotSynthesizeOrEscapeCallerBindings() {
        val fixture = fixture("SELECT 1 <if test=\"enabled\">WHERE id = #{id}</if>")
        val original = contract(fixture)
        for (body in listOf(
            "SELECT #<if test=\"enabled\">{id}</if>",
            "SELECT \\\\<if test=\"enabled\">#{id}</if>",
        )) {
            val changed = fixture(body)
            val candidate = contract(changed)
            val failure = (prepare(changed, true, supplied = candidate) as PreparationResult.Failed).failure
            assertEquals("xml-boolean-if-bound-token-topology-unsupported", failure.code)
        }
        assertFalse(original.isPreparationBlocked)
    }

    @Test
    fun emptyRenderedSqlIsTypedFailureAndContextLoaderIsRestored() {
        val fixture = fixture("<if test=\"enabled\">SELECT #{id}</if>")
        val thread = Thread.currentThread()
        val before = thread.contextClassLoader
        val failure = (prepare(fixture, false) as PreparationResult.Failed).failure
        assertEquals("mybatis-prepared-sql-empty", failure.code)
        assertSame(before, thread.contextClassLoader)
        assertTrue(prepare(fixture, true) is PreparationResult.Success)
    }

    @Test
    fun boundPreparationsRemainNonExecutableThroughMaintainedMaterialization() {
        val fixture = fixture("SELECT #{id} <if test=\"enabled\">, #{id}</if>")
        for (enabled in listOf(true, false)) {
            val execution = success(prepare(fixture, enabled))
            val failure = (MaintainedExecutionMaterializer.materialize(execution) as MaterializationResult.Failed).failure
            assertEquals(MaterializationFailureKind.BOUND_EXECUTION_REQUIRED, failure.kind)
        }
    }

    @Test
    fun falseCannotHideInvalidCallerValuesOrMissingRequiredInputs() {
        val fixture = fixture("SELECT 1 <if test=\"enabled\">WHERE id = #{id}</if>")
        val contract = contract(fixture)
        val flag = contract.requirements.first()
        val missing = InputEnvironment.validate(contract, listOf(ProvidedInput(flag.id, InputValue.BooleanValue(false), ExecutionInputOrigin.USER_ENTERED))) as InputEnvironmentResult.Failure
        assertTrue(missing.failures.any { it.kind == com.algorist.zMyBatis.core.input.InputEnvironmentFailureKind.MISSING_REQUIRED_INPUT })
        val nullFlag = InputEnvironment.validate(contract, listOf(
            ProvidedInput(flag.id, InputValue.NullValue, ExecutionInputOrigin.USER_ENTERED),
            ProvidedInput(contract.requirements[1].id, integer(42), ExecutionInputOrigin.USER_ENTERED),
        )) as InputEnvironmentResult.Failure
        assertTrue(nullFlag.failures.any { it.kind == com.algorist.zMyBatis.core.input.InputEnvironmentFailureKind.NULL_NOT_ALLOWED })
        val environment = (InputEnvironment.validate(contract, listOf(
            ProvidedInput(flag.id, InputValue.BooleanValue(false), ExecutionInputOrigin.USER_ENTERED),
            ProvidedInput(contract.requirements[1].id, InputValue.IntegerValue(BigInteger.ONE.shiftLeft(100)), ExecutionInputOrigin.USER_ENTERED),
        )) as InputEnvironmentResult.Success).environment
        val request = (MyBatisPreparationRequest.create(XmlMapperPreparationSource(fixture.graph, mapperMethod = fixture.mapper), contract, environment) as PreparationRequestResult.Ready).request
        val failure = (XmlMapperPreparationEngine.prepare(request) as PreparationResult.Failed).failure
        assertEquals(PreparationFailureKind.UNSUPPORTED_BINDING_VALUE, failure.kind)
    }

    @Test
    fun staleMapperRevisionIsRefusedAtRequestConstruction() {
        val fixture = fixture("SELECT 1 <if test=\"enabled\">WHERE id = #{id}</if>")
        val contract = contract(fixture)
        val environment = (InputEnvironment.validate(contract, listOf(
            ProvidedInput(contract.requirements[0].id, InputValue.BooleanValue(true), ExecutionInputOrigin.USER_ENTERED),
            ProvidedInput(contract.requirements[1].id, integer(42), ExecutionInputOrigin.USER_ENTERED),
        )) as InputEnvironmentResult.Success).environment
        val changed = XmlMapperMethodCapture(fixture.mapper.statementId, fixture.mapper.mapperSource.copy(revision = SourceRevision("java-r2")), fixture.mapper.methodSourceRange, fixture.mapper.parameters)
        val failure = (MyBatisPreparationRequest.create(XmlMapperPreparationSource(fixture.graph, mapperMethod = changed), contract, environment) as PreparationRequestResult.Failed).failure
        assertEquals(PreparationFailureKind.SOURCE_REVISION_MISMATCH, failure.kind)
    }

    @Test
    fun ordinaryStockMappingFailureRestoresContextAndDoesNotPoisonLaterPreparation() {
        val fixture = fixture("SELECT 1 <if test=\"enabled\">WHERE id = #{id,jdbcType=NOT_A_TYPE}</if>")
        val before = Thread.currentThread().contextClassLoader
        val failure = (prepare(fixture, true) as PreparationResult.Failed).failure
        assertEquals(PreparationFailureKind.MYBATIS_PARSE, failure.kind)
        assertSame(before, Thread.currentThread().contextClassLoader)
        assertTrue(prepare(fixture("SELECT 1 <if test=\"enabled\">WHERE id = #{id}</if>"), true) is PreparationResult.Success)
    }

    @Test
    fun parentOgnlAccessorAndExpressionLimitCannotAffectXmlBooleanPreparation() = synchronized(Configuration::class.java) {
        val loader = Configuration::class.java.classLoader
        val context = Class.forName("org.apache.ibatis.scripting.xmltags.DynamicContext\$ContextMap", false, loader)
        val ognlRuntime = Class.forName("org.apache.ibatis.ognl.OgnlRuntime", true, loader)
        val accessor = Class.forName("org.apache.ibatis.ognl.PropertyAccessor", true, loader)
        val get = ognlRuntime.getMethod("getPropertyAccessor", Class::class.java)
        val set = ognlRuntime.getMethod("setPropertyAccessor", Class::class.java, accessor)
        val originalAccessor = get.invoke(null, context)
        val poison = Proxy.newProxyInstance(loader, arrayOf(accessor)) { _, _, _ -> throw AssertionError("parent accessor invoked") }
        val ognl = Class.forName("org.apache.ibatis.ognl.Ognl", true, loader)
        val max = ognl.getDeclaredField("expressionMaxLength").apply { isAccessible = true }
        val frozen = ognl.getDeclaredField("expressionMaxLengthFrozen").apply { isAccessible = true }
        val originalMax = max.get(null)
        val originalFrozen = frozen.get(null)
        set.invoke(null, context, poison)
        ognl.getMethod("thawExpressionMaxLength").invoke(null)
        ognl.getMethod("applyExpressionMaxLength", Int::class.javaObjectType).invoke(null, 1)
        try {
            assertThrows(InvocationTargetException::class.java) {
                ognl.getMethod("parseExpression", String::class.java).invoke(null, "enabled")
            }
            val fixture = fixture("SELECT 1 <if test=\"enabled\">WHERE id = #{id}</if>")
            for (enabled in listOf(true, false)) {
                val execution = success(prepare(fixture, enabled))
                assertEquals(if (enabled) 1 else 0, execution.orderedBindings.size)
                assertSame(poison, get.invoke(null, context))
                assertEquals(1, max.get(null))
            }
        } finally {
            max.set(null, originalMax)
            frozen.set(null, originalFrozen)
            set.invoke(null, context, originalAccessor)
        }
    }

    @Test
    fun reflectionWrappedFatalAndCancellationFailuresPreserveOriginalIdentity() {
        val guard = XmlMapperPreparationEngine::class.java.getDeclaredMethod("rethrowFatal", Throwable::class.java).apply { isAccessible = true }
        for (failure in listOf(AssertionError("fatal"), LinkageError("fatal"), CancellationException("cancelled"))) {
            try {
                guard.invoke(XmlMapperPreparationEngine, InvocationTargetException(RuntimeException("wrapped", failure)))
                throw AssertionError("failure was swallowed")
            } catch (wrapped: InvocationTargetException) {
                assertSame(failure, wrapped.targetException)
            }
        }
        guard.invoke(XmlMapperPreparationEngine, InvocationTargetException(IllegalArgumentException("ordinary")))
    }

    @Test
    fun concurrentInvocationsOwnIndependentRuntimeAndRestoreThreadContext() {
        val fixture = fixture("SELECT #{id} <if test=\"enabled\">, #{id}</if>")
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = (0 until 12).map { index -> pool.submit(Callable {
                val thread = Thread.currentThread()
                val before = thread.contextClassLoader
                val execution = success(prepare(fixture, index % 2 == 0, id = index))
                assertSame(before, thread.contextClassLoader)
                assertEquals(if (index % 2 == 0) 2 else 1, execution.orderedBindings.size)
                assertTrue(execution.orderedBindings.all { it.value == integer(index) })
                execution
            }) }
            results.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        }
    }

    private fun prepare(
        fixture: Fixture,
        enabled: Boolean,
        id: Int = 42,
        supplied: ParameterContract = contract(fixture),
        withMapper: Boolean = true,
        overrides: Map<Int, InputValue> = emptyMap(),
    ): PreparationResult {
        val environment = (InputEnvironment.validate(supplied, supplied.requirements.map { requirement ->
            val index = requirement.provenance.evidence.filterIsInstance<com.algorist.zMyBatis.core.input.InputEvidence.MapperMethodParameter>().single().index
            ProvidedInput(requirement.id, overrides[index] ?: if (index == 0) InputValue.BooleanValue(enabled) else integer(id), ExecutionInputOrigin.USER_ENTERED)
        }) as InputEnvironmentResult.Success).environment
        val source = if (withMapper) XmlMapperPreparationSource(fixture.graph, mapperMethod = fixture.mapper)
            else XmlMapperPreparationSource(fixture.graph, listOf(fixture.mapper.mapperSource))
        val request = MyBatisPreparationRequest.create(source, supplied, environment) as PreparationRequestResult.Ready
        return XmlMapperPreparationEngine.prepare(request.request)
    }

    private fun success(result: PreparationResult): PreparedExecution {
        assertTrue("expected success, received $result", result is PreparationResult.Success)
        return (result as PreparationResult.Success).execution
    }

    private fun contract(fixture: Fixture) = XmlMapperMethodParameterContractFactory.build(fixture.graph, fixture.mapper)

    private fun fixture(body: String, type: String = "boolean", idType: String = "long"): Fixture {
        val xml = """
            <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
            <mapper namespace="example.Mapper"><select id="find">$body</select></mapper>
        """.trimIndent()
        val graph = StatementSourceGraph(CapturedStatement(STATEMENT, StatementKind.SELECT, SourceRange(0, xml.length)), listOf(SourceSnapshot(XML_FILE, XML_REVISION, xml)), emptyList())
        val mapper = XmlMapperMethodCapture(STATEMENT, SourceSnapshot(JAVA_FILE, JAVA_REVISION, "x".repeat(100)), SourceRange(0, 100), listOf(JavaMethodParameterMetadata(0, "enabled", JavaTypeIdentity(type), "enabled"), JavaMethodParameterMetadata(1, "id", JavaTypeIdentity(idType), "id")))
        return Fixture(graph, mapper)
    }

    private fun stockBoundSql(fixture: Fixture, enabled: Boolean, id: Int): org.apache.ibatis.mapping.BoundSql {
        val configuration = Configuration()
        val snapshot = fixture.graph.sourceSnapshots.single()
        ByteArrayInputStream(snapshot.content.toByteArray(Charsets.UTF_8)).use { input ->
            XMLMapperBuilder(input, configuration, "stock-oracle", configuration.sqlFragments).parse()
        }
        return configuration.getMappedStatement("example.Mapper.find").getBoundSql(
            linkedMapOf("enabled" to enabled, "id" to id.toLong(), "param1" to enabled, "param2" to id.toLong()),
        )
    }

    private fun integer(value: Int) = InputValue.IntegerValue(BigInteger.valueOf(value.toLong()))
    private fun normalize(sql: String) = sql.trim().replace(Regex("\\s+"), " ")
    private data class Fixture(val graph: StatementSourceGraph, val mapper: XmlMapperMethodCapture)
    private companion object {
        val XML_FILE = SourceFileId("mapper.xml")
        val JAVA_FILE = SourceFileId("Mapper.java")
        val XML_REVISION = SourceRevision("xml-r1")
        val JAVA_REVISION = SourceRevision("java-r1")
        val STATEMENT = XmlStatementId(XML_FILE, "example.Mapper", "find")
    }
}
