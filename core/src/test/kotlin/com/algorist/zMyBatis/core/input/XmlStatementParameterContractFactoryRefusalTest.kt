package com.algorist.zMyBatis.core.input

import com.algorist.zMyBatis.core.source.CapturedStatement
import com.algorist.zMyBatis.core.source.SourceDependencyEdge
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.StatementSourceGraph
import com.algorist.zMyBatis.core.source.XmlStatementId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlStatementParameterContractFactoryRefusalTest {
    @Test
    fun dependencyBackedGraphBlocksUntilTargetFragmentProvenanceExists() {
        val root = graph(body = "select * from users")
        val dependentFile = SourceFileId("src/main/resources/com/acme/Common.xml")
        val dependentSnapshot = SourceSnapshot(
            dependentFile,
            SourceRevision("document:9"),
            "<mapper namespace=\"com.acme.Common\"><sql id=\"columns\">id</sql></mapper>",
        )
        val graph = StatementSourceGraph(
            rootStatement = root.rootStatement,
            sourceSnapshots = root.sourceSnapshots + dependentSnapshot,
            dependencies = listOf(
                SourceDependencyEdge(
                    dependentFileId = ROOT_FILE,
                    requiredFileId = dependentFile,
                    referenceRange = root.rootStatement.sourceRange,
                ),
            ),
        )

        val contract = XmlStatementParameterContractFactory.build(graph)

        assertTrue(contract.isPreparationBlocked)
        assertTrue(contract.requirements.isEmpty())
        assertEquals(
            listOf("xml-dependent-fragment-provenance-unsupported"),
            contract.blockingProblems.map { it.code },
        )
        assertEquals(InputContractProblemKind.UNSUPPORTED, contract.blockingProblems.single().kind)
        assertEquals(setOf(ROOT_FILE, dependentFile), contract.sourceRevisions.keys)
    }

    @Test
    fun nestedMapperElementBlocksBeforePartialPlaceholderRequirementsEscape() {
        val contract = XmlStatementParameterContractFactory.build(
            graph(body = "<if test=\"id != null\">where id = #{id}</if>"),
        )

        assertTrue(contract.isPreparationBlocked)
        assertTrue(contract.requirements.isEmpty())
        assertEquals(
            listOf("xml-nested-element-input-discovery-unsupported"),
            contract.blockingProblems.map { it.code },
        )
    }

    @Test
    fun dottedOrComplexPlaceholderExpressionFailsClosed() {
        val dotted = XmlStatementParameterContractFactory.build(
            graph(body = "select * from users where id = #{user.id}"),
        )
        val indexed = XmlStatementParameterContractFactory.build(
            graph(body = "select * from users where name = #{users[0]}"),
        )

        listOf(dotted, indexed).forEach { contract ->
            assertTrue(contract.isPreparationBlocked)
            assertTrue(contract.requirements.isEmpty())
            assertEquals(
                listOf("xml-complex-placeholder-expression"),
                contract.blockingProblems.map { it.code },
            )
            assertEquals(InputContractProblemKind.UNSUPPORTED, contract.blockingProblems.single().kind)
        }
    }

    @Test
    fun malformedOpenTokenFailsClosed() {
        val contract = XmlStatementParameterContractFactory.build(
            graph(body = "select * from users where id = #{id"),
        )

        assertTrue(contract.isPreparationBlocked)
        assertTrue(contract.requirements.isEmpty())
        assertEquals(
            listOf("xml-malformed-placeholder"),
            contract.blockingProblems.map { it.code },
        )
        assertEquals(InputContractProblemKind.UNKNOWN, contract.blockingProblems.single().kind)
    }

    @Test
    fun oneRootCannotBecomeIndependentRawAndBoundInputs() {
        val contract = XmlStatementParameterContractFactory.build(
            graph(body = "select " + raw("value") + " from users where name = #{value}"),
        )

        assertTrue(contract.isPreparationBlocked)
        assertTrue(contract.requirements.isEmpty())
        assertTrue(contract.aliases.isEmpty())
        val problem = contract.blockingProblems.single()
        assertEquals(InputContractProblemKind.AMBIGUOUS, problem.kind)
        assertEquals("xml-mixed-raw-bound-input", problem.code)
        assertEquals(
            setOf(InputKind.BOUND, InputKind.RAW_INTERPOLATION),
            problem.provenance!!.evidence
                .filterIsInstance<InputEvidence.Placeholder>()
                .map { it.kind }
                .toSet(),
        )
    }

    @Test
    fun reservedMyBatisContextRootNeverBecomesCallerInput() {
        listOf("_parameter", "_databaseId").forEach { reserved ->
            val contract = XmlStatementParameterContractFactory.build(
                graph(body = "select " + raw(reserved)),
            )

            assertTrue(contract.isPreparationBlocked)
            assertTrue(contract.requirements.isEmpty())
            assertTrue(contract.aliases.isEmpty())
            assertEquals(
                listOf("xml-reserved-internal-placeholder-root"),
                contract.blockingProblems.map { it.code },
            )
        }
    }

    @Test
    fun namespaceAndStatementKindAreCrossCheckedAgainstAuthoritativeGraph() {
        val namespaceMismatch = XmlStatementParameterContractFactory.build(
            graph(
                body = "select 1",
                xmlNamespace = "com.acme.ActualMapper",
                idNamespace = "com.acme.ClaimedMapper",
            ),
        )
        val kindMismatch = XmlStatementParameterContractFactory.build(
            graph(
                body = "update users set active = 1",
                elementName = "update",
                kind = StatementKind.SELECT,
            ),
        )

        listOf(namespaceMismatch, kindMismatch).forEach { contract ->
            assertTrue(contract.isPreparationBlocked)
            assertEquals(
                listOf("xml-parameter-contract-root-mismatch"),
                contract.blockingProblems.map { it.code },
            )
            assertEquals(InputContractProblemKind.UNKNOWN, contract.blockingProblems.single().kind)
        }
    }

    @Test
    fun malformedRootDocumentFailsTypedInsteadOfInventingInputs() {
        val xml = "<mapper namespace=\"com.acme.UserMapper\"><select id=\"find\">" + raw("table")
        val start = xml.indexOf("<select")
        val end = xml.indexOf('>', start) + 1
        val graph = StatementSourceGraph(
            rootStatement = CapturedStatement(
                XmlStatementId(ROOT_FILE, "com.acme.UserMapper", "find"),
                StatementKind.SELECT,
                SourceRange(start, end),
            ),
            sourceSnapshots = listOf(SourceSnapshot(ROOT_FILE, ROOT_REVISION, xml)),
            dependencies = emptyList(),
        )

        val contract = XmlStatementParameterContractFactory.build(graph)

        assertTrue(contract.isPreparationBlocked)
        assertEquals(
            listOf("xml-parameter-contract-malformed-root-source"),
            contract.blockingProblems.map { it.code },
        )
        assertEquals(InputContractProblemKind.UNKNOWN, contract.blockingProblems.single().kind)
    }

    @Test
    fun internalDtdSubsetIsRejectedEvenIfGraphIsConstructedManually() {
        val contract = XmlStatementParameterContractFactory.build(
            graph(
                body = "select " + raw("table"),
                doctype = "<!DOCTYPE mapper [<!ENTITY x \"boom\">]>",
            ),
        )

        assertTrue(contract.isPreparationBlocked)
        assertEquals(
            listOf("xml-parameter-contract-unsafe-dtd"),
            contract.blockingProblems.map { it.code },
        )
        assertEquals(InputContractProblemKind.UNSUPPORTED, contract.blockingProblems.single().kind)
    }

    private fun graph(
        body: String,
        xmlNamespace: String = "com.acme.UserMapper",
        idNamespace: String = xmlNamespace,
        elementName: String = "select",
        kind: StatementKind = StatementKind.SELECT,
        doctype: String = "",
    ): StatementSourceGraph {
        val declaration = "<$elementName id=\"find\">$body</$elementName>"
        val xml = doctype +
            "<mapper namespace=\"$xmlNamespace\">" +
            declaration +
            "</mapper>"
        val start = xml.indexOf("<$elementName id=\"find\">")
        val end = xml.indexOf('>', start) + 1
        return StatementSourceGraph(
            rootStatement = CapturedStatement(
                id = XmlStatementId(ROOT_FILE, idNamespace, "find"),
                kind = kind,
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
