package com.algorist.zMyBatis.core.input

import com.algorist.zMyBatis.core.source.CapturedStatement
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.StatementSourceGraph
import com.algorist.zMyBatis.core.source.XmlStatementId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlStatementParameterContractFactoryTest {
    @Test
    fun rawRootProducesExecutableSourceProvenContract() {
        val graph = graph(
            body = "select * from " + raw("table"),
        )

        val contract = XmlStatementParameterContractFactory.build(graph)

        assertFalse(contract.isPreparationBlocked)
        assertEquals(graph.rootStatement.id, contract.statementId)
        assertEquals(mapOf(ROOT_FILE to ROOT_REVISION), contract.sourceRevisions)
        assertEquals(1, contract.requirements.size)

        val requirement = contract.requirements.single()
        assertEquals(InputRequirementId("xml-root:table"), requirement.id)
        assertEquals(InputKind.RAW_INTERPOLATION, requirement.kind)
        assertEquals(InputShape.RAW_TEXT, requirement.expectedType.shape)
        assertEquals(InputScalarType.STRING, requirement.expectedType.scalarType)
        assertEquals(InputNullability.NON_NULL, requirement.expectedType.nullability)
        assertEquals(InputRequiredness.REQUIRED, requirement.requiredness)

        val alias = contract.aliases.single()
        assertEquals("table", alias.name)
        assertEquals(requirement.id, alias.requirementId)
        assertEquals(InputAliasKind.XML_PLACEHOLDER_ROOT, alias.kind)

        val placeholder = requirement.provenance.evidence.single() as InputEvidence.Placeholder
        assertEquals(InputKind.RAW_INTERPOLATION, placeholder.kind)
        assertEquals("table", placeholder.expression)
        assertEquals(ROOT_FILE, placeholder.source.sourceFileId)
        assertEquals(ROOT_REVISION, placeholder.source.sourceRevision)
        assertEquals(graph.rootStatement.sourceRange, placeholder.source.sourceRange)
    }

    @Test
    fun boundRootIsDiscoveredButShapeRemainsExplicitlyUnknownAndBlocking() {
        val graph = graph(
            body = "select * from users where id = #{id,jdbcType=BIGINT}",
        )

        val contract = XmlStatementParameterContractFactory.build(graph)

        assertTrue(contract.isPreparationBlocked)
        val requirement = contract.requirements.single()
        assertEquals(InputRequirementId("xml-root:id"), requirement.id)
        assertEquals(InputKind.BOUND, requirement.kind)
        assertEquals(InputShape.UNKNOWN, requirement.expectedType.shape)
        assertEquals(InputScalarType.UNKNOWN, requirement.expectedType.scalarType)
        assertEquals(InputNullability.UNKNOWN, requirement.expectedType.nullability)

        val problem = contract.blockingProblems.single()
        assertEquals(InputContractProblemKind.UNKNOWN, problem.kind)
        assertEquals("xml-unproven-bound-input-shape", problem.code)
        assertEquals(requirement.id, problem.requirementId)
        assertEquals(requirement.provenance, problem.provenance)

        assertEquals("id", contract.aliases.single().name)
        assertEquals(InputAliasKind.XML_PLACEHOLDER_ROOT, contract.aliases.single().kind)
    }

    @Test
    fun unrelatedStatementCannotCreateGhostRequirements() {
        val graph = graph(
            body = "select * from " + raw("table"),
            trailingDeclarations = "<select id=\"other\">select #{ghost} from " + raw("ghostRaw") + "</select>",
        )

        val contract = XmlStatementParameterContractFactory.build(graph)

        assertFalse(contract.isPreparationBlocked)
        assertEquals(listOf("table"), contract.aliases.map { it.name })
        assertEquals(listOf(InputRequirementId("xml-root:table")), contract.requirements.map { it.id })
    }

    @Test
    fun commentsDoNotCreateInputsAndCdataTextDoes() {
        val graph = graph(
            body = buildString {
                append("select * from ")
                append(raw("table"))
                append("<!-- ")
                append(raw("ghost"))
                append(" -->")
                append("<![CDATA[ order by ")
                append(raw("column"))
                append(" ]]>")
            },
        )

        val contract = XmlStatementParameterContractFactory.build(graph)

        assertFalse(contract.isPreparationBlocked)
        assertEquals(listOf("table", "column"), contract.aliases.map { it.name })
        assertEquals(
            setOf("table", "column"),
            contract.requirements.map { it.id.value.removePrefix("xml-root:") }.toSet(),
        )
    }

    @Test
    fun escapedOpenTokenMatchesMyBatisAndDoesNotBecomeCallerInput() {
        val graph = graph(
            body = "select \\#{literal}, * from " + raw("table"),
        )

        val contract = XmlStatementParameterContractFactory.build(graph)

        assertFalse(contract.isPreparationBlocked)
        assertEquals(listOf("table"), contract.aliases.map { it.name })
        assertEquals(1, contract.requirements.size)
    }

    @Test
    fun standardMapperDoctypeRemainsLocallyParseable() {
        val graph = graph(
            body = "select * from " + raw("table"),
            withDoctype = true,
        )

        val contract = XmlStatementParameterContractFactory.build(graph)

        assertFalse(contract.isPreparationBlocked)
        assertEquals("table", contract.aliases.single().name)
    }

    @Test
    fun statementWithoutPlaceholdersProducesEmptyNonBlockingContract() {
        val contract = XmlStatementParameterContractFactory.build(
            graph(body = "select 1"),
        )

        assertFalse(contract.isPreparationBlocked)
        assertTrue(contract.requirements.isEmpty())
        assertTrue(contract.aliases.isEmpty())
        assertTrue(contract.internalBindings.isEmpty())
        assertTrue(contract.blockingProblems.isEmpty())
    }

    private fun graph(
        body: String,
        trailingDeclarations: String = "",
        withDoctype: Boolean = false,
    ): StatementSourceGraph {
        val element = "select"
        val declaration = "<$element id=\"find\">$body</$element>"
        val doctype = if (withDoctype) {
            "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" " +
                "\"https://mybatis.org/dtd/mybatis-3-mapper.dtd\">"
        } else {
            ""
        }
        val xml = doctype +
            "<mapper namespace=\"com.acme.UserMapper\">" +
            declaration +
            trailingDeclarations +
            "</mapper>"
        val start = xml.indexOf("<$element id=\"find\">")
        val end = xml.indexOf('>', start) + 1
        val statementId = XmlStatementId(ROOT_FILE, "com.acme.UserMapper", "find")
        return StatementSourceGraph(
            rootStatement = CapturedStatement(
                id = statementId,
                kind = StatementKind.SELECT,
                sourceRange = SourceRange(start, end),
            ),
            sourceSnapshots = listOf(SourceSnapshot(ROOT_FILE, ROOT_REVISION, xml)),
            dependencies = emptyList(),
        )
    }

    private fun raw(name: String): String = 36.toChar().toString() + "{" + name + "}"

    private companion object {
        val ROOT_FILE = SourceFileId("src/main/resources/com/acme/UserMapper.xml")
        val ROOT_REVISION = SourceRevision("document:73")
    }
}
