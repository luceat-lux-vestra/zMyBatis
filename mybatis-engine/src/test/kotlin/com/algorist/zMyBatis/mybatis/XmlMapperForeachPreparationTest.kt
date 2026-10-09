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
    fun selectWhereMixedSiblingsRefuseIncompleteMapperAndSourceDriftEvenIfFalseAndEmpty() {
        val parameters = listOf(
            parameter(0, "boolean", "enabled"),
            parameter(1, "long", "id"),
            parameter(2, "java.util.List<java.lang.Long>", "ids"),
        )
        val loop = """<foreach collection="ids" item="item">#{item}</foreach>"""
        val condition = """<if test="enabled">AND flag=#{id}</if>"""
        val body = "SELECT #{id} <where>$condition $loop</where>"
        val original = fixture(body, parameters)
        val values = mapOf(
            0 to InputValue.BooleanValue(false),
            1 to integer(3),
            2 to listValue(),
        )
        val baseline = contract(original)
        val missingMapper = XmlMapperPreparationEngine.prepare(
            request(original, values, includeMapperCapture = false),
        ) as PreparationResult.Failed
        assertEquals("xml-foreach-preparation-mapper-authority-unproven", missingMapper.failure.code)
        for (changed in listOf(
            body.replace(loop, "$loop$loop"),
            body.replace(loop, """<foreach collection="ids" item="item"><if test="enabled">#{item}</if></foreach>"""),
            body.replace(condition, """<if test="enabled">$loop</if>"""),
            body.replace(loop, """<foreach collection="ids" item="item" nullable="true">#{item}</foreach>"""),
            body.replace(condition, """<if test="enabled != null">AND flag=#{id}</if>"""),
        )) {
            val changedFixture = fixture(changed, parameters)
            val outcome = XmlMapperPreparationEngine.prepare(request(changedFixture, values, baseline))
            assertTrue("Unproven mixed source was prepared: $changed", outcome is PreparationResult.Failed)
        }
        val moved = fixture(body.replace("AND flag=#{id}", "AND flag=1"), parameters)
        assertTrue(XmlMapperPreparationEngine.prepare(request(moved, values, baseline)) is PreparationResult.Failed)

        // A SELECT-only island must not authorize mixed dynamics in UPDATE SET/WHERE.
        val update = fixture("UPDATE t <set>v=#{id},</set><where>$condition $loop</where>", parameters, StatementKind.UPDATE)
        assertTrue(contract(update).isPreparationBlocked)
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
    fun deleteWhereMixedRefusesMissingMapperAndInactiveUnprovenSourceDrift() {
        val parameters = listOf(
            parameter(0, "boolean", "enabled"),
            parameter(1, "long", "id"),
            parameter(2, "java.util.List<java.lang.Long>", "ids"),
        )
        val condition = """<if test="enabled">AND flag=#{id}</if>"""
        val loop = """<foreach collection="ids" item="item">#{item}</foreach>"""
        val body = "DELETE FROM t <where>AND tenant_id=#{id} $condition $loop</where>"
        val original = fixture(body, parameters, StatementKind.DELETE)
        val values = mapOf(0 to InputValue.BooleanValue(false), 1 to integer(3), 2 to listValue())
        val baseline = contract(original)
        val noCapture = XmlMapperPreparationEngine.prepare(
            request(original, values, includeMapperCapture = false),
        ) as PreparationResult.Failed
        assertEquals("xml-foreach-preparation-mapper-authority-unproven", noCapture.failure.code)
        for (changed in listOf(
            body.replace(loop, "$loop$loop"),
            body.replace(loop, """<foreach collection="ids" item="item"><if test="enabled">#{item}</if></foreach>"""),
            body.replace(condition, """<if test="enabled">$loop</if>"""),
            body.replace(condition, """<if test="enabled != null">AND flag=#{id}</if>"""),
            body.replace(condition, """<if test="enabled">${'$'}{table}</if>"""),
            body.replace("<where>", """<where bogus="x">"""),
            body.replace(loop, """<foreach collection="ids" item="item" nullable="true">#{item}</foreach>"""),
            "$body<where>AND tenant_id=#{id}</where>",
            body.replace(loop, """<include refid="unknown"/>"""),
        )) {
            val drifted = fixture(changed, parameters, StatementKind.DELETE)
            assertTrue(
                "DELETE source drift was prepared: $changed",
                XmlMapperPreparationEngine.prepare(request(drifted, values, baseline)) is PreparationResult.Failed,
            )
        }
        val noCollection = fixture(body, parameters.dropLast(1), StatementKind.DELETE)
        assertTrue(contract(noCollection).isPreparationBlocked)
        val altered = fixture(body.replace("tenant_id=#{id}", "tenant_id=3"), parameters, StatementKind.DELETE)
        assertTrue(XmlMapperPreparationEngine.prepare(request(altered, values, baseline)) is PreparationResult.Failed)
    }
}{table}</if>"""),
            body.replace("<where>", """<where bogus="x">"""),
            body.replace(loop, """<foreach collection="ids" item="item" nullable="true">#{item}</foreach>"""),
            "$body<where>AND tenant_id=#{id}</where>",
            body.replace(loop, """<include refid="unknown"/>"""),
        )) {
            val drifted = fixture(changed, parameters, StatementKind.DELETE)
            assertTrue(
                "DELETE source drift was prepared: $changed",
                XmlMapperPreparationEngine.prepare(request(drifted, values, baseline)) is PreparationResult.Failed,
            )
        }
        val noCollection = fixture(body, parameters.dropLast(1), StatementKind.DELETE)
        assertTrue(contract(noCollection).isPreparationBlocked)
        val altered = fixture(body.replace("tenant_id=#{id}", "tenant_id=3"), parameters, StatementKind.DELETE)
        assertTrue(XmlMapperPreparationEngine.prepare(request(altered, values, baseline)) is PreparationResult.Failed)
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
    fun updateSingleSetMixedSiblingsRefuseMapperSourceAndSecondRoleDriftEvenWhenInactive() {
        val parameters = listOf(
            parameter(0, "boolean", "enabled"),
            parameter(1, "long", "id"),
            parameter(2, "java.util.List<java.lang.Long>", "ids"),
        )
        val loop = """<foreach collection="ids" item="item">v=#{item},</foreach>"""
        val condition = """<if test="enabled">status=#{id},</if>"""
        val body = "UPDATE t <set>$condition $loop</set> WHERE record_id=#{id}"
        val fixture = fixture(body, parameters, StatementKind.UPDATE)
        val values = mapOf(0 to InputValue.BooleanValue(false), 1 to integer(3), 2 to listValue())
        val baseline = contract(fixture)
        val missingMapper = XmlMapperPreparationEngine.prepare(
            request(fixture, values, includeMapperCapture = false),
        ) as PreparationResult.Failed
        assertEquals("xml-foreach-preparation-mapper-authority-unproven", missingMapper.failure.code)
        for (changed in listOf(
            body.replace(loop, "$loop$loop"),
            body.replace(loop, """<foreach collection="ids" item="item"><if test="enabled">v=#{item}</if></foreach>"""),
            body.replace(condition, """<if test="enabled">$loop</if>"""),
            body.replace(condition, """<if test="enabled != null">status=#{id},</if>"""),
            body.replace(condition, """<if test="enabled">${'$'}{table}</if>"""),
            body.replace("<set>", """<set extra="x">"""),
            body.replace(loop, """<foreach collection="ids" item="item" nullable="true">v=#{item},</foreach>"""),
            "UPDATE t <set>$condition $loop</set><where>AND record_id=#{id}</where>",
            "UPDATE t <where>AND record_id=#{id}</where><set>$condition $loop</set>",
            "UPDATE t <where>$condition $loop</where><set>v=#{id},</set>",
            "UPDATE t <where>$condition $loop</where>",
        )) {
            val changedFixture = fixture(changed, parameters, StatementKind.UPDATE)
            val result = XmlMapperPreparationEngine.prepare(request(changedFixture, values, baseline))
            assertTrue("Unsupported UPDATE mixed source was prepared: $changed", result is PreparationResult.Failed)
        }
        val unprovenMapper = fixture(body, parameters.dropLast(1), StatementKind.UPDATE)
        assertTrue(contract(unprovenMapper).isPreparationBlocked)
    }
    @Test
    fun falseBooleanSiblingCannotHideUnsupportedNestedForeachSource() {
        val parameters = listOf(
            parameter(0, "boolean", "enabled"),
            parameter(1, "long", "id"),
            parameter(2, "java.util.List<java.lang.Long>", "ids"),
        )
        val supported = fixture(
            "SELECT #{id} <if test=\"enabled\"> + #{id}</if> " +
                "<foreach collection=\"ids\" item=\"item\" separator=\",\">#{item}</foreach>",
            parameters,
        )
        val authentic = contract(supported)
        val changed = fixture(
            "SELECT #{id} <if test=\"enabled\">" +
                "<foreach collection=\"ids\" item=\"item\">#{item}</foreach></if>",
            parameters,
        )

        val failure = XmlMapperPreparationEngine.prepare(
            request(
                changed,
                mapOf(
                    0 to InputValue.BooleanValue(false),
                    1 to integer(3),
                    2 to listValue(7, 9),
                ),
                authentic,
            ),
        ) as PreparationResult.Failed

        assertEquals(PreparationFailureKind.UNSUPPORTED_SEMANTIC, failure.failure.kind)
        assertEquals("xml-foreach-preparation-source-unsupported", failure.failure.code)
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
                            val locals = contract(fixture).internalBindings.associateBy { it.name }
                            for (binding in execution.orderedBindings.filter { it.additionalParameter }) {
                                val origin = binding.origin as PreparedBindingOrigin.MyBatisAdditional
                                assertEquals(locals.getValue(origin.internalBinding.name), origin.internalBinding)
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
    fun combinedWrappersRefuseMissingMapperUnsupportedSourceAndCrossWrapperTokens() {
        val parameters = listOf(parameter(0, "long", "status"), parameter(1, "java.util.List<java.lang.Long>", "ids"))
        val loop = "<foreach collection=\"ids\" item=\"item\">v=#{item},</foreach>"
        val body = "UPDATE t <set>$loop</set><where>AND id=#{status}</where>"
        val original = fixture(body, parameters, StatementKind.UPDATE)
        for (items in listOf(listValue(), listValue(7, 9))) {
            val values = mapOf(0 to integer(3), 1 to items)
            val missing = XmlMapperPreparationEngine.prepare(request(original, values, includeMapperCapture = false)) as PreparationResult.Failed
            assertEquals("xml-foreach-preparation-mapper-authority-unproven", missing.failure.code)
            for (changed in listOf(
                body.replace("AND id=#{status}", loop),
                body.replace("AND id=#{status}", "<if test=\"enabled\">AND id=#{status}</if>"),
                body.replace("AND id=#{status}", "AND id=#{item}"),
                "$body<set>v=1</set>",
                body.replace("<set>", "<set bogus=\"x\">"),
                body.replace("<where>", "<trim prefix=\"WHERE\" prefixOverrides=\"AND|OR\">").replace("</where>", "</trim>"),
                body.replace("AND id=#{status}", "<bind name=\"x\" value=\"1\"/>"),
            )) {
                val changedFixture = fixture(changed, parameters, StatementKind.UPDATE)
                val failure = XmlMapperPreparationEngine.prepare(request(changedFixture, values, contract(original))) as PreparationResult.Failed
                assertEquals(changed, "xml-foreach-preparation-source-unsupported", failure.failure.code)
            }
            for (changed in listOf(
                "UPDATE t #<set>$loop</set><where>{status}</where>",
                "UPDATE t <set>$loop#</set><where>{status}</where>",
                "UPDATE t <where>AND id=#{status}#</where><set>$loop</set>",
                "UPDATE t <set>v=#{status},</set><where><foreach collection=\"ids\" item=\"item\" open=\"#{\">item}</foreach></where>",
            )) {
                val changedFixture = fixture(changed, parameters, StatementKind.UPDATE)
                val before = Thread.currentThread().contextClassLoader
                val failure = XmlMapperPreparationEngine.prepare(request(changedFixture, values)) as PreparationResult.Failed
                assertEquals(changed, "xml-foreach-bound-token-topology-unsupported", failure.failure.code)
                assertSame(before, Thread.currentThread().contextClassLoader)
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
                val locals = contract(fixture).internalBindings.associateBy { it.name }
                for (binding in execution.orderedBindings.filter { it.additionalParameter }) {
                    val origin = binding.origin as PreparedBindingOrigin.MyBatisAdditional
                    assertEquals(locals.getValue(origin.internalBinding.name), origin.internalBinding)
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
            assertStockParity(generic, mapOf("param2" to listOf(7L)), success(request(generic, mapOf(1 to listValue(7)))))
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
    fun setForeachRequiresCompleteMapperAndRejectsSourceDriftBeforeRuntime() {
        val loop = "<foreach collection=\"ids\" item=\"item\">v=#{item},</foreach>"
        for ((open, close) in setWrappers()) {
            val body = "UPDATE t $open$loop$close WHERE id=1"
            val original = fixture(body, listOf(parameter(0, "java.util.List<java.lang.Long>", "ids")), StatementKind.UPDATE)
            for (items in listOf(listValue(), listValue(7))) {
                val missing = XmlMapperPreparationEngine.prepare(request(original, mapOf(0 to items), includeMapperCapture = false)) as PreparationResult.Failed
                assertEquals("xml-foreach-preparation-mapper-authority-unproven", missing.failure.code)
                for (changed in listOf(
                    body.replace(loop, "$loop$loop"),
                    "$body<where><if test=\"enabled\">AND 1=1</if></where>",
                    body.replace(open, if (open == "<set>") "<set bogus=\"x\">" else "<trim prefix=\"SET\" suffixOverrides=\";\">"),
                )) {
                    val drifted = fixture(changed, original.mapper.parameters, StatementKind.UPDATE)
                    val failure = XmlMapperPreparationEngine.prepare(request(drifted, mapOf(0 to items), contract(original))) as PreparationResult.Failed
                    assertEquals(changed, "xml-foreach-preparation-source-unsupported", failure.failure.code)
                }
            }
            val raw = fixture(body.replace(loop, "<foreach collection=\"ids\" item=\"item\" open=\"${'$'}{ids}\">v=#{item},</foreach>"), original.mapper.parameters, StatementKind.UPDATE)
            val failure = XmlMapperPreparationEngine.prepare(request(raw, mapOf(0 to listValue(7)))) as PreparationResult.Failed
            assertEquals("xml-preparation-raw-input-unsupported", failure.failure.code)
        }
    }

    @Test
    fun strippingEveryForeachRequirementCannotSkipAdmissionEvenForAnEmptyLoop() {
        val fixture = fixture("SELECT 1 <foreach collection=\"ids\" item=\"item\">#{item}</foreach>", listOf(parameter(0, "java.util.List<java.lang.Long>", "ids")))
        val original = contract(fixture)
        val stripped = ParameterContract(original.statementId, emptyList(), emptyList(), emptyList(), emptyList(), original.sourceRevisions)
        val prepared = XmlMapperPreparationEngine.prepare(request(fixture, emptyMap(), stripped))
        assertTrue(prepared is PreparationResult.Failed)
        assertEquals("xml-foreach-preparation-source-contract-mismatch", (prepared as PreparationResult.Failed).failure.code)
    }

    @Test
    fun missingCompleteMapperCaptureRefusesForeachEvenWithMatchingSourceRevision() {
        val fixture = fixture("SELECT <foreach collection=\"ids\" item=\"item\">#{item}</foreach>", listOf(parameter(0, "java.util.List<java.lang.Long>", "ids")))
        for (items in listOf(listValue(), listValue(7))) {
            val prepared = XmlMapperPreparationEngine.prepare(request(fixture, mapOf(0 to items), includeMapperCapture = false))
            assertTrue(prepared is PreparationResult.Failed)
            assertEquals("xml-foreach-preparation-mapper-authority-unproven", (prepared as PreparationResult.Failed).failure.code)
        }
    }

    @Test
    fun unusedMapperParametersCanSuppressCollectionAliasesOrShadowLocals() {
        for ((open, close) in wrappers() + ("" to "")) {
            val fixture = fixture("SELECT 1 $open<foreach collection=\"param1\" item=\"item\">#{item}</foreach>$close", listOf(parameter(0, "java.util.List<java.lang.Long>", "ids")))
            for (alias in listOf("param1", "item")) {
                val mapper = XmlMapperMethodCapture(fixture.mapper.statementId, fixture.mapper.mapperSource, fixture.mapper.methodSourceRange,
                    fixture.mapper.parameters + parameter(1, "long", alias))
                val changed = fixture.copy(mapper = mapper)
                assertTrue(contract(changed).isPreparationBlocked)
                for (items in listOf(listValue(), listValue(7))) {
                    val prepared = XmlMapperPreparationEngine.prepare(request(changed, mapOf(0 to items), contract(fixture)))
                    assertTrue(prepared is PreparationResult.Failed)
                    assertEquals("xml-foreach-preparation-mapper-unsupported", (prepared as PreparationResult.Failed).failure.code)
                }
            }
        }
    }

    @Test
    fun coherentForgedMapperEvidenceCannotAuthorizeCollectionOrOutsideCallerValues() {
        val body = "SELECT #{status}, <foreach collection=\"param2\" item=\"item\">#{item}</foreach>, #{status}"
        val fixture = fixture(body, listOf(parameter(0, "long", "status"), parameter(1, "java.util.List<java.lang.Long>", "ids")))
        val variants = listOf(
            listOf(parameter(0, "java.lang.String", "status"), parameter(1, "java.util.List<java.lang.Long>", "ids")),
            listOf(parameter(0, "long", "status"), parameter(1, "java.util.List<java.lang.String>", "ids")),
            listOf(parameter(0, "long", "status"), parameter(1, "java.util.List<java.lang.Long>", "param2")),
        )
        for ((index, parameters) in variants.withIndex()) {
            val forged = contract(fixture(body, parameters))
            assertTrue(!forged.isPreparationBlocked)
            val values = mapOf(0 to if (index == 0) InputValue.Text("different type") else integer(3),
                1 to if (index == 1) InputValue.ListValue(listOf(InputValue.Text("different element type"))) else listValue(7))
            val prepared = XmlMapperPreparationEngine.prepare(request(fixture, values, forged))
            assertTrue(prepared is PreparationResult.Failed)
            assertEquals("xml-foreach-preparation-source-contract-mismatch", (prepared as PreparationResult.Failed).failure.code)
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
                    val locals = contract(fixture).internalBindings.associateBy { it.name }
                    for (binding in execution.orderedBindings.filter { it.additionalParameter }) {
                        val origin = binding.origin as PreparedBindingOrigin.MyBatisAdditional
                        assertEquals(locals.getValue(origin.internalBinding.name), origin.internalBinding)
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
            assertStockParity(generic, mapOf("param2" to listOf(7L)), success(request(generic, mapOf(1 to listValue(7)))))
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
    fun topologyChecksEveryCapturedMapperWithoutAssumingASingleSnapshot() {
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
            val unsafe = other.copy(content = other.content.replace("#{item}", "\\#{item}"))
            val drifted = original.copy(graph = StatementSourceGraph(original.graph.rootStatement, original.graph.sourceSnapshots + unsafe, emptyList()))
            val failure = XmlMapperPreparationEngine.prepare(request(drifted, mapOf(0 to listValue(7)))) as PreparationResult.Failed
            assertEquals("xml-foreach-bound-token-topology-unsupported", failure.failure.code)
        }
    }

    @Test
    fun wrapperDriftAndRawStructuralAttributesCannotReuseOldAuthority() {
        val loop = "<foreach collection=\"ids\" item=\"item\">#{item}</foreach>"
        val original = fixture("SELECT 1 <where>$loop</where>", listOf(parameter(0, "java.util.List<java.lang.Long>", "ids")))
        for (body in listOf(
            "SELECT 1 <where bogus=\"x\">$loop</where>",
            "SELECT 1 <where>$loop</where><where>$loop</where>",
            "SELECT 1 <trim prefix=\"WHERE\" prefixOverrides=\"AND|OR\">$loop</trim>",
        )) {
            val drifted = fixture(body, original.mapper.parameters)
            val failure = XmlMapperPreparationEngine.prepare(request(drifted, mapOf(0 to listValue(7)), contract(original))) as PreparationResult.Failed
            assertEquals("xml-foreach-preparation-source-unsupported", failure.failure.code)
        }
        // A valid SELECT WHERE source with unproven caller inputs must still fail.
        val mixed = fixture("SELECT 1 <where>$loop<if test=\"enabled\">AND id = #{id}</if></where>", original.mapper.parameters)
        val mixedFailure = XmlMapperPreparationEngine.prepare(
            request(mixed, mapOf(0 to listValue(7)), contract(original)),
        ) as PreparationResult.Failed
        assertEquals("xml-foreach-preparation-mapper-unsupported", mixedFailure.failure.code)
        val raw = fixture("SELECT 1 <where><foreach collection=\"ids\" item=\"item\" open=\"${'$'}{ids}\">#{item}</foreach></where>", original.mapper.parameters)
        val failure = XmlMapperPreparationEngine.prepare(request(raw, mapOf(0 to listValue(7)))) as PreparationResult.Failed
        assertEquals("xml-preparation-raw-input-unsupported", failure.failure.code)
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
        val admission = XmlForeachPreparationAdmission.inspect(fixture.graph, fixture.mapper, request.parameterContract) as XmlForeachPreparationAdmission.Result.Admitted
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
                assertEquals(listOf("__frch_item_0", "__frch_item_1"), executions.first().orderedBindings.map { it.property })
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
            assertEquals(mapping.javaType.name, binding.metadata.mappingJavaTypeIdentity)
            assertEquals(mapping.jdbcType?.name, binding.metadata.jdbcTypeIdentity)
            assertEquals(mapping.typeHandler.javaClass.name, binding.metadata.typeHandlerIdentity)
            assertEquals(mapping.mode.name, binding.metadata.parameterMode)
            assertEquals(mapping.numericScale, binding.metadata.numericScale)
            val runtime = if (stock.hasAdditionalParameter(mapping.property)) stock.getAdditionalParameter(mapping.property) else values[mapping.property]
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

    private fun contract(fixture: Fixture) = XmlMapperMethodParameterContractFactory.build(fixture.graph, fixture.mapper)

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
