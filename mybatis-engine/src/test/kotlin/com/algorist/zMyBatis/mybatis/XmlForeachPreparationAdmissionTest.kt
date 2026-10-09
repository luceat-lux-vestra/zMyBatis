package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.InputAliasKind
import com.algorist.zMyBatis.core.input.InputContractProblem
import com.algorist.zMyBatis.core.input.InputContractProblemKind
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputRequiredness
import com.algorist.zMyBatis.core.input.InputProvenance
import com.algorist.zMyBatis.core.input.InputShape
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
    fun completeMapperIdentityAndRevisionCannotBeReplacedByContractEvidence() {
        val fixture = fixture("SELECT <foreach collection=\"ids\" item=\"item\">#{item}</foreach>", listOf(parameter(0, "java.util.List<java.lang.Long>", "ids", "ids")))
        val original = contract(fixture)
        assertFailure(XmlForeachPreparationAdmission.inspect(fixture.graph, null, original), PreparationFailureKind.UNSUPPORTED_SEMANTIC, "xml-foreach-preparation-mapper-authority-unproven")
        val wrongId = XmlMapperMethodCapture(XmlStatementId(XML_FILE, "example.Mapper", "other"), fixture.mapper.mapperSource, fixture.mapper.methodSourceRange, fixture.mapper.parameters)
        assertFailure(XmlForeachPreparationAdmission.inspect(fixture.graph, wrongId, original), PreparationFailureKind.STATEMENT_ID_MISMATCH, "xml-foreach-preparation-statement-mismatch")
        for (snapshot in listOf(
            fixture.mapper.mapperSource.copy(revision = SourceRevision("changed")),
            fixture.mapper.mapperSource.copy(fileId = SourceFileId("vfs:/Other.java")),
        )) {
            val mapper = XmlMapperMethodCapture(fixture.mapper.statementId, snapshot, fixture.mapper.methodSourceRange, fixture.mapper.parameters)
            assertFailure(XmlForeachPreparationAdmission.inspect(fixture.graph, mapper, original), PreparationFailureKind.SOURCE_REVISION_MISMATCH, "xml-foreach-preparation-revision-mismatch")
        }
        val collision = XmlMapperMethodCapture(fixture.mapper.statementId, fixture.mapper.mapperSource.copy(fileId = XML_FILE), fixture.mapper.methodSourceRange, fixture.mapper.parameters)
        assertFailure(XmlForeachPreparationAdmission.inspect(fixture.graph, collision, original), PreparationFailureKind.PREPARATION_INVARIANT, "xml-foreach-preparation-mapper-unsupported")
        val missingRevision = runCatching {
            copyContract(original, sourceRevisions = original.sourceRevisions.filterKeys { it != JAVA_FILE })
        }
        assertTrue(missingRevision.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun fullContractComparisonIncludesOutsideCallersAliasesAndPlaceholderProvenance() {
        val body = "SELECT #{status}, <foreach collection=\"ids\" item=\"item\">#{item}</foreach>, #{status}"
        val fixture = fixture(body, listOf(parameter(0, "long", "status", "status"), parameter(1, "java.util.List<java.lang.Long>", "ids", "ids")))
        val original = contract(fixture)
        val status = original.requirements.single { it.id.value == "xml-java-param:0" }
        val withoutPlaceholder = status.copy(provenance = InputProvenance(status.provenance.evidence.filterNot { it is InputEvidence.Placeholder }))
        val variants = listOf(
            copyContract(original, requirements = original.requirements.filterNot { it.id == status.id }, aliases = original.aliases.filterNot { it.requirementId == status.id }),
            copyContract(original, aliases = original.aliases.filterNot { it.name == "status" }),
            copyContract(original, requirements = original.requirements.map { if (it.id == status.id) withoutPlaceholder else it }),
            copyContract(original, requirements = original.requirements.map { if (it.id == status.id) it.copy(requiredness = InputRequiredness.OPTIONAL) else it }),
        )
        for (variant in variants) assertFailure(XmlForeachPreparationAdmission.inspect(fixture.graph, fixture.mapper, variant), PreparationFailureKind.BINDING_RESOLUTION, "xml-foreach-preparation-source-contract-mismatch")
        val changedUses = graph("$body, #{status}", XML_REVISION)
        assertFailure(XmlForeachPreparationAdmission.inspect(changedUses, fixture.mapper, original), PreparationFailureKind.BINDING_RESOLUTION, "xml-foreach-preparation-source-contract-mismatch")
        val changedRange = XmlMapperMethodCapture(fixture.mapper.statementId, fixture.mapper.mapperSource, SourceRange(21, 120), fixture.mapper.parameters)
        assertFailure(XmlForeachPreparationAdmission.inspect(fixture.graph, changedRange, original), PreparationFailureKind.BINDING_RESOLUTION, "xml-foreach-preparation-source-contract-mismatch")
    }

    @Test
    fun wrappedCollectionAndLocalsAreRebuiltFromSource() {
        val loop = "<foreach collection=\"ids\" item=\"item\" index=\"idx\">#{idx},#{item}</foreach>"
        for (body in listOf("SELECT 1 <where>$loop</where>", "SELECT 1 <trim prefix=\"WHERE\" prefixOverrides=\"AND |OR \">$loop</trim>")) {
            val fixture = fixture(body, listOf(parameter(0, "java.util.List<java.lang.Long>", "ids", "ids")))
            val authentic = contract(fixture)
            val admitted = XmlForeachPreparationAdmission.inspect(fixture.graph, fixture.mapper, authentic) as XmlForeachPreparationAdmission.Result.Admitted
            assertEquals(setOf(authentic.requirements.single().id), admitted.collectionRequirementIds)
            assertEquals(mapOf("item" to InternalBindingKind.FOREACH_ITEM, "idx" to InternalBindingKind.FOREACH_INDEX), admitted.locals)
            val local = authentic.internalBindings.first()
            val forged = copyContract(authentic, internalBindings = listOf(InternalBinding("forged", local.kind, local.provenance)) + authentic.internalBindings.drop(1))
            assertFailure(XmlForeachPreparationAdmission.inspect(fixture.graph, fixture.mapper, forged), PreparationFailureKind.BINDING_RESOLUTION, "xml-foreach-preparation-source-contract-mismatch")
            for (drift in listOf(body.replace("<where>", "<where bogus=\"x\">"), body.replace("AND |OR ", "AND|OR"), body.replace(loop, "$loop<if test=\"enabled\">AND id = #{id}</if>"))) {
                if (drift == body) continue
                assertFailure(XmlForeachPreparationAdmission.inspect(graph(drift, XML_REVISION), fixture.mapper, authentic), PreparationFailureKind.UNSUPPORTED_SEMANTIC, "xml-foreach-preparation-source-unsupported")
            }
        }
    }

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

        val result = XmlForeachPreparationAdmission.inspect(fixture.graph, fixture.mapper, contract)

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
            XmlForeachPreparationAdmission.inspect(generic.graph, generic.mapper, contract(generic)) is
                XmlForeachPreparationAdmission.Result.Admitted,
        )
        assertTrue(
            XmlForeachPreparationAdmission.inspect(shortcut.graph, shortcut.mapper, contract(shortcut)) is
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
            XmlForeachPreparationAdmission.inspect(fixture.graph, fixture.mapper, contract(fixture)) ===
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
            XmlForeachPreparationAdmission.inspect(fixture.graph, fixture.mapper, forged),
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
            XmlForeachPreparationAdmission.inspect(fixture.graph, fixture.mapper, duplicateContract),
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
            XmlForeachPreparationAdmission.inspect(fixture.graph, fixture.mapper, extraInternal),
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
            XmlForeachPreparationAdmission.inspect(drifted, fixture.mapper, authentic),
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "xml-foreach-preparation-source-unsupported",
        )
    }

    @Test
    fun statementIdentityAndMapperTypeAuthorityCannotBeForged() {
        val fixture = fixture(
            """SELECT <foreach collection="ids" item="item">#{item}</foreach>""",
            listOf(parameter(0, "java.util.List<java.lang.Long>", "ids", "ids")),
        )
        val authentic = contract(fixture)
        val wrongStatement = graph(
            """SELECT <foreach collection="ids" item="item">#{item}</foreach>""",
            XML_REVISION,
            statementName = "other",
        )

        assertFailure(
            XmlForeachPreparationAdmission.inspect(wrongStatement, fixture.mapper, authentic),
            PreparationFailureKind.BINDING_RESOLUTION,
            "xml-foreach-preparation-source-contract-mismatch",
        )

        val requirement = authentic.requirements.single()
        val wrongShape = copyContract(
            authentic,
            requirements = listOf(
                requirement.copy(
                    expectedType = requirement.expectedType.copy(shape = InputShape.MAP),
                ),
            ),
        )
        assertFailure(
            XmlForeachPreparationAdmission.inspect(fixture.graph, fixture.mapper, wrongShape),
            PreparationFailureKind.BINDING_RESOLUTION,
            "xml-foreach-preparation-source-contract-mismatch",
        )
    }

    @Test
    fun supportedAliasKindCannotBeForgedWithoutMatchingProvenance() {
        val fixture = fixture(
            """SELECT <foreach collection="ids" item="item">#{item}</foreach>""",
            listOf(parameter(0, "java.util.List<java.lang.Long>", "ids", "ids")),
        )
        val authentic = contract(fixture)
        val alias = authentic.aliases.single()
        val forged = copyContract(
            authentic,
            aliases = listOf(alias.copy(kind = InputAliasKind.GENERIC_PARAM)),
        )

        assertFailure(
            XmlForeachPreparationAdmission.inspect(fixture.graph, fixture.mapper, forged),
            PreparationFailureKind.BINDING_RESOLUTION,
            "xml-foreach-preparation-source-contract-mismatch",
        )
    }

    @Test
    fun blockedConsumerContractCannotBecomeForeachAuthority() {
        val fixture = fixture(
            """SELECT <foreach collection="ids" item="item">#{item}</foreach>""",
            listOf(parameter(0, "java.util.List<java.lang.Long>", "ids", "ids")),
        )
        val authentic = contract(fixture)
        val blocked = copyContract(
            authentic,
            blockingProblems = listOf(
                InputContractProblem(
                    kind = InputContractProblemKind.UNSUPPORTED,
                    code = "forged-blocking-problem",
                    requirementId = null,
                    provenance = null,
                ),
            ),
        )

        assertFailure(
            XmlForeachPreparationAdmission.inspect(fixture.graph, fixture.mapper, blocked),
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
        blockingProblems: List<InputContractProblem> = emptyList(),
        sourceRevisions: Map<SourceFileId, SourceRevision> = source.sourceRevisions,
    ) = ParameterContract(
        statementId = source.statementId,
        requirements = requirements,
        aliases = aliases,
        internalBindings = internalBindings,
        blockingProblems = blockingProblems,
        sourceRevisions = sourceRevisions,
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
        statementName: String = "find",
    ): StatementSourceGraph {
        val statementId = XmlStatementId(XML_FILE, "example.Mapper", statementName)
        val declaration = "<select id=\"$statementName\">$body</select>"
        val xml = """
            <mapper namespace="example.Mapper">
              $declaration
            </mapper>
        """.trimIndent()
        val start = xml.indexOf("<select id=\"$statementName\">")
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
