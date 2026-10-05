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

class XmlNamedMapPropertyContractTest {
    @Test
    fun explicitNamedMapPropertyKeepsRootAliasAndLeafProvenance() {
        val contract = build(
            graph("select * from users where id = #{payload.id}"),
            listOf(mapParameter(alias = "payload")),
        )

        assertFalse(contract.isPreparationBlocked)
        val requirement = contract.requirements.single()
        assertEquals(InputShape.MAP, requirement.expectedType.shape)
        assertEquals(listOf("payload"), contract.aliases.map { it.name })
        assertEquals(InputAliasKind.EXPLICIT_PARAM, contract.aliases.single().kind)
        assertTrue(contract.aliases.none { it.name == "payload.id" })

        val evidence = requirement.provenance.evidence
            .filterIsInstance<InputEvidence.NamedMapProperty>()
            .single()
        assertEquals(0, evidence.parameterIndex)
        assertEquals("payload", evidence.alias)
        assertEquals("payload.id", evidence.mappingProperty)
        assertEquals("id", evidence.key)
        assertEquals(
            "mybatis-3.5.19-default-parameter-handler-named-map-property",
            evidence.ruleId,
        )
        assertTrue(
            requirement.provenance.evidence.any {
                it is InputEvidence.Placeholder && it.expression == "payload.id"
            },
        )
        assertTrue(requirement.provenance.evidence.none { it is InputEvidence.ParameterObjectProperty })
    }

    @Test
    fun siblingPropertiesShareOneRequirementAndRootAlias() {
        val contract = build(
            graph("select * from users where id = #{payload.id} or parent_id = #{payload.parentId}"),
            listOf(mapParameter(alias = "payload")),
        )

        assertFalse(contract.isPreparationBlocked)
        assertEquals(1, contract.requirements.size)
        assertEquals(listOf("payload"), contract.aliases.map { it.name })
        assertEquals(
            setOf("payload.id", "payload.parentId"),
            contract.requirements.single().provenance.evidence
                .filterIsInstance<InputEvidence.NamedMapProperty>()
                .mapTo(linkedSetOf()) { it.mappingProperty },
        )
    }

    @Test
    fun deterministicGenericAliasMayOwnNamedMapProperty() {
        val contract = build(
            graph("select * from users where id = #{param1.id}"),
            listOf(mapParameter(alias = "payload")),
        )

        assertFalse(contract.isPreparationBlocked)
        assertEquals(listOf("param1"), contract.aliases.map { it.name })
        assertEquals(InputAliasKind.GENERIC_PARAM, contract.aliases.single().kind)
        val evidence = contract.requirements.single().provenance.evidence
            .filterIsInstance<InputEvidence.NamedMapProperty>()
            .single()
        assertEquals("param1", evidence.alias)
        assertEquals("param1.id", evidence.mappingProperty)
    }

    @Test
    fun unsupportedMapShapesRemainBlocked() {
        val unsupported = listOf(
            "java.util.Map",
            "java.util.Map<java.lang.Long,java.lang.Long>",
            "java.util.Map<java.lang.String,java.util.UUID>",
            "java.util.Map<java.lang.String,java.util.List<java.lang.Long>>",
            "java.util.Map<java.lang.String,fixture.CustomValue>",
        )

        unsupported.forEach { type ->
            val contract = build(
                graph("select * from users where id = #{payload.id}"),
                listOf(
                    JavaMethodParameterMetadata(
                        index = 0,
                        sourceName = "payload",
                        typeIdentity = JavaTypeIdentity(type),
                        myBatisParamAlias = "payload",
                    ),
                ),
            )
            assertTrue("expected blocked type: $type", contract.isPreparationBlocked)
            assertTrue(
                contract.requirements.none {
                    it.provenance.evidence.any { evidence -> evidence is InputEvidence.NamedMapProperty }
                },
            )
        }
    }

    @Test
    fun deepBracketRawAndUnprovenAliasesRemainBlocked() {
        listOf("payload.user.id", "payload[id]").forEach { expression ->
            val contract = build(
                graph("select * from users where id = #{$expression}"),
                listOf(mapParameter(alias = "payload")),
            )
            assertTrue("expected blocked expression: $expression", contract.isPreparationBlocked)
        }

        val raw = build(
            graph("select * from " + rawToken("payload.id")),
            listOf(
                JavaMethodParameterMetadata(
                    index = 0,
                    sourceName = "payload",
                    typeIdentity = JavaTypeIdentity("java.util.Map<java.lang.String,java.lang.String>"),
                    myBatisParamAlias = "payload",
                ),
            ),
        )
        assertTrue(raw.isPreparationBlocked)

        val sourceNameOnly = build(
            graph("select * from users where id = #{payload.id}"),
            listOf(mapParameter(alias = null)),
        )
        assertTrue(sourceNameOnly.isPreparationBlocked)
    }

    @Test
    fun directParameterObjectMapRuleRemainsDistinct() {
        val contract = build(
            graph("select * from users where id = #{id}"),
            listOf(mapParameter(alias = null)),
        )

        assertFalse(contract.isPreparationBlocked)
        assertEquals(InputAliasKind.PARAMETER_OBJECT, contract.aliases.single().kind)
        assertTrue(
            contract.requirements.single().provenance.evidence
                .any { it is InputEvidence.ParameterObjectProperty },
        )
        assertTrue(
            contract.requirements.single().provenance.evidence
                .none { it is InputEvidence.NamedMapProperty },
        )
    }

    private fun mapParameter(alias: String?) = JavaMethodParameterMetadata(
        index = 0,
        sourceName = "payload",
        typeIdentity = JavaTypeIdentity("java.util.Map<java.lang.String,java.lang.Long>"),
        myBatisParamAlias = alias,
    )

    private fun build(
        graph: StatementSourceGraph,
        parameters: List<JavaMethodParameterMetadata>,
    ): ParameterContract = XmlMapperMethodParameterContractFactory.build(
        graph,
        XmlMapperMethodCapture(
            statementId = graph.rootStatement.id as XmlStatementId,
            mapperSource = SourceSnapshot(JAVA_FILE, JAVA_REVISION, "x".repeat(256)),
            methodSourceRange = SourceRange(20, 120),
            parameters = parameters,
        ),
    )

    private fun graph(body: String): StatementSourceGraph {
        val statementId = XmlStatementId(XML_FILE, "example.Mapper", "find")
        val declaration = "<select id=\"find\">$body</select>"
        val xml = "<mapper namespace=\"example.Mapper\">$declaration</mapper>"
        val start = xml.indexOf("<select id=\"find\">")
        val end = xml.indexOf('>', start) + 1
        return StatementSourceGraph(
            rootStatement = CapturedStatement(
                statementId,
                StatementKind.SELECT,
                SourceRange(start, end),
            ),
            sourceSnapshots = listOf(SourceSnapshot(XML_FILE, XML_REVISION, xml)),
            dependencies = emptyList(),
        )
    }

    private fun rawToken(name: String): String = "$" + "{$name}"

    private companion object {
        val XML_FILE = SourceFileId("vfs:/mapper.xml")
        val XML_REVISION = SourceRevision("xml-r1")
        val JAVA_FILE = SourceFileId("vfs:/Mapper.java")
        val JAVA_REVISION = SourceRevision("java-r1")
    }
}
