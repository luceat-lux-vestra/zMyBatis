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
            "<where>$condition$condition</where>",
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
    fun nestedMultipleMixedOrComplexDynamicSourcesCannotLeakPartialRequirements() {
        val bodies = listOf(
            "<if test=\"enabled\"><if test=\"other\">#{id}</if></if>",
            "<if test=\"enabled\">#{id}</if><if test=\"enabled\">#{id}</if>",
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

    private fun graph(body: String): StatementSourceGraph {
        val xml = "<mapper namespace=\"com.acme.Mapper\"><select id=\"find\">$body</select></mapper>"
        val start = xml.indexOf("<select")
        return StatementSourceGraph(
            rootStatement = CapturedStatement(
                id = XmlStatementId(XML_FILE, "com.acme.Mapper", "find"),
                kind = StatementKind.SELECT,
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
