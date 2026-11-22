package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.ExecutionInputOrigin
import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.InputEnvironmentFailureKind
import com.algorist.zMyBatis.core.input.InputEnvironmentResult
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.input.ProvidedInput
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
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import java.math.BigInteger
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.session.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlSiblingBooleanIfPreparationTest {
    @Test
    fun boundedTrimAndNativeWrapperCombinationsPreserveStockSqlAndMappingMetadata() {
        val setBody = "base = #{id},<if test=\"enabled\">a = #{id},</if><if test=\"third\">flag = #{third,jdbcType=BOOLEAN},</if>"
        val whereBody = "<if test=\"other\">OR id = #{id}</if><if test=\"third\">AND flag = #{third,jdbcType=BOOLEAN}</if>"
        for (setTag in listOf("<set>", "<trim prefix=\"SET\" suffixOverrides=\",\">")) {
            for (whereTag in listOf("<where>", "<trim prefix=\"WHERE\" prefixOverrides=\"AND |OR \">")) {
                val setClose = if (setTag == "<set>") "</set>" else "</trim>"
                val whereClose = if (whereTag == "<where>") "</where>" else "</trim>"
                val fixture = fixture("UPDATE t $setTag$setBody$setClose$whereTag$whereBody$whereClose RETURNING #{id}", kind = StatementKind.UPDATE)
                for (mask in 0..7) {
                    val before = Thread.currentThread().contextClassLoader
                    val execution = success(prepare(fixture, mask))
                    assertStockParity(fixture, mask, execution)
                    val properties = buildList {
                        add("id")
                        if (flag(mask, 0)) add("id")
                        if (flag(mask, 2)) add("third")
                        if (flag(mask, 1)) add("id")
                        if (flag(mask, 2)) add("third")
                        add("id")
                    }
                    assertEquals(properties, execution.orderedBindings.map { it.property })
                    assertEquals(properties.map { if (it == "third") InputValue.BooleanValue(true) else integer(42) }, execution.orderedBindings.map { it.value })
                    assertEquals(StatementKind.UPDATE, execution.statementKind)
                    assertEquals(contract(fixture).sourceRevisions, execution.sourceRevisions)
                    assertSame(before, Thread.currentThread().contextClassLoader)
                }
            }
        }
    }

    @Test
    fun trimAttributesRemainStockLiteralsAndWhitespaceOverridesAreNotRewritten() {
        val conditions = "<!-- ignored --><if test=\"enabled\"><![CDATA[and id = #{id}]]></if><if test=\"param1\">OR other = #{param4}</if>"
        for (opening in listOf(
            "<trim prefix=\"WHERE\" prefixOverrides=\"AND |OR \">",
            "<trim prefixOverrides=\"AND &#124;OR \" prefix=\"&#87;HERE\">",
        )) {
            for (predicate in listOf(conditions, "<if test=\"enabled\">AND\nid = #{id}</if>", "<if test=\"enabled\">ANDROID = #{id}</if>")) {
                val fixture = fixture("SELECT 1 $opening$predicate</trim>")
                for (mask in 0..1) {
                    val execution = success(prepare(fixture, mask))
                    assertStockParity(fixture, mask, execution)
                    if (mask == 0) assertEquals("SELECT 1", normalize(execution.sqlWithPlaceholders))
                    if (mask == 1 && predicate.contains("AND\n")) assertTrue(normalize(execution.sqlWithPlaceholders).contains("WHERE AND id = ?"))
                    if (mask == 1 && predicate.contains("ANDROID")) assertTrue(execution.sqlWithPlaceholders.contains("ANDROID"))
                    assertTrue(contract(fixture).aliases.none { it.name in setOf("prefix", "prefixOverrides", "trim", "WHERE") })
                }
            }
        }
        val set = fixture("UPDATE t <trim suffixOverrides=\",\" prefix=\"SET\">\n<if test=\"param1\"><![CDATA[a = #{id},\t]]></if></trim> WHERE id = 1", kind = StatementKind.UPDATE)
        for (mask in 0..1) assertStockParity(set, mask, success(prepare(set, mask)))
    }

    @Test
    fun trimMapLeavesFollowStockMissingAsNullSemanticsWithoutInventingInactiveBindings() {
        val fixture = fixture("UPDATE t <trim prefix=\"SET\" suffixOverrides=\",\"><if test=\"enabled\">a = #{id.first},</if></trim><trim prefix=\"WHERE\" prefixOverrides=\"AND |OR \"><if test=\"other\">AND b = #{id.second}</if></trim>", "java.util.Map<java.lang.String,java.lang.Long>", StatementKind.UPDATE)
        val partial = InputValue.MapValue(mapOf("first" to InputValue.NullValue))
        assertEquals(InputValue.NullValue, success(prepare(fixture, 1, replacements = mapOf(3 to partial))).orderedBindings.single().value)
        assertTrue(success(prepare(fixture, 0, replacements = mapOf(3 to partial))).orderedBindings.isEmpty())
        assertEquals(
            listOf(InputValue.NullValue),
            success(prepare(fixture, 2, replacements = mapOf(3 to partial))).orderedBindings.map { it.value },
        )
        assertEquals(
            listOf(InputValue.NullValue, InputValue.NullValue),
            success(prepare(fixture, 3, replacements = mapOf(3 to partial))).orderedBindings.map { it.value },
        )
        val complete = InputValue.MapValue(mapOf("first" to integer(5), "second" to InputValue.NullValue))
        assertEquals(listOf(integer(5), InputValue.NullValue), success(prepare(fixture, 3, replacements = mapOf(3 to complete))).orderedBindings.map { it.value })
    }

    @Test
    fun allEightCombinedUpdateCombinationsKeepStockSqlMetadataAndGuardedMappingCounts() {
        for (staticAssignment in listOf("", "base = #{id},")) {
            for (staticPredicate in listOf("", "AND base = #{id}")) {
                val fixture = fixture(
                    "UPDATE t <set>$staticAssignment<if test=\"enabled\">a = #{id},</if><if test=\"third\">flag = #{third,jdbcType=BOOLEAN},</if></set><where>$staticPredicate<if test=\"other\">OR id = #{id}</if><if test=\"third\">AND flag = #{third,jdbcType=BOOLEAN}</if></where> RETURNING #{id}",
                    kind = StatementKind.UPDATE,
                )
                for (mask in 0..7) {
                    val before = Thread.currentThread().contextClassLoader
                    val execution = success(prepare(fixture, mask))
                    assertStockParity(fixture, mask, execution)
                    val properties = buildList {
                        if (staticAssignment.isNotEmpty()) add("id")
                        if (flag(mask, 0)) add("id")
                        if (flag(mask, 2)) add("third")
                        if (staticPredicate.isNotEmpty()) add("id")
                        if (flag(mask, 1)) add("id")
                        if (flag(mask, 2)) add("third")
                        add("id")
                    }
                    assertEquals(properties, execution.orderedBindings.map { it.property })
                    assertEquals(properties.map { if (it == "third") InputValue.BooleanValue(true) else integer(42) }, execution.orderedBindings.map { it.value })
                    assertEquals(staticAssignment.isNotEmpty() || flag(mask, 0) || flag(mask, 2), execution.sqlWithPlaceholders.contains("SET"))
                    assertEquals(staticPredicate.isNotEmpty() || flag(mask, 1) || flag(mask, 2), execution.sqlWithPlaceholders.contains("WHERE"))
                    if (mask == 0 && staticAssignment.isEmpty() && staticPredicate.isEmpty()) assertEquals("UPDATE t RETURNING ?", normalize(execution.sqlWithPlaceholders))
                    assertEquals(StatementKind.UPDATE, execution.statementKind)
                    assertEquals(contract(fixture).sourceRevisions, execution.sourceRevisions)
                    assertSame(before, Thread.currentThread().contextClassLoader)
                }
            }
        }
    }

    @Test
    fun combinedWrappersPreserveGenericRepeatedGuardsCommentsAndSourceOrder() {
        val set = "<set>\n<!-- ignored --><if test=\"enabled\"><![CDATA[a = #{id},\t]]></if><if test=\"param1\">b = #{param4},</if></set>"
        val where = "<where><if test=\"enabled\">\nor\tid = #{id}</if><if test=\"param2\">AND other = #{param4}</if></where>"
        for (body in listOf("$set $where", "$where $set")) {
            val fixture = fixture("UPDATE t $body", kind = StatementKind.UPDATE)
            for (mask in 0..3) {
                val execution = success(prepare(fixture, mask))
                assertStockParity(fixture, mask, execution)
                assertEquals((if (flag(mask, 0)) 3 else 0) + (if (flag(mask, 1)) 1 else 0), execution.orderedBindings.size)
            }
        }
    }

    @Test
    fun combinedUpdateMapLeavesFollowStockMissingAsNullAcrossBothWrappers() {
        val fixture = fixture("UPDATE t <set><if test=\"enabled\">a = #{id.first},</if></set><where><if test=\"other\">AND b = #{id.second}</if></where>", "java.util.Map<java.lang.String,java.lang.Long>", StatementKind.UPDATE)
        val partial = InputValue.MapValue(mapOf("first" to InputValue.NullValue))
        assertEquals(InputValue.NullValue, success(prepare(fixture, 1, replacements = mapOf(3 to partial))).orderedBindings.single().value)
        assertTrue(success(prepare(fixture, 0, replacements = mapOf(3 to partial))).orderedBindings.isEmpty())
        assertEquals(
            listOf(InputValue.NullValue),
            success(prepare(fixture, 2, replacements = mapOf(3 to partial))).orderedBindings.map { it.value },
        )
        assertEquals(
            listOf(InputValue.NullValue, InputValue.NullValue),
            success(prepare(fixture, 3, replacements = mapOf(3 to partial))).orderedBindings.map { it.value },
        )
        val complete = InputValue.MapValue(mapOf("first" to integer(5), "second" to InputValue.NullValue))
        assertEquals(listOf(integer(5), InputValue.NullValue), success(prepare(fixture, 3, replacements = mapOf(3 to complete))).orderedBindings.map { it.value })
    }

    @Test
    fun allEightSetCombinationsKeepStockSqlOrderedMetadataAndUnconditionalBindings() {
        for (staticAssignment in listOf("", "base = #{id},")) {
            val fixture = fixture(
                "UPDATE t <set>$staticAssignment<if test=\"enabled\">a = #{id},</if><if test=\"other\">b = #{id},</if><if test=\"third\">flag = #{third,jdbcType=BOOLEAN},</if></set> WHERE id = #{id}",
                kind = StatementKind.UPDATE,
            )
            for (mask in 0..7) {
                val before = Thread.currentThread().contextClassLoader
                val execution = success(prepare(fixture, mask))
                assertStockParity(fixture, mask, execution)
                assertEquals(StatementKind.UPDATE, execution.statementKind)
                val properties = buildList {
                    if (staticAssignment.isNotEmpty()) add("id")
                    if (flag(mask, 0)) add("id")
                    if (flag(mask, 1)) add("id")
                    if (flag(mask, 2)) add("third")
                    add("id")
                }
                assertEquals(properties, execution.orderedBindings.map { it.property })
                assertEquals(properties.map { if (it == "third") InputValue.BooleanValue(true) else integer(42) }, execution.orderedBindings.map { it.value })
                assertEquals(mask != 0 || staticAssignment.isNotEmpty(), execution.sqlWithPlaceholders.contains("SET"))
                assertFalse(execution.sqlWithPlaceholders.substringBefore("WHERE").trimEnd().endsWith(','))
                if (mask == 0 && staticAssignment.isEmpty()) assertEquals("UPDATE t WHERE id = ?", normalize(execution.sqlWithPlaceholders))
                assertEquals(contract(fixture).sourceRevisions, execution.sourceRevisions)
                assertSame(before, Thread.currentThread().contextClassLoader)
            }
        }
    }

    @Test
    fun setPreservesStockWhitespaceCommentsAndGenericRepeatedGuards() {
        val fixture = fixture("UPDATE t <set>\n<!-- ignored --> <if test=\"enabled\"><![CDATA[a = #{id},\t]]></if><if test=\"param1\">b = #{param4},\n</if><if test=\"other\">c = #{id},</if></set> WHERE id = #{id}", kind = StatementKind.UPDATE)
        for (mask in 0..3) {
            val execution = success(prepare(fixture, mask))
            assertStockParity(fixture, mask, execution)
            assertEquals(1 + (if (flag(mask, 0)) 2 else 0) + (if (flag(mask, 1)) 1 else 0), execution.orderedBindings.size)
        }
    }

    @Test
    fun setActiveMapLeavesFollowStockMissingAsNullWhileInactiveLeavesAreUnread() {
        val fixture = fixture("UPDATE t <set><if test=\"enabled\">a = #{id.first},</if><if test=\"other\">b = #{id.second},</if></set> WHERE id = 1", "java.util.Map<java.lang.String,java.lang.Long>", StatementKind.UPDATE)
        val partial = InputValue.MapValue(mapOf("first" to InputValue.NullValue))
        assertEquals(InputValue.NullValue, success(prepare(fixture, 1, replacements = mapOf(3 to partial))).orderedBindings.single().value)
        val inactive = success(prepare(fixture, 0, replacements = mapOf(3 to partial)))
        assertTrue(inactive.orderedBindings.isEmpty())
        assertEquals("UPDATE t WHERE id = 1", normalize(inactive.sqlWithPlaceholders))
        assertEquals(
            listOf(InputValue.NullValue),
            success(prepare(fixture, 2, replacements = mapOf(3 to partial))).orderedBindings.map { it.value },
        )
        assertEquals(
            listOf(InputValue.NullValue, InputValue.NullValue),
            success(prepare(fixture, 3, replacements = mapOf(3 to partial))).orderedBindings.map { it.value },
        )
    }

    @Test
    fun setTokenSynthesisAndEmptySqlRemainTypedFailuresAcrossBranches() {
        for (body in listOf(
            "UPDATE t #<set><if test=\"enabled\">{id},</if></set>",
            "UPDATE t <set><if test=\"enabled\">#</if><if test=\"other\">{id},</if></set>",
            "UPDATE t <set><if test=\"enabled\">\\</if><if test=\"other\">a = #{id},</if></set>",
        )) {
            val fixture = fixture(body, kind = StatementKind.UPDATE)
            assertFalse(contract(fixture).isPreparationBlocked)
            for (mask in 0..3) {
                assertEquals("xml-boolean-if-bound-token-topology-unsupported", (prepare(fixture, mask) as PreparationResult.Failed).failure.code)
            }
        }
        val empty = fixture("<set><if test=\"enabled\">a = #{id},</if></set>", kind = StatementKind.UPDATE)
        val before = Thread.currentThread().contextClassLoader
        assertEquals("mybatis-prepared-sql-empty", (prepare(empty, 0) as PreparationResult.Failed).failure.code)
        assertSame(before, Thread.currentThread().contextClassLoader)
        assertEquals("SET a = ?", normalize(success(prepare(empty, 1)).sqlWithPlaceholders))
    }

    @Test
    fun allEightDirectConditionCombinationsKeepStockSqlOrderedValuesAndMappingMetadata() {
        val fixture = fixture("SELECT #{id}<if test=\"enabled\">, #{id}, #{enabled,jdbcType=BOOLEAN}</if>, #{id}<if test=\"other\">, #{id}</if><if test=\"third\">, #{id}, #{id}</if>")
        for (mask in 0..7) {
            val execution = success(prepare(fixture, mask))
            val properties = buildList {
                add("id")
                if (flag(mask, 0)) { add("id"); add("enabled") }
                add("id")
                if (flag(mask, 1)) add("id")
                if (flag(mask, 2)) { add("id"); add("id") }
            }
            assertEquals(properties, execution.orderedBindings.map { it.property })
            assertEquals(properties.map { if (it == "enabled") InputValue.BooleanValue(true) else integer(42) }, execution.orderedBindings.map { it.value })
            assertStockParity(fixture, mask, execution)
            assertEquals(contract(fixture).sourceRevisions, execution.sourceRevisions)
        }
    }

    @Test
    fun allEightWhereCombinationsKeepStockTrimmingAndStaticMappings() {
        val fixture = fixture("SELECT #{id}<where><if test=\"enabled\">OR id = #{id}</if><if test=\"other\">AND id = #{id}</if><if test=\"third\">OR id = #{id}</if></where> ORDER BY #{id}")
        for (mask in 0..7) {
            val execution = success(prepare(fixture, mask))
            assertStockParity(fixture, mask, execution)
            assertEquals(2 + Integer.bitCount(mask), execution.orderedBindings.size)
            assertTrue(execution.orderedBindings.all { it.value == integer(42) })
            assertEquals(mask != 0, execution.sqlWithPlaceholders.contains("WHERE"))
            if (mask == 0) assertEquals("SELECT ? ORDER BY ?", normalize(execution.sqlWithPlaceholders))
        }
    }

    @Test
    fun repeatedExplicitAndGenericGuardsUseOneTypedCallerWithoutLosingOccurrences() {
        val fixture = fixture("SELECT #{id}<if test=\"enabled\">, #{id}</if><if test=\"param1\">, #{param4}</if><if test=\"enabled\">, #{id}</if>")
        val contract = contract(fixture)
        assertEquals(4, contract.requirements.size)
        assertEquals(
            listOf("method-param:0", "method-param:1", "method-param:2", "method-param:3"),
            contract.requirements.map { it.id.value },
        )
        for (mask in listOf(0, 1)) {
            val execution = success(prepare(fixture, mask))
            assertEquals(if (mask == 0) 1 else 4, execution.orderedBindings.size)
            assertStockParity(fixture, mask, execution)
        }
    }

    @Test
    fun siblingConditionsFollowStockMissingAsNullNamedMapSemantics() {
        val fixture = fixture("SELECT 1<where><if test=\"enabled\">AND a = #{id.first}</if><if test=\"other\">AND b = #{id.second}</if></where>", "java.util.Map<java.lang.String,java.lang.Long>")
        val partial = InputValue.MapValue(mapOf("first" to InputValue.NullValue))
        val firstOnly = success(prepare(fixture, 1, replacements = mapOf(3 to partial)))
        assertEquals(InputValue.NullValue, firstOnly.orderedBindings.single().value)
        val inactive = success(prepare(fixture, 0, replacements = mapOf(3 to partial)))
        assertTrue(inactive.orderedBindings.isEmpty())
        assertEquals("SELECT 1", normalize(inactive.sqlWithPlaceholders))
        assertEquals(
            listOf(InputValue.NullValue),
            success(prepare(fixture, 2, replacements = mapOf(3 to partial))).orderedBindings.map { it.value },
        )
        assertEquals(
            listOf(InputValue.NullValue, InputValue.NullValue),
            success(prepare(fixture, 3, replacements = mapOf(3 to partial))).orderedBindings.map { it.value },
        )
    }

    @Test
    fun everyConditionAndCallerRootRemainRequiredEvenWhenAllConditionsAreFalse() {
        val fixture = fixture("SELECT 1<if test=\"enabled\">#{id}</if><if test=\"other\">#{id}</if><if test=\"third\">#{id}</if>")
        val contract = contract(fixture)
        val inputs = contract.requirements.map { requirement ->
            val index = indexOf(requirement.provenance.evidence)
            ProvidedInput(requirement.id, if (index == 3) integer(42) else InputValue.BooleanValue(false), ExecutionInputOrigin.USER_ENTERED)
        }
        for (input in inputs) {
            val failure = InputEnvironment.validate(contract, inputs.filterNot { it.requirementId == input.requirementId }) as InputEnvironmentResult.Failure
            assertTrue(failure.failures.any { it.kind == InputEnvironmentFailureKind.MISSING_REQUIRED_INPUT })
        }
        val other = inputs[1]
        val nullableEnvironment = InputEnvironment.validate(
            contract,
            inputs.map { if (it == other) it.copy(value = InputValue.NullValue) else it },
        )
        assertTrue(nullableEnvironment is InputEnvironmentResult.Success)
        val nullableExecution = success(
            prepare(fixture, 0, replacements = mapOf(1 to InputValue.NullValue)),
        )
        assertEquals("SELECT 1", normalize(nullableExecution.sqlWithPlaceholders))
        assertTrue(nullableExecution.orderedBindings.isEmpty())

        val overRange = (prepare(fixture, 0, replacements = mapOf(3 to InputValue.IntegerValue(BigInteger.ONE.shiftLeft(100)))) as PreparationResult.Failed).failure
        assertEquals(PreparationFailureKind.UNSUPPORTED_BINDING_VALUE, overRange.kind)
    }

    @Test
    fun tokenSynthesisAcrossSiblingBoundariesIsRejectedForEveryTruthCombination() {
        for (body in listOf(
            "SELECT #<if test=\"enabled\">{id}</if><if test=\"other\">, #{id}</if>",
            "SELECT 1<if test=\"enabled\">#</if><if test=\"other\">{id}</if>",
            "SELECT 1<where><if test=\"enabled\">\\</if><if test=\"other\">#{id}</if></where>",
        )) {
            val fixture = fixture(body)
            assertFalse(contract(fixture).isPreparationBlocked)
            for (mask in 0..3) {
                assertEquals("xml-boolean-if-bound-token-topology-unsupported", (prepare(fixture, mask) as PreparationResult.Failed).failure.code)
            }
        }
    }

    @Test
    fun emptyAllFalseSqlIsTypedAndDoesNotPoisonSubsequentPreparation() {
        val fixture = fixture("<if test=\"enabled\">SELECT 1</if><if test=\"other\">SELECT 2</if>")
        val before = Thread.currentThread().contextClassLoader
        assertEquals("mybatis-prepared-sql-empty", (prepare(fixture, 0) as PreparationResult.Failed).failure.code)
        assertSame(before, Thread.currentThread().contextClassLoader)
        assertEquals("SELECT 1", normalize(success(prepare(fixture, 1)).sqlWithPlaceholders))
        assertSame(before, Thread.currentThread().contextClassLoader)
    }

    @Test
    fun concurrentSiblingAndForeachInvocationsIgnoreParentOgnlAndRestoreContext() = synchronized(Configuration::class.java) {
        val loader = Configuration::class.java.classLoader
        val context = Class.forName("org.apache.ibatis.scripting.xmltags.DynamicContext\$ContextMap", false, loader)
        val runtime = Class.forName("org.apache.ibatis.ognl.OgnlRuntime", true, loader)
        val accessor = Class.forName("org.apache.ibatis.ognl.PropertyAccessor", true, loader)
        val get = runtime.getMethod("getPropertyAccessor", Class::class.java)
        val set = runtime.getMethod("setPropertyAccessor", Class::class.java, accessor)
        val original = get.invoke(null, context)
        val poison = Proxy.newProxyInstance(loader, arrayOf(accessor)) { _, _, _ -> throw AssertionError("parent OGNL was invoked") }
        val siblings = fixture("SELECT #{id}<where><if test=\"enabled\">AND a = #{id}</if><if test=\"other\">AND b = #{id}</if><if test=\"third\">AND c = #{id}</if></where>")
        val setFixture = fixture("UPDATE t <set><if test=\"enabled\">a = #{id},</if><if test=\"other\">b = #{id},</if><if test=\"third\">c = #{id},</if></set> WHERE id = #{id}", kind = StatementKind.UPDATE)
        val combined = fixture("UPDATE t <set><if test=\"enabled\">a = #{id},</if><if test=\"third\">c = #{id},</if></set><where><if test=\"other\">AND id = #{id}</if><if test=\"third\">AND c = #{id}</if></where>", kind = StatementKind.UPDATE)
        val trimmed = fixture("UPDATE t <trim prefix=\"SET\" suffixOverrides=\",\"><if test=\"enabled\">a = #{id},</if></trim><trim prefix=\"WHERE\" prefixOverrides=\"AND |OR \"><if test=\"other\">AND id = #{id}</if><if test=\"third\">OR c = #{id}</if></trim>", kind = StatementKind.UPDATE)
        val foreach = fixture("SELECT <foreach collection=\"id\" item=\"item\" separator=\",\">#{item}</foreach>", "java.util.List<java.lang.Long>")
        val executor = Executors.newFixedThreadPool(4)
        set.invoke(null, context, poison)
        try {
            val futures = (0..7).map { mask -> executor.submit(Callable {
                val before = Thread.currentThread().contextClassLoader
                val prepared = success(prepare(siblings, mask, id = mask.toLong()))
                assertEquals(1 + Integer.bitCount(mask), prepared.orderedBindings.size)
                assertTrue(prepared.orderedBindings.all { it.value == integer(mask.toLong()) })
                val updated = success(prepare(setFixture, mask, id = mask.toLong()))
                assertEquals(1 + Integer.bitCount(mask), updated.orderedBindings.size)
                assertTrue(updated.orderedBindings.all { it.value == integer(mask.toLong()) })
                assertEquals(StatementKind.UPDATE, updated.statementKind)
                val composed = success(prepare(combined, mask, id = mask.toLong()))
                assertEquals(Integer.bitCount(mask) + (if (flag(mask, 2)) 1 else 0), composed.orderedBindings.size)
                assertTrue(composed.orderedBindings.all { it.value == integer(mask.toLong()) })
                val trimmedExecution = success(prepare(trimmed, mask, id = mask.toLong()))
                assertEquals(Integer.bitCount(mask), trimmedExecution.orderedBindings.size)
                assertTrue(trimmedExecution.orderedBindings.all { it.value == integer(mask.toLong()) })
                val items = InputValue.ListValue(listOf(integer(mask.toLong()), integer(99)))
                val repeated = success(prepare(foreach, 0, replacements = mapOf(3 to items)))
                assertEquals(listOf(integer(mask.toLong()), integer(99)), repeated.orderedBindings.map { it.value })
                assertTrue(repeated.orderedBindings.all { it.additionalParameter })
                assertSame(before, Thread.currentThread().contextClassLoader)
            }) }
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
            assertSame(poison, get.invoke(null, context))
        } finally {
            try {
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))
            } finally {
                set.invoke(null, context, original)
            }
        }
    }

    private fun prepare(fixture: Fixture, mask: Int, id: Long = 42, replacements: Map<Int, InputValue> = emptyMap(), supplied: ParameterContract = contract(fixture)): PreparationResult {
        val inputs = supplied.requirements.map { requirement ->
            val index = indexOf(requirement.provenance.evidence)
            ProvidedInput(requirement.id, replacements[index] ?: if (index == 3) integer(id) else InputValue.BooleanValue(flag(mask, index)), ExecutionInputOrigin.USER_ENTERED)
        }
        val environment = (InputEnvironment.validate(supplied, inputs) as InputEnvironmentResult.Success).environment
        val request = (MyBatisPreparationRequest.create(XmlMapperPreparationSource(fixture.graph, mapperMethod = fixture.mapper), supplied, environment) as PreparationRequestResult.Ready).request
        return XmlMapperPreparationEngine.prepare(request)
    }

    private fun assertStockParity(fixture: Fixture, mask: Int, execution: PreparedExecution) {
        val configuration = Configuration()
        val xml = fixture.graph.sourceSnapshots.single().content
        ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)).use { XMLMapperBuilder(it, configuration, "stock-sibling-oracle", configuration.sqlFragments).parse() }
        val values = linkedMapOf<String, Any?>("enabled" to flag(mask, 0), "other" to flag(mask, 1), "third" to flag(mask, 2), "id" to 42L)
        for (index in 0..3) values["param${index + 1}"] = if (index == 3) 42L else flag(mask, index)
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
        }
    }

    private fun fixture(body: String, idType: String = "long", kind: StatementKind = StatementKind.SELECT): Fixture {
        val file = SourceFileId("siblings.xml")
        val statement = XmlStatementId(file, "example.Mapper", "find")
        val element = kind.name.lowercase()
        val xml = "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"https://mybatis.org/dtd/mybatis-3-mapper.dtd\"><mapper namespace=\"example.Mapper\"><$element id=\"find\">$body</$element></mapper>"
        val graph = StatementSourceGraph(CapturedStatement(statement, kind, SourceRange(0, xml.length)), listOf(SourceSnapshot(file, SourceRevision("xml-r1"), xml)), emptyList())
        val parameters = listOf("enabled" to "boolean", "other" to "java.lang.Boolean", "third" to "boolean", "id" to idType).mapIndexed { index, (name, type) -> JavaMethodParameterMetadata(index, name, JavaTypeIdentity(type), name) }
        val mapper = XmlMapperMethodCapture(statement, SourceSnapshot(SourceFileId("Mapper.java"), SourceRevision("java-r1"), "x".repeat(100)), SourceRange(0, 100), parameters)
        return Fixture(graph, mapper)
    }

    private fun success(result: PreparationResult): PreparedExecution {
        assertTrue("expected success, received $result", result is PreparationResult.Success)
        return (result as PreparationResult.Success).execution
    }

    private fun contract(fixture: Fixture): ParameterContract {
        val source = XmlMapperPreparationSource(fixture.graph, mapperMethod = fixture.mapper)
        return MyBatisInvocationContractFactory.build(source)
    }
    private fun indexOf(evidence: List<InputEvidence>) = evidence.filterIsInstance<InputEvidence.MapperMethodParameter>().single().index
    private fun flag(mask: Int, index: Int) = mask and (1 shl index) != 0
    private fun integer(value: Long) = InputValue.IntegerValue(BigInteger.valueOf(value))
    private fun normalize(sql: String) = sql.trim().replace(Regex("\\s+"), " ")
    private data class Fixture(val graph: StatementSourceGraph, val mapper: XmlMapperMethodCapture)
}
