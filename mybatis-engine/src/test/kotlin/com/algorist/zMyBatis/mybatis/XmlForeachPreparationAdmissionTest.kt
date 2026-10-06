package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.InputAliasKind
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputProvenance
import com.algorist.zMyBatis.core.input.InternalBinding
import com.algorist.zMyBatis.core.input.InternalBindingKind
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.input.XmlMapperMethodParameterContractFactory
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
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
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlForeachPreparationAdmissionTest {
    @Test
    fun authenticExplicitAliasAndLocalsAreAdmitted() {
        val fixture = fixture(
            """
            SELECT
            <foreach collection="ids" item="item" index="idx" separator=",">
              #{idx}, #{item}
            </foreach>
            """.trimIndent(),
            listOf(parameter(0, "java.util.List<java.lang.Long>", "ids", "ids")),
        )
        val contract = contract(fixture)

        val result = XmlForeachPreparationAdmission.inspect(fixture.graph, contract)

        assertTrue(result is XmlForeachPreparationAdmission.Result.Admitted)
        result as XmlForeachPreparationAdmission.Result.Admitted
        assertEquals(setOf(contract.requirements.single().id), result.collectionRequirementIds)
        assertEquals(
            linkedMapOf(
                "item" to InternalBindingKind.FOREACH_ITEM,
                "idx" to InternalBindingKind.FOREACH_INDEX,
            ),
            result.locals,
        )
    }

    @Test
    fun provenGenericAndCollectionShortcutAliasesAreAdmitted() {
        val generic = fixture(
            """SELECT <foreach collection="param2" item="item">#{item}</foreach>""",
            listOf(
                parameter(0, "java.lang.String", "status", "status"),
                parameter(1, "java.util.List<java.lang.Long>", "ids", "ids"),
            ),
        )
        val shortcut = fixture(
            """SELECT <foreach collection="array" item="item">#{item}</foreach>""",
            listOf(parameter(0, "long[]", "ids", null)),
        )

        assertTrue(
            XmlForeachPreparationAdmission.inspect(generic.graph, contract(generic)) is
                XmlForeachPreparationAdmission.Result.Admitted,
        )
        assertTrue(
            XmlForeachPreparationAdmission.inspect(shortcut.graph, contract(shortcut)) is
                XmlForeachPreparationAdmission.Result.Admitted,
        )
    }

    @Test
    fun ordinaryStaticContractIsNotApplicable() {
        val fixture = fixture(
            "SELECT * FROM users WHERE id = #{id}",
            listOf(parameter(0, "long", "id", "id")),
        )

        assertTrue(
            XmlForeachPreparationAdmission.inspect(fixture.graph, contract(fixture)) ===
                XmlForeachPreparationAdmission.Result.NotPresent,
        )
    }

    @Test
    fun forgedLocalAuthorityFailsClosed() {
        val fixture = fixture(
            """SELECT <foreach collection="ids" item="item">#{item}</foreach>""",
            listOf(parameter(0, "java.util.List<java.lang.Long>", "ids", "ids")),
        )
        val authentic = contract(fixture)
        val local = authentic.internalBindings.single()
        val forged = copyContract(
            authentic,
            internalBindings = listOf(
                InternalBinding("forged", local.kind, local.provenance),
            ),
        )

        assertFailure(
            XmlForeachPreparationAdmission.inspect(fixture.graph, forged),
            PreparationFailureKind.BINDING_RESOLUTION,
            "xml-foreach-preparation-source-contract-mismatch",
        )
    }

    @Test
    fun duplicateCollectionOrExtraInternalAuthorityFailsClosed() {
        val fixture = fixture(
            """SELECT <foreach collection="ids" item="item">#{item}</foreach>""",
            listOf(parameter(0, "java.util.List<java.lang.Long>", "ids", "ids")),
        )
        val authentic = contract(fixture)
        val requirement = authentic.requirements.single()
        val foreach = requirement.provenance.evidence
            .filterIsInstance<InputEvidence.ForeachCollection>()
            .single()
        val duplicateCollection = requirement.copy(
            provenance = InputProvenance(requirement.provenance.evidence + foreach),
        )
        val duplicateContract = copyContract(
            authentic,
            requirements = listOf(duplicateCollection),
        )

        assertFailure(
            XmlForeachPreparationAdmission.inspect(fixture.graph, duplicateContract),
            PreparationFailureKind.BINDING_RESOLUTION,
            "xml-foreach-preparation-source-contract-mismatch",
        )

        val local = authentic.internalBindings.single()
        val extraInternal = copyContract(
            authentic,
            internalBindings = authentic.internalBindings +
                InternalBinding("bound", InternalBindingKind.BIND, local.provenance),
        )
        assertFailure(
            XmlForeachPreparationAdmission.inspect(fixture.graph, extraInternal),
            PreparationFailureKind.BINDING_RESOLUTION,
            "xml-foreach-preparation-source-contract-mismatch",
        )
    }

    @Test
    fun sourceDriftOutsideBoundedForeachIslandFailsClosed() {
        val fixture = fixture(
            """SELECT <foreach collection="ids" item="item">#{item}</foreach>""",
            listOf(parameter(0, "java.util.List<java.lang.Long>", "ids", "ids")),
        )
        val authentic = contract(fixture)
        val drifted = graph(
            """SELECT <if test="ids != null">#{ids}</if>""",
            XML_REVISION,
        )

        assertFailure(
            XmlForeachPreparationAdmission.inspect(drifted, authentic),
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "xml-foreach-preparation-source-unsupported",
        )
    }

    @Test
    fun forgedAliasKindFailsContractAdmission() {
        val fixture = fixture(
            """SELECT <foreach collection="ids" item="item">#{item}</foreach>""",
            listOf(parameter(0, "java.util.List<java.lang.Long>", "ids", "ids")),
        )
        val authentic = contract(fixture)
        val alias = authentic.aliases.single()
        val forged = copyContract(
            authentic,
            aliases = listOf(alias.copy(kind = InputAliasKind.SOURCE_PARAMETER_NAME)),
        )

        assertFailure(
            XmlForeachPreparationAdmission.inspect(fixture.graph, forged),
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "xml-foreach-preparation-contract-unsupported",
        )
    }

    private fun assertFailure(
        result: XmlForeachPreparationAdmission.Result,
        kind: PreparationFailureKind,
        code: String,
    ) {
        assertTrue(result is XmlForeachPreparationAdmission.Result.Failed)
        result as XmlForeachPreparationAdmission.Result.Failed
        assertEquals(kind, result.failure.kind)
        assertEquals(code, result.failure.code)
    }

    private fun copyContract(
        source: ParameterContract,
        requirements: List<com.algorist.zMyBatis.core.input.InputRequirement> = source.requirements,
        aliases: List<com.algorist.zMyBatis.core.input.InputAlias> = source.aliases,
        internalBindings: List<InternalBinding> = source.internalBindings,
    ) = ParameterContract(
        statementId = source.statementId,
        requirements = requirements,
        aliases = aliases,
        internalBindings = internalBindings,
        blockingProblems = emptyList(),
        sourceRevisions = source.sourceRevisions,
    )

    private fun contract(fixture: Fixture): ParameterContract =
        XmlMapperMethodParameterContractFactory.build(fixture.graph, fixture.mapper)

    private fun fixture(
        body: String,
        parameters: List<JavaMethodParameterMetadata>,
    ): Fixture {
        val graph = graph(body, XML_REVISION)
        return Fixture(
            graph,
            XmlMapperMethodCapture(
                statementId = graph.rootStatement.id as XmlStatementId,
                mapperSource = SourceSnapshot(JAVA_FILE, JAVA_REVISION, "x".repeat(256)),
                methodSourceRange = SourceRange(20, 120),
                parameters = parameters,
            ),
        )
    }

    private fun graph(
        body: String,
        revision: SourceRevision,
    ): StatementSourceGraph {
        val statementId = XmlStatementId(XML_FILE, "example.Mapper", "find")
        val declaration = "<select id=\"find\">$body</select>"
        val xml = """
            <mapper namespace="example.Mapper">
              $declaration
            </mapper>
        """.trimIndent()
        val start = xml.indexOf("<select id=\"find\">")
        val end = xml.indexOf('>', start) + 1
        return StatementSourceGraph(
            rootStatement = CapturedStatement(
                id = statementId,
                kind = StatementKind.SELECT,
                sourceRange = SourceRange(start, end),
            ),
            sourceSnapshots = listOf(SourceSnapshot(XML_FILE, revision, xml)),
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

    private data class Fixture(
        val graph: StatementSourceGraph,
        val mapper: XmlMapperMethodCapture,
    )

    private companion object {
        val XML_FILE = SourceFileId("vfs:/mapper.xml")
        val XML_REVISION = SourceRevision("xml-foreach-admission-r1")
        val JAVA_FILE = SourceFileId("vfs:/Mapper.java")
        val JAVA_REVISION = SourceRevision("java-foreach-admission-r1")
    }
}
