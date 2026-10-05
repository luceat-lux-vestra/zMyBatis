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

class XmlSingleParameterObjectContractTest {
    @Test
    fun soleUnannotatedScalarUsesExplicitParameterObjectFallback() {
        val graph = graph("select * from users where id = #{arbitrary}")
        val contract = build(graph, listOf(parameter(0, "long", "id", null)))

        assertFalse(contract.isPreparationBlocked)
        val requirement = contract.requirements.single()
        assertEquals(InputRequirementId("xml-java-param:0"), requirement.id)
        assertEquals(InputKind.BOUND, requirement.kind)
        assertEquals(InputShape.SCALAR, requirement.expectedType.shape)
        assertEquals(InputScalarType.INTEGER, requirement.expectedType.scalarType)

        val alias = contract.aliases.single()
        assertEquals("arbitrary", alias.name)
        assertEquals(InputAliasKind.PARAMETER_OBJECT, alias.kind)
        assertEquals(requirement.id, alias.requirementId)

        val fallback = requirement.provenance.evidence
            .filterIsInstance<InputEvidence.ParameterObjectFallback>()
            .single()
        assertEquals(0, fallback.parameterIndex)
        assertEquals("arbitrary", fallback.mappingProperty)
        assertEquals(
            "mybatis-3.5.19-default-parameter-handler-type-handler-fallback",
            fallback.ruleId,
        )
        assertTrue(
            requirement.provenance.evidence.any {
                it is InputEvidence.MapperMethodParameter &&
                    it.index == 0 &&
                    it.sourceName == "id"
            },
        )
        assertTrue(
            requirement.provenance.evidence.any {
                it is InputEvidence.Placeholder &&
                    it.kind == InputKind.BOUND &&
                    it.expression == "arbitrary"
            },
        )
    }

    @Test
    fun distinctMappingPropertiesShareOneSoleScalarRequirement() {
        val graph = graph("select * from users where id = #{left} or parent_id = #{right}")
        val contract = build(graph, listOf(parameter(0, "long", "id", null)))

        assertFalse(contract.isPreparationBlocked)
        assertEquals(1, contract.requirements.size)
        assertEquals(listOf("left", "right"), contract.aliases.map { it.name })
        assertTrue(contract.aliases.all { it.kind == InputAliasKind.PARAMETER_OBJECT })
        assertEquals(
            setOf("left", "right"),
            contract.requirements.single().provenance.evidence
                .filterIsInstance<InputEvidence.ParameterObjectFallback>()
                .mapTo(linkedSetOf()) { it.mappingProperty },
        )
        assertEquals(
            listOf("left", "right"),
            contract.requirements.single().provenance.evidence
                .filterIsInstance<InputEvidence.Placeholder>()
                .map { it.expression },
        )
    }

    @Test
    fun soleUnannotatedTemporalParameterUsesParameterObjectFallback() {
        val graph = graph("select * from audit where created_on = #{value}")
        val contract = build(
            graph,
            listOf(parameter(0, "java.time.LocalDate", "createdOn", null)),
        )

        assertFalse(contract.isPreparationBlocked)
        val requirement = contract.requirements.single()
        assertEquals(InputShape.TEMPORAL, requirement.expectedType.shape)
        assertEquals(InputScalarType.DATE, requirement.expectedType.scalarType)
        assertEquals(InputAliasKind.PARAMETER_OBJECT, contract.aliases.single().kind)
    }

    @Test
    fun explicitParamKeepsExistingAliasAuthorityInsteadOfFallback() {
        val graph = graph("select * from users where id = #{id}")
        val contract = build(graph, listOf(parameter(0, "long", "sourceId", "id")))

        assertFalse(contract.isPreparationBlocked)
        assertEquals(InputAliasKind.EXPLICIT_PARAM, contract.aliases.single().kind)
        assertTrue(
            contract.requirements.single().provenance.evidence
                .none { it is InputEvidence.ParameterObjectFallback },
        )
    }

    @Test
    fun multiParameterAndStructuredArbitraryRootsRemainBlocked() {
        val multi = build(
            graph("select * from users where id = #{value}"),
            listOf(
                parameter(0, "long", "id", null),
                parameter(1, "java.lang.String", "name", null),
            ),
        )
        assertTrue(multi.isPreparationBlocked)
        assertTrue(multi.requirements.isEmpty())
        assertTrue(multi.aliases.isEmpty())

        val list = build(
            graph("select * from users where id = #{value}"),
            listOf(parameter(0, "java.util.List<java.lang.Long>", "ids", null)),
        )
        assertTrue(list.isPreparationBlocked)
        assertTrue(list.aliases.none { it.kind == InputAliasKind.PARAMETER_OBJECT })

        val map = build(
            graph("select * from users where id = #{value}"),
            listOf(parameter(0, "java.util.Map<java.lang.String,java.lang.Long>", "payload", null)),
        )
        assertTrue(map.isPreparationBlocked)
        assertTrue(map.aliases.none { it.kind == InputAliasKind.PARAMETER_OBJECT })
    }

    @Test
    fun rawAndNestedUsesDoNotGainParameterObjectFallback() {
        val raw = build(
            graph("select * from " + rawToken("table")),
            listOf(parameter(0, "java.lang.String", "table", null)),
        )
        assertTrue(raw.isPreparationBlocked)
        assertTrue(raw.aliases.none { it.kind == InputAliasKind.PARAMETER_OBJECT })

        val nested = build(
            graph("select * from users where id = #{payload.id}"),
            listOf(parameter(0, "long", "id", null)),
        )
        assertTrue(nested.isPreparationBlocked)
        assertTrue(nested.aliases.none { it.kind == InputAliasKind.PARAMETER_OBJECT })
    }

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

    private fun parameter(
        index: Int,
        type: String,
        sourceName: String?,
        alias: String?,
    ) = JavaMethodParameterMetadata(
        index = index,
        sourceName = sourceName,
        typeIdentity = JavaTypeIdentity(type),
        myBatisParamAlias = alias,
    )

    private fun rawToken(name: String): String = "$" + "{$name}"

    private companion object {
        val XML_FILE = SourceFileId("vfs:/mapper.xml")
        val XML_REVISION = SourceRevision("xml-r1")
        val JAVA_FILE = SourceFileId("vfs:/Mapper.java")
        val JAVA_REVISION = SourceRevision("java-r1")
    }
}
