package com.algorist.zMyBatis.core.input

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlBooleanIfParameterContractTest {
    @Test
    fun boundedTrimFormsPreserveSourceGuardScopesAndMapperCallerAuthority() {
        val setBody = "base = #{id},<if test=\"enabled\">a = #{id},</if>"
        val whereBody = "AND base = #{id}<if test=\"param2\">OR id = #{id}</if>"
        val set = "<trim prefix=\"SET\" suffixOverrides=\",\">$setBody</trim>"
        val where = "<trim prefix=\"WHERE\" prefixOverrides=\"AND |OR \">$whereBody</trim>"
        for ((body, kind, scopes) in listOf(
            Triple(where, StatementKind.SELECT, listOf(null, "param2")),
            Triple(set, StatementKind.UPDATE, listOf(null, "enabled")),
            Triple("$set $where", StatementKind.UPDATE, listOf(null, "enabled", null, "param2")),
            Triple("<set>$setBody</set>$where", StatementKind.UPDATE, listOf(null, "enabled", null, "param2")),
            Triple("$set<where>$whereBody</where>", StatementKind.UPDATE, listOf(null, "enabled", null, "param2")),
        )) {
            val graph = graph(body, kind)
            val sourceOnly = XmlStatementParameterContractFactory.build(graph)
            assertTrue(sourceOnly.isPreparationBlocked)
            assertTrue(sourceOnly.requirements.isEmpty())
            val contract = build(graph, parameter(0, "boolean", "enabled"), parameter(1, "java.lang.Boolean", "other"), parameter(2, "long", "id"))
            assertFalse(body, contract.isPreparationBlocked)
            val id = contract.requirements.single { it.id.value == "xml-java-param:2" }
            assertEquals(scopes, id.provenance.evidence.filterIsInstance<InputEvidence.Placeholder>().map { it.enclosingOgnlExpression })
            assertTrue(contract.aliases.none { it.name in setOf("trim", "WHERE", "SET", "prefix", "prefixOverrides", "suffixOverrides") })
            assertTrue(contract.internalBindings.isEmpty())
        }
    }

    @Test
    fun arbitraryTrimAttributesAndInvalidCompositionCannotLeavePartialCallerAuthority() {
        val condition = "<if test=\"enabled\">id = #{id}</if>"
        val where = "<trim prefix=\"WHERE\" prefixOverrides=\"AND |OR \">$condition</trim>"
        val set = "<trim prefix=\"SET\" suffixOverrides=\",\">$condition</trim>"
        for (body in listOf(
            "<trim>$condition</trim>", "<trim prefix=\"WHERE\">$condition</trim>",
            "<trim prefix=\"where\" prefixOverrides=\"AND |OR \">$condition</trim>",
            "<trim prefix=\"WHERE\" prefixOverrides=\"AND|OR\">$condition</trim>",
            "<trim prefix=\"WHERE\" suffixOverrides=\",\">$condition</trim>",
            "<trim prefix=\"WHERE\" prefixOverrides=\"AND |OR \" suffix=\"tail\">$condition</trim>",
            "<trim prefix=\"SET\" suffixOverrides=\"?\">$condition</trim>",
            "<trim prefix=\"#{id}\" prefixOverrides=\"AND |OR \">$condition</trim>",
            "<trim prefix=\"\${prefix}\" prefixOverrides=\"AND |OR \">$condition</trim>",
            "<trim prefix=\"WHERE\" prefixOverrides=\"\${overrides}\">$condition</trim>",
            "<trim prefix=\"WHERE\" x:prefixOverrides=\"AND |OR \" xmlns:x=\"urn:unsupported\">$condition</trim>",
            "<trim prefix=\"WHERE\" prefixOverrides=\"AND |OR \" xmlns=\"urn:unsupported\">$condition</trim>",
            "<x:trim prefix=\"WHERE\" prefixOverrides=\"AND |OR \" xmlns:x=\"urn:unsupported\">$condition</x:trim>",
            "<trim prefix=\"WHERE\" prefixOverrides=\"AND |OR \"/>",
            "<trim prefix=\"WHERE\" prefixOverrides=\"AND |OR \">AND id = #{id}</trim>",
            "<trim prefix=\"SET\" suffixOverrides=\",\">a = #{id},</trim>",
            "$where<where>$condition</where>", "<where>$condition</where>$where", "$where$where",
            "$set<set>$condition</set>", "<set>$condition</set>$set", "$set$set",
            "$where<trim prefix=\"SET\" suffixOverrides=\",\"/>",
            "$set<trim prefix=\"WHERE\" prefixOverrides=\"AND |OR \">AND id = #{id}</trim>",
            "<trim prefix=\"WHERE\" prefixOverrides=\"AND |OR \"><where>$condition</where></trim>",
            "$condition$where", "$where$condition", "$set$condition$where",
        )) {
            val contract = build(graph(body, StatementKind.UPDATE), parameter(0, "boolean", "enabled"), parameter(1, "long", "id"))
            assertTrue(body, contract.isPreparationBlocked)
            assertTrue(body, contract.requirements.isEmpty())
            assertTrue(body, contract.aliases.isEmpty())
        }
        for (kind in listOf(StatementKind.SELECT, StatementKind.INSERT, StatementKind.DELETE)) {
            assertTrue(build(graph(set, kind), parameter(0, "boolean", "enabled"), parameter(1, "long", "id")).isPreparationBlocked)
        }
        for (type in listOf("long", "java.lang.String")) {
            assertTrue(build(graph(where), parameter(0, type, "enabled"), parameter(1, "long", "id")).isPreparationBlocked)
        }
    }

    @Test
    fun updateSetAndWhereRetainIndependentAndRepeatedGuardProvenanceInSourceOrder() {
        val set = "<set>base = #{id},<if test=\"enabled\">a = #{id},</if><if test=\"param2\">b = #{id},</if></set>"
        val where = "<where>AND base = #{id}<if test=\"other\">AND b = #{id}</if><if test=\"enabled\">AND a = #{id}</if></where>"
        for ((body, scopes) in listOf(
            "$set $where" to listOf(null, "enabled", "param2", null, "other", "enabled"),
            "$where $set" to listOf(null, "other", "enabled", null, "enabled", "param2"),
        )) {
            val graph = graph("UPDATE t $body", StatementKind.UPDATE)
            val sourceOnly = XmlStatementParameterContractFactory.build(graph)
            assertTrue(sourceOnly.isPreparationBlocked)
            assertTrue(sourceOnly.requirements.isEmpty())
            val contract = build(graph, parameter(0, "boolean", "enabled"), parameter(1, "java.lang.Boolean", "other"), parameter(2, "long", "id"))
            assertFalse(body, contract.isPreparationBlocked)
            assertEquals(3, contract.requirements.size)
            assertTrue(contract.requirements.all { it.requiredness == InputRequiredness.REQUIRED })
            assertEquals(scopes, contract.requirements[2].provenance.evidence.filterIsInstance<InputEvidence.Placeholder>().map { it.enclosingOgnlExpression })
            assertEquals(setOf("param2", "other"), conditions(contract.requirements[1]).toSet())
            assertTrue(contract.aliases.none { it.name == "set" || it.name == "where" })
            assertTrue(contract.internalBindings.isEmpty())
        }
    }

    @Test
    fun setPreservesConditionalAndUnconditionalUsesWithoutCreatingASetCaller() {
        val graph = graph(
            "UPDATE t <set>base = #{id},<if test=\"enabled\">a = #{id},</if><if test=\"param2\">b = #{id},</if></set> WHERE id = #{id}",
            StatementKind.UPDATE,
        )
        val sourceOnly = XmlStatementParameterContractFactory.build(graph)
        assertTrue(sourceOnly.isPreparationBlocked)
        assertTrue(sourceOnly.requirements.isEmpty())
        val contract = build(graph, parameter(0, "boolean", "enabled"), parameter(1, "java.lang.Boolean", "other"), parameter(2, "long", "id"))
        assertFalse(contract.isPreparationBlocked)
        assertEquals(3, contract.requirements.size)
        assertTrue(contract.requirements.all { it.requiredness == InputRequiredness.REQUIRED })
        assertEquals(listOf("enabled"), conditions(contract.requirements[0]))
        assertEquals(listOf("param2"), conditions(contract.requirements[1]))
        assertEquals(listOf(null, "enabled", "param2", null), contract.requirements[2].provenance.evidence.filterIsInstance<InputEvidence.Placeholder>().map { it.enclosingOgnlExpression })
        assertTrue(contract.aliases.none { it.name == "set" })
        assertTrue(contract.internalBindings.isEmpty())
    }

    @Test
    fun unsupportedSetCompositionCannotLeavePartialCallerAuthority() {
        val condition = "<if test=\"enabled\">a = #{id},</if>"
        for (body in listOf(
            "<set/>", "<set>a = #{id},</set>",
            "<set><!-- $condition --><![CDATA[$condition]]></set>",
            "<set suffixOverrides=\",\">$condition</set>",
            "<set xmlns=\"urn:unsupported\">$condition</set>",
            "<x:set xmlns:x=\"urn:unsupported\">$condition</x:set>",
            "<set><set>$condition</set></set>",
            "<set><where>$condition</where></set>",
            "<set>$condition</set><where/>",
            "<set>$condition</set><where>AND id = #{id}</where>",
            "<where>$condition</where><set/>",
            "<where>$condition</where><set>a = #{id},</set>",
            "<set>$condition</set><where>$condition</where><where>$condition</where>",
            "<where>$condition</where><set>$condition</set><set>$condition</set>",
            "<set>$condition</set>$condition<where>$condition</where>",
            "<where>$condition</where>$condition<set>$condition</set>",
            "<set>$condition</set><where extra=\"ignored\">$condition</where>",
            "<set>$condition</set><where xmlns=\"urn:unsupported\">$condition</where>",
            "<where>$condition</where><set extra=\"ignored\">$condition</set>",
            "<set>$condition</set><where><if test=\"enabled != null\">AND id = #{id}</if></where>",
            "<set>$condition</set><set>$condition</set>",
            "$condition<set>$condition</set>", "<set>$condition</set>$condition",
            "<if test=\"enabled\"><set>a = #{id},</set></if>",
            "<set>$condition<bind name=\"x\" value=\"1\"/></set>",
            "<set>$condition<include refid=\"fragment\"/></set>",
            "<set>$condition<foreach collection=\"ids\" item=\"item\">#{item}</foreach></set>",
            "<foreach collection=\"ids\" item=\"item\">#{item}</foreach><set>$condition</set>",
            "<set>$condition</set><foreach collection=\"ids\" item=\"item\">#{item}</foreach>",
        )) {
            val contract = build(graph("UPDATE t $body WHERE id = #{id}", StatementKind.UPDATE), parameter(0, "boolean", "enabled"), parameter(1, "long", "id"))
            assertTrue(body, contract.isPreparationBlocked)
            assertTrue(body, contract.requirements.isEmpty())
            assertTrue(body, contract.aliases.isEmpty())
        }
    }

    @Test
    fun setCannotGrantUnprovenNonBooleanOrRawInputAuthority() {
        val body = "UPDATE t <set><if test=\"enabled\">a = #{id},</if></set> WHERE id = #{id}"
        for (kind in listOf(StatementKind.SELECT, StatementKind.INSERT, StatementKind.DELETE)) {
            val contract = build(graph(body, kind), parameter(0, "boolean", "enabled"), parameter(1, "long", "id"))
            assertTrue(contract.isPreparationBlocked)
            assertTrue(contract.requirements.isEmpty())
        }
        for (type in listOf("long", "java.lang.String", "java.util.Map<java.lang.String,java.lang.Boolean>")) {
            assertTrue(build(graph(body, StatementKind.UPDATE), parameter(0, type, "enabled"), parameter(1, "long", "id")).isPreparationBlocked)
        }
        assertTrue(build(graph(body, StatementKind.UPDATE), parameter(0, "boolean", "different"), parameter(1, "long", "id")).isPreparationBlocked)
        for (rawBody in listOf(
            "UPDATE \${table} <set><if test=\"enabled\">a = #{id},</if></set>",
            "UPDATE t <set>\${table}<if test=\"enabled\">a = #{id},</if></set>",
            "UPDATE t <set><if test=\"enabled\">\${table} = #{id},</if></set>",
        )) {
            val contract = build(graph(rawBody, StatementKind.UPDATE), parameter(0, "boolean", "enabled"), parameter(1, "long", "id"), parameter(2, "java.lang.String", "table"))
            assertEquals("xml-if-raw-input-unsupported", contract.blockingProblems.single().code)
            assertTrue(contract.requirements.isEmpty())
        }
    }

    @Test
    fun siblingConditionsPreserveEachPlaceholderScopeAndAllRequiredCallerRoots() {
        for (wrapped in listOf(false, true)) {
            val body = "#{id}<if test=\"enabled\">#{id}</if>#{id}<if test=\"other\">#{id}</if>#{id}"
            val graph = graph("SELECT " + if (wrapped) "<where>$body</where>" else body)
            val sourceOnly = XmlStatementParameterContractFactory.build(graph)
            assertTrue(sourceOnly.isPreparationBlocked)
            assertTrue(sourceOnly.requirements.isEmpty())
            val contract = build(graph, parameter(0, "boolean", "enabled"), parameter(1, "java.lang.Boolean", "other"), parameter(2, "long", "id"))
            assertFalse(contract.isPreparationBlocked)
            assertEquals(3, contract.requirements.size)
            assertTrue(contract.requirements.all { it.requiredness == InputRequiredness.REQUIRED })
            val id = contract.requirement(contract.aliases.single { it.name == "id" }.requirementId)!!
            assertEquals(listOf(null, "enabled", null, "other", null), id.provenance.evidence.filterIsInstance<InputEvidence.Placeholder>().map { it.enclosingOgnlExpression })
            assertEquals(listOf("enabled"), conditions(contract.requirements[0]))
            assertEquals(listOf("other"), conditions(contract.requirements[1]))
            assertTrue(contract.internalBindings.isEmpty())
        }
    }

    @Test
    fun repeatedAndGenericConditionAliasesKeepOccurrencesWithoutDuplicatingCallerInputs() {
        val contract = build(
            graph("SELECT #{id} <if test=\"enabled\">#{id}</if><if test=\"param1\">#{id}</if><if test=\"enabled\">#{id}</if>"),
            parameter(0, "boolean", "enabled"), parameter(1, "long", "id"),
        )
        assertFalse(contract.isPreparationBlocked)
        assertEquals(2, contract.requirements.size)
        assertEquals(listOf("enabled", "enabled", "param1"), conditions(contract.requirements[0]))
        val id = contract.requirements[1]
        assertEquals(listOf(null, "enabled", "param1", "enabled"), id.provenance.evidence.filterIsInstance<InputEvidence.Placeholder>().map { it.enclosingOgnlExpression })
        assertEquals(contract.aliases.single { it.name == "enabled" }.requirementId, contract.aliases.single { it.name == "param1" }.requirementId)
    }

    @Test
    fun conditionalScopeChangeIsVisibleEvenWhenAliasAndPlaceholderCountsDoNotChange() {
        val outside = build(graph("SELECT #{id}<if test=\"enabled\">WHERE 1 = 1</if>"), parameter(0, "boolean", "enabled"), parameter(1, "long", "id"))
        val inside = build(graph("SELECT 1<if test=\"enabled\">WHERE id = #{id}</if>"), parameter(0, "boolean", "enabled"), parameter(1, "long", "id"))
        assertEquals(outside.aliases, inside.aliases)
        val outsideUse = outside.requirements[1].provenance.evidence.filterIsInstance<InputEvidence.Placeholder>().single()
        val insideUse = inside.requirements[1].provenance.evidence.filterIsInstance<InputEvidence.Placeholder>().single()
        assertEquals(outsideUse.expression, insideUse.expression)
        assertEquals(null, outsideUse.enclosingOgnlExpression)
        assertEquals("enabled", insideUse.enclosingOgnlExpression)
    }

    @Test
    fun everySiblingConditionNeedsBooleanMapperAuthority() {
        val graph = graph("SELECT 1<if test=\"enabled\">WHERE 1 = 1</if><if test=\"other\">AND 2 = 2</if>")
        for (other in listOf(parameter(1, "long", "other"), parameter(1, "boolean", "different"))) {
            assertTrue(build(graph, parameter(0, "boolean", "enabled"), other).isPreparationBlocked)
        }
        val mixed = graph("SELECT 1<if test=\"enabled\">WHERE 1 = 1</if><if test=\"other\">\${table}</if>")
        assertEquals("xml-if-raw-input-unsupported", build(mixed, parameter(0, "boolean", "enabled"), parameter(1, "boolean", "other"), parameter(2, "java.lang.String", "table")).blockingProblems.single().code)
    }

    @Test
    fun unsupportedLaterSiblingCannotLeaveAPartialContract() {
        for (later in listOf(
            "<if test=\"other\"><if test=\"enabled\">#{id}</if></if>",
            "<if test=\"other != null\">#{id}</if>",
            "<if test=\"other\" extra=\"ignored\">#{id}</if>",
            "<if xmlns=\"urn:unsupported\" test=\"other\">#{id}</if>",
            "<choose><when test=\"other\">#{id}</when></choose>",
        )) {
            val contract = build(graph("SELECT #{id}<if test=\"enabled\">#{id}</if>$later"), parameter(0, "boolean", "enabled"), parameter(1, "boolean", "other"), parameter(2, "long", "id"))
            assertTrue(later, contract.isPreparationBlocked)
            assertTrue(later, contract.requirements.isEmpty())
            assertTrue(later, contract.aliases.isEmpty())
        }
    }
    @Test
    fun whereConditionStillNeedsMapperAuthorityAndNeverCreatesAWhereInput() {
        val graph = graph("SELECT 1 <where><if test=\"enabled\">AND id = #{id}</if></where>")
        val sourceOnly = XmlStatementParameterContractFactory.build(graph)
        assertTrue(sourceOnly.isPreparationBlocked)
        assertTrue(sourceOnly.requirements.isEmpty())
        assertEquals(setOf("id", "enabled"), sourceOnly.blockingProblems.flatMap {
            it.provenance!!.evidence.map { evidence ->
                when (evidence) {
                    is InputEvidence.Placeholder -> evidence.expression
                    is InputEvidence.OgnlExpression -> evidence.expression
                    else -> throw AssertionError("unexpected source evidence: $evidence")
                }
            }
        }.toSet())

        val proven = build(graph, parameter(0, "boolean", "enabled"), parameter(1, "long", "id"))
        assertFalse(proven.isPreparationBlocked)
        assertEquals(setOf("enabled", "id"), proven.aliases.map { it.name }.toSet())
        assertTrue(proven.internalBindings.isEmpty())
        assertEquals(mapOf(XML_FILE to XML_REVISION, JAVA_FILE to JAVA_REVISION), proven.sourceRevisions)
    }

    @Test
    fun whereRetainsBoundUsesOutsideInsideAndAfterTheCondition() {
        val contract = build(
            graph("SELECT #{id} <where>AND id = #{id} <if test=\"enabled\">AND enabled = #{enabled} AND id = #{id}</if> AND id = #{id}</where> ORDER BY #{id}"),
            parameter(0, "boolean", "enabled"), parameter(1, "long", "id"),
        )
        assertFalse(contract.isPreparationBlocked)
        val enabled = contract.requirement(contract.aliases.single { it.name == "enabled" }.requirementId)!!
        assertEquals(listOf("enabled"), conditions(enabled))
        assertEquals(1, enabled.provenance.evidence.filterIsInstance<InputEvidence.Placeholder>().size)
        val id = contract.requirement(contract.aliases.single { it.name == "id" }.requirementId)!!
        assertEquals(5, id.provenance.evidence.filterIsInstance<InputEvidence.Placeholder>().size)
        assertTrue(id.provenance.evidence.filterIsInstance<InputEvidence.Placeholder>().all {
            it.source.sourceFileId == XML_FILE && it.source.sourceRevision == XML_REVISION
        })
    }

    @Test
    fun whereSupportsOnlyProvenPrimitiveOrBoxedBooleanAliases() {
        for (type in listOf("boolean", "java.lang.Boolean")) {
            for (alias in listOf("enabled", "param1")) {
                val contract = build(
                    graph("SELECT 1 <where><!-- no caller --> <if test=\" $alias \"><![CDATA[AND id = #{id}]]></if></where>"),
                    parameter(0, type, "enabled"), parameter(1, "long", "id"),
                )
                assertFalse("$type / $alias", contract.isPreparationBlocked)
                val requirement = contract.requirement(contract.aliases.single { it.name == alias }.requirementId)!!
                assertEquals(InputRequiredness.REQUIRED, requirement.requiredness)
                assertEquals(InputScalarType.BOOLEAN, requirement.expectedType.scalarType)
                assertEquals(listOf(alias), conditions(requirement))
            }
        }
        for (type in listOf("long", "java.lang.String", "java.util.Map<java.lang.String,java.lang.Boolean>")) {
            val contract = build(graph("SELECT 1 <where><if test=\"enabled\">AND 1 = 1</if></where>"), parameter(0, type, "enabled"))
            assertTrue(type, contract.blockingProblems.any { it.code == "xml-if-condition-type-unsupported" })
        }
    }

    @Test
    fun unsupportedWhereShapesFailWithoutPartialCallerAuthority() {
        val condition = "<if test=\"enabled\">AND id = #{id}</if>"
        val bodies = listOf(
            "<where/>", "<where>AND id = #{id}</where>",
            "<where><!-- $condition --> <![CDATA[$condition]]></where>",
            "<where extra=\"ignored\">$condition</where>",
            "<where prefixOverrides=\"OR\">$condition</where>",
            "<where xmlns=\"urn:unsupported\">$condition</where>",
            "<x:where xmlns:x=\"urn:unsupported\">$condition</x:where>",
            "<where><where>$condition</where></where>",
            "<where>$condition</where><where>$condition</where>",
            "$condition<where>$condition</where>",
            "<where>$condition</where>$condition",
            "<where>$condition<bind name=\"x\" value=\"1\"/></where>",
            "<where><include refid=\"fragment\"/>$condition</where>",
            "<where><foreach collection=\"ids\" item=\"id\">#{id}</foreach>$condition</where>",
            "<foreach collection=\"ids\" item=\"id\">#{id}</foreach><where>$condition</where>",
            "<where>$condition</where><foreach collection=\"ids\" item=\"id\">#{id}</foreach>",
            "<if test=\"enabled\"><where>AND id = #{id}</where></if>",
            "<trim>$condition</trim>", "<set>$condition</set>",
        ) + listOf("enabled != null", "enabled.value", "!enabled", "true", "_parameter", "").map {
            "<where><if test=\"$it\">AND id = #{id}</if></where>"
        }
        for (body in bodies) {
            val contract = build(graph("SELECT #{id} $body"), parameter(0, "boolean", "enabled"), parameter(1, "long", "id"))
            assertTrue(body, contract.isPreparationBlocked)
            assertTrue(body, contract.requirements.isEmpty())
            assertTrue(body, contract.aliases.isEmpty())
            assertTrue(body, contract.internalBindings.isEmpty())
        }
    }

    @Test
    fun rawInputAnywhereInWhereIslandRemainsUnsupported() {
        for (body in listOf(
            "\${table}<where><if test=\"enabled\">AND 1 = 1</if></where>",
            "<where>\${table}<if test=\"enabled\">AND 1 = 1</if></where>",
            "<where><if test=\"enabled\">AND \${table} = 1</if></where>",
        )) {
            val contract = build(graph(body), parameter(0, "boolean", "enabled"), parameter(1, "java.lang.String", "table"))
            assertEquals("xml-if-raw-input-unsupported", contract.blockingProblems.single().code)
            assertTrue(contract.requirements.isEmpty())
        }
    }

    @Test
    fun sourceConditionAloneCannotInventCallerAuthority() {
        val graph = graph("SELECT 1 <if test=\"enabled\">WHERE 1 = 1</if>")
        val contract = XmlStatementParameterContractFactory.build(graph)

        assertTrue(contract.isPreparationBlocked)
        assertTrue(contract.requirements.isEmpty())
        val problem = contract.blockingProblems.single()
        assertEquals("xml-caller-input-authority-unproven", problem.code)
        val condition = problem.provenance!!.evidence.single() as InputEvidence.OgnlExpression
        assertEquals("enabled", condition.expression)
        assertEquals(XML_FILE, condition.source.sourceFileId)
        assertEquals(XML_REVISION, condition.source.sourceRevision)
    }

    @Test
    fun explicitBooleanConditionIsRequiredEvenWithoutBoundPlaceholder() {
        for (type in listOf("boolean", "java.lang.Boolean")) {
            val graph = graph("SELECT 1 <if test=\" enabled \">WHERE 1 = 1</if>")
            val contract = build(graph, parameter(0, type, "enabled"))

            assertFalse(type, contract.isPreparationBlocked)
            val requirement = contract.requirements.single()
            assertEquals(InputRequiredness.REQUIRED, requirement.requiredness)
            assertEquals(InputScalarType.BOOLEAN, requirement.expectedType.scalarType)
            assertEquals(InputKind.BOUND, requirement.kind)
            assertEquals(InputAliasKind.EXPLICIT_PARAM, contract.aliases.single().kind)
            assertEquals(listOf("enabled"), conditions(requirement))
            assertTrue(requirement.provenance.evidence.none { it is InputEvidence.Placeholder })
            assertTrue(contract.internalBindings.isEmpty())
            assertEquals(mapOf(XML_FILE to XML_REVISION, JAVA_FILE to JAVA_REVISION), contract.sourceRevisions)
        }
    }

    @Test
    fun conditionAndBoundUsesShareOneCallerRequirementAndRetainBothEvidenceKinds() {
        val graph = graph("SELECT #{enabled} <if test=\"enabled\">, #{enabled}, #{id}</if>")
        val contract = build(graph, parameter(0, "boolean", "enabled"), parameter(1, "long", "id"))

        assertFalse(contract.isPreparationBlocked)
        assertEquals(2, contract.requirements.size)
        val enabled = contract.requirement(contract.aliases.single { it.name == "enabled" }.requirementId)!!
        assertEquals(listOf("enabled"), conditions(enabled))
        assertEquals(2, enabled.provenance.evidence.filterIsInstance<InputEvidence.Placeholder>().size)
        assertEquals(listOf("enabled", "id"), contract.aliases.map { it.name })
    }

    @Test
    fun provenGenericAliasRetainsMapperIndexAndNamingRule() {
        val graph = graph("SELECT 1 <if test=\"param2\">, #{param1}</if>")
        val contract = build(graph, parameter(0, "long", "id"), parameter(1, "boolean", "enabled"))

        assertFalse(contract.isPreparationBlocked)
        val alias = contract.aliases.single { it.name == "param2" }
        assertEquals(InputAliasKind.GENERIC_PARAM, alias.kind)
        val naming = alias.provenance.evidence.filterIsInstance<InputEvidence.GeneratedAlias>().single()
        assertEquals(1, naming.parameterIndex)
        assertEquals("mybatis-3.5.19-param-name-resolver-generic", naming.ruleId)
        assertEquals(listOf("param2"), conditions(contract.requirement(alias.requirementId)!!))
    }

    @Test
    fun conditionRequiresBooleanRatherThanOgnlTruthinessForOtherTypes() {
        for (type in listOf("long", "java.lang.String", "java.util.List<java.lang.Long>", "java.util.Map<java.lang.String,java.lang.Long>")) {
            val contract = build(graph("SELECT 1 <if test=\"enabled\">WHERE 1 = 1</if>"), parameter(0, type, "enabled"))
            assertTrue(type, contract.isPreparationBlocked)
            assertTrue(type, contract.blockingProblems.any { it.code == "xml-if-condition-type-unsupported" })
            val problem = contract.blockingProblems.single { it.code == "xml-if-condition-type-unsupported" }
            assertEquals(contract.requirements.single().id, problem.requirementId)
            assertEquals(contract.requirements.single().provenance, problem.provenance)
        }
    }

    @Test
    fun boundScalarFallbackDoesNotProveOgnlConditionName() {
        val graph = graph("SELECT #{value} <if test=\"enabled\">WHERE 1 = 1</if>")
        val contract = build(graph, parameter(0, "boolean", null))

        assertTrue(contract.isPreparationBlocked)
        assertTrue(contract.blockingProblems.any { it.code == "xml-caller-input-authority-unproven" })
        assertTrue(contract.aliases.none { it.name == "enabled" })
    }

    @Test
    fun missingAndDuplicateAliasesAndMapperIdentityDriftRemainBlocked() {
        val graph = graph("SELECT 1 <if test=\"enabled\">WHERE 1 = 1</if>")
        assertTrue(build(graph, parameter(0, "boolean", "other")).isPreparationBlocked)
        val duplicates = build(graph, parameter(0, "boolean", "enabled"), parameter(1, "boolean", "enabled"))
        assertEquals("xml-duplicate-explicit-param-alias", duplicates.blockingProblems.single().code)
        val drifted = XmlMapperMethodCapture(
            statementId = XmlStatementId(XML_FILE, "com.acme.Mapper", "other"),
            mapperSource = SourceSnapshot(JAVA_FILE, JAVA_REVISION, "x".repeat(100)),
            methodSourceRange = SourceRange(0, 100),
            parameters = listOf(parameter(0, "boolean", "enabled")),
        )
        val contract = XmlMapperMethodParameterContractFactory.build(graph, drifted)
        assertEquals("xml-mapper-method-authority-mismatch", contract.blockingProblems.single().code)
        assertTrue(contract.requirements.isEmpty())
    }

    @Test
    fun nestedMixedOrComplexDynamicSourcesCannotLeakPartialRequirements() {
        val bodies = listOf(
            "<if test=\"enabled\"><if test=\"other\">#{id}</if></if>",
            "<if test=\"enabled\">#{id}</if><foreach collection=\"ids\" item=\"id\">#{id}</foreach>",
            "<foreach collection=\"ids\" item=\"id\">#{id}</foreach><if test=\"enabled\">#{id}</if>",
            "<if test=\"enabled\"><include refid=\"fragment\"/></if>",
            "<if test=\"enabled\" extra=\"ignored\">#{id}</if>",
            "<if xmlns:x=\"urn:unsupported\" x:test=\"enabled\">#{id}</if>",
            "<if xmlns=\"urn:unsupported\" test=\"enabled\">#{id}</if>",
        ) + listOf("enabled != null", "enabled.value", "enabled[0]", "enabled()", "!enabled", "true", "false", "null", "and", "or", "new", "not", "eq", "instanceof", "_parameter", "_databaseId", "").map {
            "<if test=\"$it\">#{id}</if>"
        }
        bodies.forEach { body ->
            val contract = build(graph("SELECT #{id} $body"), parameter(0, "long", "id"), parameter(1, "boolean", "enabled"))
            assertTrue(body, contract.isPreparationBlocked)
            assertTrue(body, contract.requirements.isEmpty())
            assertTrue(body, contract.aliases.isEmpty())
        }
    }

    @Test
    fun rawInterpolationInIfIslandRemainsBlocked() {
        for (body in listOf(
            "<if test=\"enabled\">\${table}</if>",
            "\${table}<if test=\"enabled\">1</if>",
        )) {
            val contract = build(graph(body), parameter(0, "boolean", "enabled"), parameter(1, "java.lang.String", "table"))
            assertEquals("xml-if-raw-input-unsupported", contract.blockingProblems.single().code)
            assertTrue(contract.requirements.isEmpty())
        }
    }

    @Test
    fun commentsAndCdataDoNotCreateConditionalCallerInputs() {
        val contract = build(
            graph("SELECT 1 <!-- <if test=\"forged\"> --> <![CDATA[<if test=\"forged\">]]>"),
            parameter(0, "boolean", "forged"),
        )
        assertFalse(contract.isPreparationBlocked)
        assertTrue(contract.requirements.isEmpty())
    }

    private fun conditions(requirement: InputRequirement): List<String> =
        requirement.provenance.evidence.filterIsInstance<InputEvidence.OgnlExpression>().map { it.expression }

    private fun build(graph: StatementSourceGraph, vararg parameters: JavaMethodParameterMetadata): ParameterContract =
        XmlMapperMethodParameterContractFactory.build(
            graph,
            XmlMapperMethodCapture(
                statementId = graph.rootStatement.id as XmlStatementId,
                mapperSource = SourceSnapshot(JAVA_FILE, JAVA_REVISION, "x".repeat(100)),
                methodSourceRange = SourceRange(0, 100),
                parameters = parameters.toList(),
            ),
        )

    private fun graph(body: String, kind: StatementKind = StatementKind.SELECT): StatementSourceGraph {
        val element = kind.name.lowercase()
        val xml = "<mapper namespace=\"com.acme.Mapper\"><$element id=\"find\">$body</$element></mapper>"
        val start = xml.indexOf("<$element")
        return StatementSourceGraph(
            rootStatement = CapturedStatement(
                id = XmlStatementId(XML_FILE, "com.acme.Mapper", "find"),
                kind = kind,
                sourceRange = SourceRange(start, xml.indexOf('>', start) + 1),
            ),
            sourceSnapshots = listOf(SourceSnapshot(XML_FILE, XML_REVISION, xml)),
            dependencies = emptyList(),
        )
    }

    private fun parameter(index: Int, type: String, alias: String?): JavaMethodParameterMetadata =
        JavaMethodParameterMetadata(index, "source$index", JavaTypeIdentity(type), alias)

    companion object {
        private val XML_FILE = SourceFileId("file:///project/Mapper.xml")
        private val JAVA_FILE = SourceFileId("file:///project/Mapper.java")
        private val XML_REVISION = SourceRevision("xml-rev")
        private val JAVA_REVISION = SourceRevision("java-rev")
    }
}
