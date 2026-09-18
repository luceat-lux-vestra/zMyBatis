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
    fun rawRootIsPreservedAsSourceEvidenceButDoesNotInventCallerAuthority() {
        val graph = graph(body = "select * from " + raw("table"))

        val contract = XmlStatementParameterContractFactory.build(graph)

        assertTrue(contract.isPreparationBlocked)
        assertEquals(graph.rootStatement.id, contract.statementId)
        assertEquals(mapOf(ROOT_FILE to ROOT_REVISION), contract.sourceRevisions)
        assertTrue(contract.requirements.isEmpty())
        assertTrue(contract.aliases.isEmpty())

        val problem = contract.blockingProblems.single()
        assertEquals(InputContractProblemKind.UNKNOWN, problem.kind)
        assertEquals("xml-caller-input-authority-unproven", problem.code)
        assertEquals(null, problem.requirementId)
        val placeholder = problem.provenance!!.evidence.single() as InputEvidence.Placeholder
        assertEquals(InputKind.RAW_INTERPOLATION, placeholder.kind)
        assertEquals("table", placeholder.expression)
        assertEquals(ROOT_FILE, placeholder.source.sourceFileId)
        assertEquals(ROOT_REVISION, placeholder.source.sourceRevision)
        assertEquals(graph.rootStatement.sourceRange, placeholder.source.sourceRange)
    }

    @Test
    fun boundRootWithOptionsPreservesLeadingPropertyEvidenceButStillBlocksAuthority() {
        val contract = XmlStatementParameterContractFactory.build(
            graph(body = "select * from users where id = #{id,jdbcType=BIGINT}"),
        )

        assertTrue(contract.isPreparationBlocked)
        assertTrue(contract.requirements.isEmpty())
        assertTrue(contract.aliases.isEmpty())
        val problem = contract.blockingProblems.single()
        assertEquals(InputContractProblemKind.UNKNOWN, problem.kind)
        assertEquals("xml-caller-input-authority-unproven", problem.code)
        val placeholder = problem.provenance!!.evidence.single() as InputEvidence.Placeholder
        assertEquals(InputKind.BOUND, placeholder.kind)
        assertEquals("id", placeholder.expression)
    }

    @Test
    fun unrelatedStatementCannotCreateGhostPlaceholderProblems() {
        val contract = XmlStatementParameterContractFactory.build(
            graph(
                body = "select * from " + raw("table"),
                trailingDeclarations =
                    "<select id=\"other\">select #{ghost} from " + raw("ghostRaw") + "</select>",
            ),
        )

        assertEquals(1, contract.blockingProblems.size)
        val placeholder = contract.blockingProblems.single().provenance!!
            .evidence.single() as InputEvidence.Placeholder
        assertEquals("table", placeholder.expression)
    }

    @Test
    fun commentsDoNotCreateEvidenceAndCdataTextDoes() {
        val contract = XmlStatementParameterContractFactory.build(
            graph(
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
            ),
        )

        assertTrue(contract.isPreparationBlocked)
        assertEquals(
            listOf("table", "column"),
            contract.blockingProblems.map { problem ->
                (problem.provenance!!.evidence.single() as InputEvidence.Placeholder).expression
            },
        )
        assertTrue(
            contract.blockingProblems.all { it.code == "xml-caller-input-authority-unproven" },
        )
    }

    @Test
    fun escapedOpenTokenMatchesMyBatisAndDoesNotCreateCallerEvidence() {
        val contract = XmlStatementParameterContractFactory.build(
            graph(body = "select \\#{literal}"),
        )

        assertFalse(contract.isPreparationBlocked)
        assertTrue(contract.requirements.isEmpty())
        assertTrue(contract.aliases.isEmpty())
        assertTrue(contract.blockingProblems.isEmpty())
    }

    @Test
    fun standardMapperDoctypeRemainsLocallyParseable() {
        val contract = XmlStatementParameterContractFactory.build(
            graph(body = "select 1", withDoctype = true),
        )

        assertFalse(contract.isPreparationBlocked)
        assertTrue(contract.blockingProblems.isEmpty())
    }

    @Test
    fun placeholderFreeStatementProducesEmptyNonBlockingContract() {
        val contract = XmlStatementParameterContractFactory.build(
            graph(body = "select 1"),
        )

        assertFalse(contract.isPreparationBlocked)
        assertTrue(contract.requirements.isEmpty())
        assertTrue(contract.aliases.isEmpty())
        assertTrue(contract.internalBindings.isEmpty())
        assertTrue(contract.blockingProblems.isEmpty())
    }

    @Test
    fun repeatedSameRootCollapsesToOneAuthorityProblemWithAllUseEvidence() {
        val contract = XmlStatementParameterContractFactory.build(
            graph(body = "select * from users where id = #{id} or parent_id = #{id}"),
        )

        assertTrue(contract.isPreparationBlocked)
        assertEquals(1, contract.blockingProblems.size)
        val evidence = contract.blockingProblems.single().provenance!!.evidence
            .filterIsInstance<InputEvidence.Placeholder>()
        assertEquals(2, evidence.size)
        assertTrue(evidence.all { it.kind == InputKind.BOUND && it.expression == "id" })
    }

    @Test
    fun differentRawAndBoundRootsRemainIndependentUnknownAuthorityProblems() {
        val contract = XmlStatementParameterContractFactory.build(
            graph(body = "select * from " + raw("table") + " where id = #{id}"),
        )

        assertTrue(contract.isPreparationBlocked)
        assertEquals(
            listOf(
                "xml-caller-input-authority-unproven",
                "xml-caller-input-authority-unproven",
            ),
            contract.blockingProblems.map { it.code },
        )
        assertEquals(
            listOf(InputKind.RAW_INTERPOLATION, InputKind.BOUND),
            contract.blockingProblems.map {
                (it.provenance!!.evidence.single() as InputEvidence.Placeholder).kind
            },
        )
    }

    @Test
    fun unrelatedSnapshotWithoutDependencyPreservesRevisionWithoutCreatingEvidence() {
        val root = graph(body = "select * from " + raw("table"))
        val unrelatedFile = SourceFileId("src/main/resources/com/acme/Unrelated.xml")
        val unrelatedRevision = SourceRevision("document:99")
        val graph = StatementSourceGraph(
            rootStatement = root.rootStatement,
            sourceSnapshots = root.sourceSnapshots + SourceSnapshot(
                unrelatedFile,
                unrelatedRevision,
                "<mapper namespace=\"com.acme.Unrelated\"><select id=\"x\">#{ghost}</select></mapper>",
            ),
            dependencies = emptyList(),
        )

        val contract = XmlStatementParameterContractFactory.build(graph)

        assertEquals(
            mapOf(ROOT_FILE to ROOT_REVISION, unrelatedFile to unrelatedRevision),
            contract.sourceRevisions,
        )
        assertEquals(1, contract.blockingProblems.size)
        val placeholder = contract.blockingProblems.single().provenance!!
            .evidence.single() as InputEvidence.Placeholder
        assertEquals("table", placeholder.expression)
        assertEquals(ROOT_FILE, placeholder.source.sourceFileId)
    }

    private fun graph(
        body: String,
        trailingDeclarations: String = "",
        withDoctype: Boolean = false,
    ): StatementSourceGraph {
        val declaration = "<select id=\"find\">$body</select>"
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
        val start = xml.indexOf("<select id=\"find\">")
        val end = xml.indexOf('>', start) + 1
        return StatementSourceGraph(
            rootStatement = CapturedStatement(
                id = XmlStatementId(ROOT_FILE, "com.acme.UserMapper", "find"),
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
