package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.ExecutionInputOrigin
import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.InputEnvironmentResult
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.input.InternalBinding
import com.algorist.zMyBatis.core.input.InternalBindingKind
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.input.ProvidedInput
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
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import java.math.BigInteger
import java.net.URLClassLoader
import java.time.LocalDate
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.session.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlMapperForeachPreparationTest {
    @Test
    fun exactSelectBooleanComparisonFamilyMatchesStockAndRemainsUnexecutableWhenBound() {
        val parameters = listOf(parameter(0, "java.lang.Boolean", "enabled"), parameter(1, "long", "id"))
        val placements = listOf(
            "" to "",
            "<where>" to "</where>",
            """<trim prefix="WHERE" prefixOverrides="AND |OR ">""" to "</trim>",
        )
        for (expression in listOf("enabled == true", "enabled == false", "enabled != true", "enabled != false")) {
            val guard = requireNotNull(com.algorist.zMyBatis.core.input.XmlBooleanGuardSyntax.parse(expression))
            for ((open, close) in placements) {
                val source = """SELECT #{id,jdbcType=BIGINT} $open<if test="$expression">AND id = #{id,jdbcType=BIGINT}</if>$close"""
                val original = fixture(source, parameters)
                val proven = contract(original)
                assertTrue(source, !proven.isPreparationBlocked)
                for (enabled in listOf(false, true)) {
                    val before = Thread.currentThread().contextClassLoader
                    val prepared = success(request(original, mapOf(0 to InputValue.BooleanValue(enabled), 1 to integer(7))))
                    assertSame(before, Thread.currentThread().contextClassLoader)
                    assertStockParity(original, mapOf("enabled" to enabled, "id" to 7L), prepared)
                    val expectedCount = if (guard.isActive(enabled)) 2 else 1
                    assertEquals(source, expectedCount, prepared.orderedBindings.size)
                    assertEquals(List(expectedCount) { "id" }, prepared.orderedBindings.map { it.property })
                    assertTrue(prepared.orderedBindings.all { it.origin is PreparedBindingOrigin.CallerInput })
                    assertEquals(proven.sourceRevisions, prepared.sourceRevisions)
                    assertEquals(MaterializationFailureKind.BOUND_EXECUTION_REQUIRED,
                        (MaintainedExecutionMaterializer.materialize(prepared) as MaterializationResult.Failed).failure.kind)
                }
            }
        }
    }

    @Test
    fun exactSelectFalseComparisonRetainsStockSqlBindingAndMaterializationPolicy() {
        val parameters = listOf(parameter(0, "boolean", "enabled"), parameter(1, "long", "id"))
        val placements = listOf(
            "" to "",
            "<where>" to "</where>",
            """<trim prefix="WHERE" prefixOverrides="AND |OR ">""" to "</trim>",
        )
        for ((open, close) in placements) {
            val source = """SELECT #{id,jdbcType=BIGINT} $open<if test="enabled == false">AND id = #{id,jdbcType=BIGINT}</if>$close"""
            val original = fixture(source, parameters)
            val proven = contract(original)
            assertTrue(!proven.isPreparationBlocked)
            for (enabled in listOf(false, true)) {
                val before = Thread.currentThread().contextClassLoader
                val prepared = success(request(original,
                    mapOf(0 to InputValue.BooleanValue(enabled), 1 to integer(7))))
                assertSame(before, Thread.currentThread().contextClassLoader)
                assertStockParity(original, mapOf("enabled" to enabled, "id" to 7L), prepared)
                assertEquals(if (enabled) 1 else 2, prepared.orderedBindings.size)
                assertEquals(List(if (enabled) 1 else 2) { "id" },
                    prepared.orderedBindings.map { it.property })
                assertTrue(prepared.orderedBindings.all { it.origin is PreparedBindingOrigin.CallerInput })
                assertEquals(proven.sourceRevisions, prepared.sourceRevisions)
                assertEquals(MaterializationFailureKind.BOUND_EXECUTION_REQUIRED,
                    (MaintainedExecutionMaterializer.materialize(prepared) as MaterializationResult.Failed).failure.kind)
            }
        }
    }

    @Test
    fun singleNegatedSelectWhereMatchesStockForBothStatesAndAllAdmittedTrims() {
        val parameters = listOf(parameter(0, "boolean", "enabled"), parameter(1, "long", "id"))
        for ((open, close) in wrappers()) {
            val source = """SELECT #{id,jdbcType=BIGINT} $open<if test="!enabled">OR id = #{id,jdbcType=BIGINT}</if>$close"""
            val original = fixture(source, parameters)
            val proven = contract(original)
            assertTrue(!proven.isPreparationBlocked)
            for (enabled in listOf(false, true)) {
                val values = mapOf(0 to InputValue.BooleanValue(enabled), 1 to integer(7))
                val before = Thread.currentThread().contextClassLoader
                val prepared = success(request(original, values))
                assertSame(before, Thread.currentThread().contextClassLoader)
                assertStockParity(original, mapOf("enabled" to enabled, "id" to 7L), prepared)
                assertEquals(if (enabled) 1 else 2, prepared.orderedBindings.size)
                assertEquals(List(if (enabled) 1 else 2) { "id" }, prepared.orderedBindings.map { it.property })
                assertTrue(prepared.orderedBindings.all { it.origin is PreparedBindingOrigin.CallerInput })
                assertEquals(proven.sourceRevisions, prepared.sourceRevisions)
                assertEquals(MaterializationFailureKind.BOUND_EXECUTION_REQUIRED,
                    (MaintainedExecutionMaterializer.materialize(prepared) as MaterializationResult.Failed).failure.kind)
            }
        }
    }

    @Test
    fun directSelectNegatedBooleanMatchesStockAndMaintainsConditionalMappingCardinality() {
        val parameters = listOf(parameter(0, "boolean", "enabled"), parameter(1, "long", "id"))
        val original = fixture(
            """SELECT #{id,jdbcType=BIGINT}<if test="!enabled"> + #{id,jdbcType=BIGINT}</if>""",
            parameters,
        )
        val authentic = contract(original)
        assertTrue(!authentic.isPreparationBlocked)
        for (enabled in listOf(false, true)) {
            val before = Thread.currentThread().contextClassLoader
            val prepared = success(request(original, mapOf(
                0 to InputValue.BooleanValue(enabled),
                1 to integer(7),
            )))
            assertSame(before, Thread.currentThread().contextClassLoader)
            assertStockParity(original, mapOf("enabled" to enabled, "id" to 7L), prepared)
            assertEquals(if (enabled) 1 else 2, prepared.orderedBindings.size)
            assertTrue(prepared.orderedBindings.all { it.origin is PreparedBindingOrigin.CallerInput })
            assertEquals(authentic.sourceRevisions, prepared.sourceRevisions)
            assertEquals(
                MaterializationFailureKind.BOUND_EXECUTION_REQUIRED,
                (MaintainedExecutionMaterializer.materialize(prepared) as MaterializationResult.Failed).failure.kind,
            )
        }
    }

    @Test
    fun directBooleanIfAndForeachSiblingsMatchStockAcrossAllStatesAndOrders() {
        val parameters = listOf(
            parameter(0, "boolean", "enabled"),
            parameter(1, "long", "id"),
            parameter(2, "java.util.List<java.lang.Long>", "ids"),
        )
        val conditional = "<if test=\"enabled\"> + #{id,jdbcType=BIGINT}</if>"
        val loop = "<foreach collection=\"ids\" item=\"item\" index=\"idx\" open=\" + (\" separator=\" + \" close=\")\">#{item,jdbcType=BIGINT} + #{idx}</foreach>"

        for (parts in listOf(listOf(conditional, loop), listOf(loop, conditional))) {
            val fixture = fixture("SELECT #{id,jdbcType=BIGINT}" + parts.joinToString(" "), parameters)
            for (enabled in listOf(false, true)) {
                for (ids in listOf(emptyList<Long>(), listOf(7L, 9L))) {
                    val execution = success(
                        request(
                            fixture,
                            mapOf(
                                0 to InputValue.BooleanValue(enabled),
                                1 to integer(3),
                                2 to InputValue.ListValue(ids.map(::integer)),
                            ),
                        ),
                    )

                    assertStockParity(
                        fixture,
                        mapOf("enabled" to enabled, "id" to 3L, "ids" to ids),
                        execution,
                    )
                    val callerBindings = execution.orderedBindings.filter {
                        it.origin is PreparedBindingOrigin.CallerInput
                    }
                    val additionalBindings = execution.orderedBindings.filter {
                        it.origin is PreparedBindingOrigin.MyBatisAdditional
                    }
                    assertEquals(if (enabled) 2 else 1, callerBindings.size)
                    assertEquals(ids.size * 2, additionalBindings.size)
                    assertEquals(
                        List(ids.size) { listOf("item", "idx") }.flatten(),
                        additionalBindings.map {
                            (it.origin as PreparedBindingOrigin.MyBatisAdditional).internalBinding.name
                        },
                    )
                    assertEquals(contract(fixture).sourceRevisions, execution.sourceRevisions)
                    assertEquals(
                        MaterializationFailureKind.BOUND_EXECUTION_REQUIRED,
                        (MaintainedExecutionMaterializer.materialize(execution) as MaterializationResult.Failed).failure.kind,
                    )
                }
            }
        }
    }

    @Test
    fun selectWhereMixedSiblingsMatchStockForAllOrdersAndStates() {
        val parameters = listOf(
            parameter(0, "boolean", "enabled"),
            parameter(1, "long", "id"),
            parameter(2, "java.util.List<java.lang.Long>", "ids"),
        )
        val conditional = """<if test="enabled"> OR flag = #{id,jdbcType=BIGINT}</if>"""
        val loop = """<foreach collection="ids" item="item" index="idx" open="AND (id, ord) IN (" separator="," close=")">(#{item,jdbcType=BIGINT}, #{idx})</foreach>"""
        for ((open, close) in wrappers()) {
            for (parts in listOf(listOf(conditional, loop), listOf(loop, conditional))) {
                val fixture = fixture("SELECT #{id} $open${parts.joinToString(" ")}$close", parameters)
                for (enabled in listOf(false, true)) {
                    for (ids in listOf(emptyList<Long>(), listOf(7L, 9L))) {
                        val values = mapOf(
                            0 to InputValue.BooleanValue(enabled),
                            1 to integer(3),
                            2 to InputValue.ListValue(ids.map(::integer)),
                        )
                        val before = Thread.currentThread().contextClassLoader
                        val execution = success(request(fixture, values))
                        assertSame(before, Thread.currentThread().contextClassLoader)
                        assertStockParity(
                            fixture,
                            mapOf("enabled" to enabled, "id" to 3L, "ids" to ids),
                            execution,
                        )
                        val caller = execution.orderedBindings.filter {
                            it.origin is PreparedBindingOrigin.CallerInput
                        }
                        val additional = execution.orderedBindings.filter {
                            it.origin is PreparedBindingOrigin.MyBatisAdditional
                        }
                        assertEquals(if (enabled) 2 else 1, caller.size)
                        assertEquals(ids.size * 2, additional.size)
                        assertEquals(
                            List(ids.size) { listOf("item", "idx") }.flatten(),
                            additional.map {
                                (it.origin as PreparedBindingOrigin.MyBatisAdditional).internalBinding.name
                            },
                        )
                        assertEquals(contract(fixture).sourceRevisions, execution.sourceRevisions)
                        assertEquals(
                            MaterializationFailureKind.BOUND_EXECUTION_REQUIRED,
                            (MaintainedExecutionMaterializer.materialize(execution) as MaterializationResult.Failed).failure.kind,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun updateStaticSetWhereMixedSiblingsMatchStockAcrossStatesAndOrders() {
        val parameters = listOf(
            parameter(0, "boolean", "enabled"),
            parameter(1, "long", "id"),
            parameter(2, "java.util.List<java.lang.Long>", "ids"),
        )
        val condition = """<if test="enabled">AND flag=#{id,jdbcType=BIGINT}</if>"""
        val loop = """<foreach collection="ids" item="item" index="idx" open="AND (id, ord) IN (" separator="," close=")">(#{item,jdbcType=BIGINT},#{idx})</foreach>"""
        for ((open, close) in wrappers()) {
            for (parts in listOf(listOf(condition, loop), listOf(loop, condition))) {
                val fixture = fixture(
                    "UPDATE t SET status=#{id,jdbcType=BIGINT} $open AND tenant_id=#{id,jdbcType=BIGINT} ${parts.joinToString(" ")} $close",
                    parameters, StatementKind.UPDATE,
                )
                for (enabled in listOf(false, true)) {
                    for (ids in listOf(emptyList<Long>(), listOf(7L, 9L))) {
                        val values = mapOf(0 to InputValue.BooleanValue(enabled), 1 to integer(3),
                            2 to InputValue.ListValue(ids.map(::integer)))
                        val before = Thread.currentThread().contextClassLoader
                        val execution = success(request(fixture, values))
                        assertSame(before, Thread.currentThread().contextClassLoader)
                        assertStockParity(fixture, mapOf("enabled" to enabled, "id" to 3L, "ids" to ids), execution)
                        assertEquals(StatementKind.UPDATE, execution.statementKind)
                        assertTrue(execution.sqlWithPlaceholders.contains("SET status"))
                        assertTrue(execution.sqlWithPlaceholders.contains("tenant_id"))
                        assertEquals(if (enabled) 3 else 2,
                            execution.orderedBindings.count { it.origin is PreparedBindingOrigin.CallerInput })
                        val additional = execution.orderedBindings.filter { it.origin is PreparedBindingOrigin.MyBatisAdditional }
                        assertEquals(ids.size * 2, additional.size)
                        assertEquals(List(ids.size) { listOf("item", "idx") }.flatten(),
                            additional.map { (it.origin as PreparedBindingOrigin.MyBatisAdditional).internalBinding.name })
                        assertEquals(contract(fixture).sourceRevisions, execution.sourceRevisions)
                        assertEquals(MaterializationFailureKind.BOUND_EXECUTION_REQUIRED,
                            (MaintainedExecutionMaterializer.materialize(execution) as MaterializationResult.Failed).failure.kind)
                    }
                }
            }
        }
    }

    @Test
    fun deleteSingleWhereMixedSiblingsMatchStockWithProvenTenantPredicate() {
        val parameters = listOf(
            parameter(0, "boolean", "enabled"),
            parameter(1, "long", "id"),
            parameter(2, "java.util.List<java.lang.Long>", "ids"),
        )
        val conditional = """<if test="enabled">AND flag=#{id,jdbcType=BIGINT}</if>"""
        val loop = """<foreach collection="ids" item="item" index="idx" open="AND (id, ord) IN (" separator="," close=")">(#{item,jdbcType=BIGINT},#{idx})</foreach>"""
        for ((open, close) in wrappers()) {
            for (parts in listOf(listOf(conditional, loop), listOf(loop, conditional))) {
                val fixture = fixture(
                    "DELETE FROM t $open AND tenant_id=#{id,jdbcType=BIGINT} ${parts.joinToString(" ")} $close",
                    parameters, StatementKind.DELETE,
                )
                for (enabled in listOf(false, true)) {
                    for (ids in listOf(emptyList<Long>(), listOf(7L, 9L))) {
                        val values = mapOf(
                            0 to InputValue.BooleanValue(enabled),
                            1 to integer(3),
                            2 to InputValue.ListValue(ids.map(::integer)),
                        )
                        val contextLoader = Thread.currentThread().contextClassLoader
                        val execution = success(request(fixture, values))
                        assertSame(contextLoader, Thread.currentThread().contextClassLoader)
                        assertStockParity(fixture, mapOf("enabled" to enabled, "id" to 3L, "ids" to ids), execution)
                        assertEquals(StatementKind.DELETE, execution.statementKind)
                        assertTrue(execution.sqlWithPlaceholders.contains("tenant_id"))
                        assertEquals(if (enabled) 2 else 1, execution.orderedBindings.count { it.origin is PreparedBindingOrigin.CallerInput })
                        val additional = execution.orderedBindings.filter { it.origin is PreparedBindingOrigin.MyBatisAdditional }
                        assertEquals(ids.size * 2, additional.size)
                        assertEquals(
                            List(ids.size) { listOf("item", "idx") }.flatten(),
                            additional.map { (it.origin as PreparedBindingOrigin.MyBatisAdditional).internalBinding.name },
                        )
                        assertEquals(contract(fixture).sourceRevisions, execution.sourceRevisions)
                        assertEquals(
                            MaterializationFailureKind.BOUND_EXECUTION_REQUIRED,
                            (MaintainedExecutionMaterializer.materialize(execution) as MaterializationResult.Failed).failure.kind,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun updateSingleSetMixedSiblingsMatchStockForAllOrdersAndStates() {
        val parameters = listOf(
            parameter(0, "boolean", "enabled"),
            parameter(1, "long", "id"),
            parameter(2, "java.util.List<java.lang.Long>", "ids"),
        )
        val conditional = """<if test="enabled">status=#{id,jdbcType=BIGINT},</if>"""
        val loop = """<foreach collection="ids" item="item" index="idx" separator="," close=",">v=#{item,jdbcType=BIGINT},ord=#{idx}</foreach>"""
        for ((open, close) in setWrappers()) {
            for (parts in listOf(listOf(conditional, loop), listOf(loop, conditional))) {
                val fixture = fixture("UPDATE t $open" + parts.joinToString(" ") + "$close WHERE record_id=#{id}", parameters, StatementKind.UPDATE)
                for (enabled in listOf(false, true)) {
                    for (ids in listOf(emptyList<Long>(), listOf(7L, 9L))) {
                        val values = mapOf(
                            0 to InputValue.BooleanValue(enabled),
                            1 to integer(3),
                            2 to InputValue.ListValue(ids.map(::integer)),
                        )
                        val before = Thread.currentThread().contextClassLoader
                        val execution = success(request(fixture, values))
                        assertSame(before, Thread.currentThread().contextClassLoader)
                        assertStockParity(fixture, mapOf("enabled" to enabled, "id" to 3L, "ids" to ids), execution)
                        assertEquals(StatementKind.UPDATE, execution.statementKind)
                        val caller = execution.orderedBindings.filter { it.origin is PreparedBindingOrigin.CallerInput }
                        val additional = execution.orderedBindings.filter { it.origin is PreparedBindingOrigin.MyBatisAdditional }
                        assertEquals(if (enabled) 2 else 1, caller.size)
                        assertEquals(ids.size * 2, additional.size)
                        assertEquals(
                            List(ids.size) { listOf("item", "idx") }.flatten(),
                            additional.map { (it.origin as PreparedBindingOrigin.MyBatisAdditional).internalBinding.name },
                        )
                        assertEquals(contract(fixture).sourceRevisions, execution.sourceRevisions)
                        assertEquals(
                            MaterializationFailureKind.BOUND_EXECUTION_REQUIRED,
                            (MaintainedExecutionMaterializer.materialize(execution) as MaterializationResult.Failed).failure.kind,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun combinedUpdateWrappersWithOneLoopMatchStockSqlMappingsAndValues() {
        val parameters = listOf(parameter(0, "long", "status"), parameter(1, "java.util.List<java.lang.Long>", "ids"))
        for ((setOpen, setClose) in setWrappers()) {
            for ((whereOpen, whereClose) in wrappers()) {
                for (loopInSet in listOf(false, true)) {
                    val loop = if (loopInSet) {
                        "<foreach collection=\"param2\" item=\"item\" index=\"idx\" separator=\",\">v=#{item,jdbcType=BIGINT},i=#{idx},repeat=#{item},</foreach>"
                    } else {
                        "<foreach collection=\"param2\" item=\"item\" index=\"idx\" open=\"AND id IN (\" close=\")\" separator=\",\">#{item,jdbcType=BIGINT},#{idx},#{item}</foreach>"
                    }
                    val parts = listOf("$setOpen base=#{status}, ${if (loopInSet) loop else ""} $setClose",
                        "$whereOpen AND status=#{status} ${if (loopInSet) "" else loop} $whereClose")
                    for (ordered in listOf(parts, parts.reversed())) {
                        val fixture = fixture("UPDATE t " + ordered.joinToString(" ") + " RETURNING #{status}", parameters, StatementKind.UPDATE)
                        for (ids in listOf(emptyList<Long?>(), listOf(7L), listOf(null, 9L))) {
                            val input = InputValue.ListValue(ids.map { it?.let(::integer) ?: InputValue.NullValue })
                            val before = Thread.currentThread().contextClassLoader
                            val execution = success(request(fixture, mapOf(0 to integer(3), 1 to input)))
                            assertStockParity(fixture, mapOf("status" to 3L, "param2" to ids), execution)
                            assertEquals(3 + ids.size * 3, execution.orderedBindings.size)
                            assertEquals(contract(fixture).sourceRevisions, execution.sourceRevisions)
                            assertTrue(execution.rawInterpolations.isEmpty())
                            for (binding in execution.orderedBindings.filter { it.additionalParameter }) {
                                val origin = binding.origin as PreparedBindingOrigin.MyBatisAdditional
                                assertEquals(InternalBindingKind.ADDITIONAL_PARAMETER, origin.internalBinding.kind)
                                assertEquals(origin.internalBinding.provenance, binding.provenance)
                            }
                            assertEquals(MaterializationFailureKind.BOUND_EXECUTION_REQUIRED,
                                (MaintainedExecutionMaterializer.materialize(execution) as MaterializationResult.Failed).failure.kind)
                            assertSame(before, Thread.currentThread().contextClassLoader)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun combinedWrappersRetainStockCollectionAliasesArrayAndTemporalMapTypes() {
        val date = LocalDate.of(2026, 10, 9)
        val cases = listOf(
            CollectionCase("list", "java.util.List<java.lang.Long>", listValue(4, 6), listOf(4L, 6L)),
            CollectionCase("collection", "java.util.Collection<java.lang.Long>", listValue(4, 6), listOf(4L, 6L)),
            CollectionCase("array", "long[]", InputValue.ArrayValue(listOf(integer(4), integer(6))), longArrayOf(4, 6)),
            CollectionCase("entries", "java.util.Map<java.lang.String,java.time.LocalDate>", InputValue.MapValue(linkedMapOf("a" to InputValue.DateValue(date))), linkedMapOf("a" to date)),
        )
        for ((setOpen, setClose) in setWrappers()) {
            for ((whereOpen, whereClose) in wrappers()) {
                for (loopInSet in listOf(false, true)) {
                    for ((alias, type, input, runtime) in cases) {
                        val loop = "<foreach collection=\"$alias\" item=\"item\" index=\"idx\" separator=\",\">#{idx},#{item}</foreach>"
                        val body = "UPDATE t $setOpen${if (loopInSet) loop else "v=1"}$setClose $whereOpen${if (loopInSet) "id=1" else loop}$whereClose"
                        val fixture = fixture(body, listOf(parameter(0, type, if (alias == "entries") alias else null)), StatementKind.UPDATE)
                        assertStockParity(fixture, mapOf(alias to runtime), success(request(fixture, mapOf(0 to input))))
                    }
                }
            }
        }
    }

    @Test
    fun combinedEmptyWrappersKeepStockOmissionAndEmptySqlRefusal() {
        val parameters = listOf(parameter(0, "java.util.List<java.lang.Long>", "ids"))
        for ((setOpen, setClose) in setWrappers()) {
            for ((whereOpen, whereClose) in wrappers()) {
                for (loopInSet in listOf(false, true)) {
                    val loop = "<foreach collection=\"ids\" item=\"item\" separator=\",\"><![CDATA[#{item},]]></foreach>"
                    val parts = listOf("$setOpen<!-- no static assignment -->${if (loopInSet) loop else ""}$setClose",
                        "$whereOpen${if (loopInSet) "" else loop}$whereClose")
                    for (ordered in listOf(parts, parts.reversed())) {
                        val body = ordered.joinToString(" ")
                        val fixture = fixture("UPDATE t $body", parameters, StatementKind.UPDATE)
                        for (ids in listOf(emptyList<Long>(), listOf(7L, 9L))) {
                            val execution = success(request(fixture, mapOf(0 to listValue(*ids.toLongArray()))))
                            assertStockParity(fixture, mapOf("ids" to ids), execution)
                            if (ids.isEmpty()) {
                                assertEquals("UPDATEt", compact(execution.sqlWithPlaceholders))
                                assertTrue(execution.orderedBindings.isEmpty())
                            }
                        }
                        val empty = fixture(body, parameters, StatementKind.UPDATE)
                        val failure = XmlMapperPreparationEngine.prepare(request(empty, mapOf(0 to listValue()))) as PreparationResult.Failed
                        assertEquals("mybatis-prepared-sql-empty", failure.failure.code)
                    }
                }
            }
        }
    }

    @Test
    fun updateSetForeachPreservesStockTrimmingOrderedBindingsAndLocalProvenance() {
        val loop = "<foreach collection=\"ids\" item=\"item\" index=\"idx\" separator=\",\">v=#{item,jdbcType=BIGINT},seq=#{idx},repeated=#{item}</foreach>"
        for ((open, close) in setWrappers()) {
            val fixture = fixture("UPDATE t $open base=#{status}, $loop, tail=#{status}, $close WHERE id=#{status}",
                listOf(parameter(0, "long", "status"), parameter(1, "java.util.List<java.lang.Long>", "ids")), StatementKind.UPDATE)
            for (ids in listOf(emptyList<Long?>(), listOf(7L), listOf(7L, null, 9L))) {
                val input = InputValue.ListValue(ids.map { it?.let(::integer) ?: InputValue.NullValue })
                val before = Thread.currentThread().contextClassLoader
                val execution = success(request(fixture, mapOf(0 to integer(3), 1 to input)))
                assertStockParity(fixture, mapOf("status" to 3L, "ids" to ids), execution)
                val repeated = ids.flatMapIndexed { index, item ->
                    val value = item?.let(::integer) ?: InputValue.NullValue
                    listOf(value, integer(index.toLong()), value)
                }
                assertEquals(listOf(integer(3)) + repeated + listOf(integer(3), integer(3)), execution.orderedBindings.map { it.value })
                assertEquals(StatementKind.UPDATE, execution.statementKind)
                assertEquals(contract(fixture).sourceRevisions, execution.sourceRevisions)
                assertTrue(execution.rawInterpolations.isEmpty())
                for (binding in execution.orderedBindings.filter { it.additionalParameter }) {
                    val origin = binding.origin as PreparedBindingOrigin.MyBatisAdditional
                    assertEquals(InternalBindingKind.ADDITIONAL_PARAMETER, origin.internalBinding.kind)
                    assertEquals(origin.internalBinding.provenance, binding.provenance)
                }
                val refusal = MaintainedExecutionMaterializer.materialize(execution) as MaterializationResult.Failed
                assertEquals(MaterializationFailureKind.BOUND_EXECUTION_REQUIRED, refusal.failure.kind)
                assertSame(before, Thread.currentThread().contextClassLoader)
            }
        }
    }

    @Test
    fun updateSetForeachRetainsCollectionArrayGenericAndTemporalMapBindings() {
        for ((open, close) in setWrappers()) {
            for ((alias, type, input, runtime) in listOf(
                CollectionCase("list", "java.util.List<java.lang.Long>", listValue(7, 9), listOf(7L, 9L)),
                CollectionCase("collection", "java.util.Collection<java.lang.Long>", listValue(7, 9), listOf(7L, 9L)),
                CollectionCase("array", "long[]", InputValue.ArrayValue(listOf(integer(7), integer(9))), longArrayOf(7, 9)),
            )) {
                val fixture = fixture("UPDATE t $open<foreach collection=\"$alias\" item=\"item\" separator=\",\">v=#{item}</foreach>$close",
                    listOf(parameter(0, type, null)), StatementKind.UPDATE)
                val execution = success(request(fixture, mapOf(0 to input)))
                assertStockParity(fixture, mapOf(alias to runtime), execution)
            }
            val date = LocalDate.of(2026, 10, 9)
            val map = fixture("UPDATE t $open<foreach collection=\"entries\" item=\"item\" index=\"key\" separator=\",\">k=#{key},v=#{item}</foreach>$close",
                listOf(parameter(0, "java.util.Map<java.lang.String,java.time.LocalDate>", "entries")), StatementKind.UPDATE)
            val execution = success(request(map, mapOf(0 to InputValue.MapValue(linkedMapOf("a" to InputValue.DateValue(date))))))
            assertStockParity(map, mapOf("entries" to linkedMapOf("a" to date)), execution)
            assertEquals(listOf(InputValue.Text("a"), InputValue.DateValue(date)), execution.orderedBindings.map { it.value })
            val generic = fixture("UPDATE t $open<foreach collection=\"param2\" item=\"item\">v=#{item},</foreach>$close",
                listOf(parameter(0, "long", "unused"), parameter(1, "java.util.List<java.lang.Long>", "ids")), StatementKind.UPDATE)
            assertStockParity(generic, mapOf("param2" to listOf(7L)), success(request(generic, mapOf(0 to integer(0), 1 to listValue(7)))))
        }
    }

    @Test
    fun emptySetAndTrailingCommaWhitespaceFollowStockWithoutInventingAssignments() {
        for ((open, close) in setWrappers()) {
            for (text in listOf("v=#{item},", "v=#{item},&#10;", "<![CDATA[v=#{item},\n]]>", "v=#{item}, /* suffix */")) {
                val loop = "<foreach collection=\"ids\" item=\"item\" separator=\",\">$text</foreach>"
                val fixture = fixture("UPDATE t $open<!-- ignored -->$loop$close WHERE id=1",
                    listOf(parameter(0, "java.util.List<java.lang.Long>", "ids")), StatementKind.UPDATE)
                for (ids in listOf(emptyList<Long>(), listOf(7L), listOf(7L, 9L))) {
                    val execution = success(request(fixture, mapOf(0 to listValue(*ids.toLongArray()))))
                    assertStockParity(fixture, mapOf("ids" to ids), execution)
                    assertEquals(ids.map(::integer), execution.orderedBindings.map { it.value })
                    if (ids.isEmpty()) {
                        assertEquals("UPDATEtWHEREid=1", compact(execution.sqlWithPlaceholders))
                        assertTrue(execution.orderedBindings.isEmpty())
                    }
                }
                val empty = fixture("$open$loop$close", fixture.mapper.parameters, StatementKind.UPDATE)
                val failure = XmlMapperPreparationEngine.prepare(request(empty, mapOf(0 to listValue()))) as PreparationResult.Failed
                assertEquals("mybatis-prepared-sql-empty", failure.failure.code)
            }
        }
    }

    @Test
    fun setForeachTokenTopologyRefusesSynthesisAndEscapingEvenForEmptyCollections() {
        for ((open, close) in setWrappers()) {
            for (body in listOf(
                "UPDATE t $open<foreach collection=\"ids\" item=\"item\" open=\"#{\">item}</foreach>$close",
                "UPDATE t $open<foreach collection=\"ids\" item=\"item\" separator=\"#\">v=#{item}</foreach>$close",
                "UPDATE t $open<foreach collection=\"ids\" item=\"item\" close=\"\\\">v=#{item}</foreach>$close",
                "UPDATE t #$open<foreach collection=\"ids\" item=\"item\">{item}</foreach>$close",
            )) {
                val fixture = fixture(body, listOf(parameter(0, "java.util.List<java.lang.Long>", "ids")), StatementKind.UPDATE)
                for (items in listOf(listValue(), listValue(7, 9))) {
                    val before = Thread.currentThread().contextClassLoader
                    val failure = XmlMapperPreparationEngine.prepare(request(fixture, mapOf(0 to items))) as PreparationResult.Failed
                    assertEquals(body, "xml-foreach-bound-token-topology-unsupported", failure.failure.code)
                    assertSame(before, Thread.currentThread().contextClassLoader)
                }
            }
        }
    }

    @Test
    fun wrappedForeachPreservesStockSqlOrderMetadataAndValues() {
        val loop = "<foreach collection=\"ids\" item=\"item\" index=\"idx\" open=\"AND id IN (\" close=\")\" separator=\",\">#{idx},#{item,jdbcType=BIGINT},#{item}</foreach>"
        for ((open, close) in wrappers()) {
            for (kind in StatementKind.entries) {
                val fixture = fixture("SELECT #{status} $open AND status = #{status} $loop $close RETURNING #{status}",
                    listOf(parameter(0, "long", "status"), parameter(1, "java.util.List<java.lang.Long>", "ids")), kind)
                for (ids in listOf(emptyList(), listOf(7L), listOf(7L, 9L))) {
                    val before = Thread.currentThread().contextClassLoader
                    val execution = success(request(fixture, mapOf(0 to integer(3), 1 to listValue(*ids.toLongArray()))))
                    assertStockParity(fixture, mapOf("status" to 3L, "ids" to ids), execution)
                    assertEquals(listOf(integer(3), integer(3)) + ids.flatMapIndexed { index, item -> listOf(integer(index.toLong()), integer(item), integer(item)) } + integer(3), execution.orderedBindings.map { it.value })
                    assertEquals(kind, execution.statementKind)
                    assertEquals(contract(fixture).sourceRevisions, execution.sourceRevisions)
                    assertTrue(execution.rawInterpolations.isEmpty())
                    for (binding in execution.orderedBindings.filter { it.additionalParameter }) {
                        val origin = binding.origin as PreparedBindingOrigin.MyBatisAdditional
                        assertEquals(InternalBindingKind.ADDITIONAL_PARAMETER, origin.internalBinding.kind)
                        assertEquals(origin.internalBinding.provenance, binding.provenance)
                    }
                    assertEquals(MaterializationFailureKind.BOUND_EXECUTION_REQUIRED, (MaintainedExecutionMaterializer.materialize(execution) as MaterializationResult.Failed).failure.kind)
                    assertSame(before, Thread.currentThread().contextClassLoader)
                }
            }
        }
    }

    @Test
    fun wrappedEmptyCollectionsNullItemsAndWhitespaceRemainStockSemantics() {
        for ((open, close) in wrappers()) {
            for (loopOpen in listOf("AND id IN (", "AND\nid IN (", "AND&#10;id IN (", "ANDROID IN (")) {
                val fixture = fixture("SELECT 1 $open<!-- ignored --><foreach collection=\"ids\" item=\"item\" open=\"$loopOpen\" close=\")\" separator=\",\"><![CDATA[#{item}]]></foreach>$close", listOf(parameter(0, "java.util.List<java.lang.Long>", "ids")))
                for (ids in listOf(emptyList<Long?>(), listOf(7L), listOf(null, 9L))) {
                    val input = InputValue.ListValue(ids.map { it?.let(::integer) ?: InputValue.NullValue })
                    val execution = success(request(fixture, mapOf(0 to input)))
                    assertStockParity(fixture, mapOf("ids" to ids), execution)
                    assertEquals(input.elements, execution.orderedBindings.map { it.value })
                    if (ids.isEmpty()) {
                        assertEquals("SELECT1", compact(execution.sqlWithPlaceholders))
                        assertTrue(execution.orderedBindings.isEmpty())
                    }
                }
            }
            val only = fixture("$open<foreach collection=\"ids\" item=\"item\">#{item}</foreach>$close", listOf(parameter(0, "java.util.List<java.lang.Long>", "ids")))
            val empty = XmlMapperPreparationEngine.prepare(request(only, mapOf(0 to listValue()))) as PreparationResult.Failed
            assertEquals("mybatis-prepared-sql-empty", empty.failure.code)
        }
    }

    @Test
    fun wrappedStockAliasesArraysAndMapKeysRetainRuntimeTypes() {
        data class CollectionCase(val alias: String, val type: String, val input: InputValue, val runtime: Any)
        for ((open, close) in wrappers()) {
            for ((alias, type, input, runtime) in listOf(
                CollectionCase("list", "java.util.List<java.lang.Long>", listValue(4, 6), listOf(4L, 6L)),
                CollectionCase("collection", "java.util.Collection<java.lang.Long>", listValue(4, 6), listOf(4L, 6L)),
                CollectionCase("array", "long[]", InputValue.ArrayValue(listOf(integer(4), integer(6))), longArrayOf(4, 6)),
            )) {
                val fixture = fixture("SELECT 1 $open<foreach collection=\"$alias\" item=\"item\" open=\"AND id IN (\" close=\")\" separator=\",\">#{item}</foreach>$close", listOf(parameter(0, type, null)))
                assertStockParity(fixture, mapOf(alias to runtime), success(request(fixture, mapOf(0 to input))))
            }
            val date = LocalDate.of(2026, 10, 9)
            val fixture = fixture("SELECT 1 $open<foreach collection=\"entries\" item=\"item\" index=\"key\" separator=\",\">#{key},#{item}</foreach>$close", listOf(parameter(0, "java.util.Map<java.lang.String,java.time.LocalDate>", "entries")))
            val execution = success(request(fixture, mapOf(0 to InputValue.MapValue(linkedMapOf("a" to InputValue.DateValue(date))))))
            assertStockParity(fixture, mapOf("entries" to linkedMapOf("a" to date)), execution)
            assertEquals(listOf(InputValue.Text("a"), InputValue.DateValue(date)), execution.orderedBindings.map { it.value })
            val generic = fixture("SELECT 1 $open<foreach collection=\"param2\" item=\"item\">#{item}</foreach>$close", listOf(parameter(0, "long", "unused"), parameter(1, "java.util.List<java.lang.Long>", "ids")))
            assertStockParity(generic, mapOf("param2" to listOf(7L)), success(request(generic, mapOf(0 to integer(0), 1 to listValue(7)))))
        }
    }

    @Test
    fun foreachTokenSynthesisAndEscapingAreRefusedEvenForEmptyCollections() {
        for ((open, close) in wrappers() + ("" to "")) {
            for (body in listOf(
                "SELECT $open<foreach collection=\"ids\" item=\"item\" open=\"#{\">item}</foreach>$close",
                "SELECT $open<foreach collection=\"ids\" item=\"item\" separator=\"#\">#{item}</foreach>$close",
                "SELECT $open<foreach collection=\"ids\" item=\"item\" close=\"\\\">#{item}</foreach>$close",
                "SELECT #$open<foreach collection=\"ids\" item=\"item\">{item}</foreach>$close",
            )) {
                val fixture = fixture(body, listOf(parameter(0, "java.util.List<java.lang.Long>", "ids")))
                for (input in listOf(listValue(), listValue(7, 9))) {
                    val before = Thread.currentThread().contextClassLoader
                    val failure = XmlMapperPreparationEngine.prepare(request(fixture, mapOf(0 to input))) as PreparationResult.Failed
                    assertEquals(body, PreparationFailureKind.UNSUPPORTED_SEMANTIC, failure.failure.kind)
                    assertEquals(body, "xml-foreach-bound-token-topology-unsupported", failure.failure.code)
                    assertSame(before, Thread.currentThread().contextClassLoader)
                }
            }
        }
    }

    @Test
    fun topologyChecksOnlyProjectedReachableContentAcrossCapturedSnapshots() {
        for ((open, close) in wrappers() + ("" to "")) {
            val original = fixture("SELECT 1 $open<foreach collection=\"ids\" item=\"item\">#{item}</foreach>$close", listOf(parameter(0, "java.util.List<java.lang.Long>", "ids")))
            val other = original.graph.sourceSnapshots.single().copy(
                fileId = SourceFileId("vfs:/Other.xml"),
                content = original.graph.sourceSnapshots.single().content.replace("example.Mapper", "example.Other"),
            )
            val graph = StatementSourceGraph(original.graph.rootStatement, original.graph.sourceSnapshots + other, emptyList())
            val captured = original.copy(graph = graph)
            val prepared = success(request(captured, mapOf(0 to listValue(7))))
            assertEquals(contract(captured).sourceRevisions, prepared.sourceRevisions)
            assertEquals(listOf(integer(7)), prepared.orderedBindings.map { it.value })

            val unsafeUnreachable = other.copy(content = other.content.replace("#{item}", "\\#{item}"))
            val drifted = original.copy(
                graph = StatementSourceGraph(
                    original.graph.rootStatement,
                    original.graph.sourceSnapshots + unsafeUnreachable,
                    emptyList(),
                ),
            )
            val stillPrepared = success(request(drifted, mapOf(0 to listValue(7))))
            assertEquals(contract(drifted).sourceRevisions, stillPrepared.sourceRevisions)
            assertEquals(listOf(integer(7)), stillPrepared.orderedBindings.map { it.value })
        }
    }

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
            listOf("idx", "item", "item", "idx", "item", "item"),
            execution.orderedBindings.map { it.property },
        )
        for (binding in execution.orderedBindings) {
            val origin = binding.origin as PreparedBindingOrigin.MyBatisAdditional
            assertEquals(InternalBindingKind.ADDITIONAL_PARAMETER, origin.internalBinding.kind)
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
        assertEquals(
            InternalBindingKind.ADDITIONAL_PARAMETER,
            (execution.orderedBindings[0].origin as PreparedBindingOrigin.MyBatisAdditional).internalBinding.kind,
        )
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
        val nullableEnvironment = InputEnvironment.validate(
            contract(fixture),
            listOf(ProvidedInput(contract(fixture).requirements.single().id, InputValue.NullValue, ExecutionInputOrigin.USER_ENTERED)),
        )
        assertTrue(nullableEnvironment is InputEnvironmentResult.Success)
        val nullCollection = XmlMapperPreparationEngine.prepare(
            request(fixture, mapOf(0 to InputValue.NullValue)),
        ) as PreparationResult.Failed
        assertEquals(PreparationFailureKind.MYBATIS_PARSE, nullCollection.failure.kind)
        assertEquals("mybatis-xml-mapper-parse-failure", nullCollection.failure.code)
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
            val direct = request(fixture, mapOf(0 to listValue(7, 9)))
            val wrapped = wrappers().map { (open, close) ->
                val wrappedFixture = fixture("SELECT 1 $open<foreach collection=\"ids\" item=\"item\" separator=\",\">#{item}</foreach>$close", fixture.mapper.parameters)
                request(wrappedFixture, mapOf(0 to listValue(7, 9)))
            }
            val set = setWrappers().map { (open, close) ->
                val setFixture = fixture("UPDATE t $open<foreach collection=\"ids\" item=\"item\" separator=\",\">v=#{item}</foreach>$close", fixture.mapper.parameters, StatementKind.UPDATE)
                request(setFixture, mapOf(0 to listValue(7, 9)))
            }
            val combined = setWrappers().flatMap { (setOpen, setClose) ->
                wrappers().flatMap { (whereOpen, whereClose) ->
                    listOf(false, true).map { loopInSet ->
                        val loop = "<foreach collection=\"ids\" item=\"item\" separator=\",\">#{item}</foreach>"
                        val combinedFixture = fixture("UPDATE t $setOpen${if (loopInSet) loop else "v=1"}$setClose $whereOpen${if (loopInSet) "id=1" else loop}$whereClose", fixture.mapper.parameters, StatementKind.UPDATE)
                        request(combinedFixture, mapOf(0 to listValue(7, 9)))
                    }
                }
            }
            val requests = listOf(direct) + wrapped + set + combined
            Executors.newFixedThreadPool(4).use { executor ->
                val futures = executor.invokeAll((0 until requests.size * 2).map { index ->
                    Callable {
                        val thread = Thread.currentThread()
                        val contextLoader = thread.contextClassLoader
                        val prepared = success(requests[index % requests.size])
                        assertSame(contextLoader, thread.contextClassLoader)
                        assertEquals(listOf(integer(7), integer(9)), prepared.orderedBindings.map { it.value })
                        prepared
                    }
                }, 60, TimeUnit.SECONDS)
                val executions = futures.map { it.get(1, TimeUnit.SECONDS) }
                for (index in executions.indices) assertEquals(executions[index % requests.size], executions[index])
                assertEquals(listOf("item", "item"), executions.first().orderedBindings.map { it.property })
            }
            assertSame(poison, get.invoke(null, contextMap))
        } finally {
            set.invoke(null, contextMap, original)
        }
    }

    private fun setWrappers() = listOf(
        "<set>" to "</set>",
        "<trim prefix=\"SET\" suffixOverrides=\",\">" to "</trim>",
        "<trim suffixOverrides=\"&#44;\" prefix=\"&#83;ET\">" to "</trim>",
    )

    private fun wrappers() = listOf(
        "<where>" to "</where>",
        "<trim prefix=\"WHERE\" prefixOverrides=\"AND |OR \">" to "</trim>",
        "<trim prefixOverrides=\"AND &#124;OR \" prefix=\"&#87;HERE\">" to "</trim>",
    )

    private fun assertStockParity(fixture: Fixture, values: Map<String, Any?>, execution: PreparedExecution) {
        val configuration = Configuration()
        ByteArrayInputStream(fixture.graph.sourceSnapshots.single().content.toByteArray(Charsets.UTF_8)).use {
            XMLMapperBuilder(it, configuration, "stock-wrapped-foreach-oracle", configuration.sqlFragments).parse()
        }
        val stock = configuration.getMappedStatement("example.Mapper.find").getBoundSql(values)
        assertEquals(stock.sql, execution.sqlWithPlaceholders)
        assertEquals(stock.parameterMappings.map { it.property }, execution.orderedBindings.map { it.property })
        stock.parameterMappings.zip(execution.orderedBindings).forEach { (mapping, binding) ->
            val metadata = stockEffectiveParameterMappingMetadata(configuration, stock, values, mapping)
            assertEquals(metadata.javaTypeIdentity, binding.metadata.mappingJavaTypeIdentity)
            assertEquals(metadata.jdbcTypeIdentity, binding.metadata.jdbcTypeIdentity)
            assertEquals(metadata.typeHandlerIdentity, binding.metadata.typeHandlerIdentity)
            assertEquals(metadata.parameterMode, binding.metadata.parameterMode)
            assertEquals(metadata.numericScale, binding.metadata.numericScale)
            val runtime = metadata.runtimeValue
            val value = when (runtime) {
                null -> InputValue.NullValue
                is Long -> integer(runtime)
                is Int -> integer(runtime.toLong())
                is String -> InputValue.Text(runtime)
                is LocalDate -> InputValue.DateValue(runtime)
                else -> throw AssertionError("Unexpected stock oracle value type")
            }
            assertEquals(value, binding.value)
        }
    }

    private fun success(request: MyBatisPreparationRequest): PreparedExecution = when (val result = XmlMapperPreparationEngine.prepare(request)) {
        is PreparationResult.Success -> result.execution
        is PreparationResult.Failed -> throw AssertionError("${result.failure.kind}: ${result.failure.code} (${result.failure.diagnosticType})")
    }

    private fun request(fixture: Fixture, values: Map<Int, InputValue>, contract: ParameterContract = contract(fixture), includeMapperCapture: Boolean = true): MyBatisPreparationRequest {
        val provided = contract.requirements.map { requirement ->
            val index = requirement.provenance.evidence.filterIsInstance<InputEvidence.MapperMethodParameter>().first().index
            ProvidedInput(requirement.id, values.getValue(index), ExecutionInputOrigin.USER_ENTERED)
        }
        val validated = InputEnvironment.validate(contract, provided)
        assertTrue("Input validation: $validated; contract problems: ${contract.blockingProblems}", validated is InputEnvironmentResult.Success)
        val environment = (validated as InputEnvironmentResult.Success).environment
        return (MyBatisPreparationRequest.create(
            if (includeMapperCapture) XmlMapperPreparationSource(fixture.graph, mapperMethod = fixture.mapper)
            else XmlMapperPreparationSource(fixture.graph, additionalAuthoritySnapshots = listOf(fixture.mapper.mapperSource)),
            contract, environment,
        ) as PreparationRequestResult.Ready).request
    }

    private fun contract(fixture: Fixture): ParameterContract {
        val source = XmlMapperPreparationSource(fixture.graph, mapperMethod = fixture.mapper)
        return MyBatisInvocationContractFactory.build(source)
    }

    private fun fixture(body: String, parameters: List<JavaMethodParameterMetadata>, kind: StatementKind = StatementKind.SELECT): Fixture {
        val file = SourceFileId("vfs:/foreach.xml")
        val id = XmlStatementId(file, "example.Mapper", "find")
        val element = kind.name.lowercase()
        val xml = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
            <mapper namespace="example.Mapper"><$element id="find">$body</$element></mapper>
        """.trimIndent().trimStart()
        val graph = StatementSourceGraph(
            CapturedStatement(id, kind, SourceRange(0, xml.length)),
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
    private data class CollectionCase(val alias: String, val type: String, val input: InputValue, val runtime: Any)
    private data class Fixture(val graph: StatementSourceGraph, val mapper: XmlMapperMethodCapture)
}
