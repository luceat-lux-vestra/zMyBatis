package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.InputEnvironmentResult
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputProvenance
import com.algorist.zMyBatis.core.input.InternalBinding
import com.algorist.zMyBatis.core.input.InternalBindingKind
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.input.SourceEvidence
import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import com.algorist.zMyBatis.core.preparation.PreparationRequestResult
import com.algorist.zMyBatis.core.preparation.PreparationResult
import com.algorist.zMyBatis.core.preparation.XmlMapperPreparationSource
import com.algorist.zMyBatis.core.source.CapturedStatement
import com.algorist.zMyBatis.core.source.SourceDependencyEdge
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.StatementSourceGraph
import com.algorist.zMyBatis.core.source.XmlStatementId
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class XmlMapperPreparationEngineAdversarialTest {
    @Test
    fun rootMayLoadBeforeQualifiedFragmentAndStillUsesMyBatisIncompleteResolution() {
        val rootFile = SourceFileId("vfs:/a-root.xml")
        val commonFile = SourceFileId("vfs:/z-common.xml")
        val rootContent = mapper(
            "example.Mapper",
            "<select id=\"find\">SELECT <include refid=\"example.Common.columns\"/> FROM users</select>",
        )
        val commonContent = mapper(
            "example.Common",
            "<sql id=\"columns\">id, name</sql>",
        )
        val graph = StatementSourceGraph(
            CapturedStatement(
                XmlStatementId(rootFile, "example.Mapper", "find"),
                StatementKind.SELECT,
                SourceRange(0, rootContent.length),
            ),
            listOf(
                SourceSnapshot(rootFile, SourceRevision("root-r1"), rootContent),
                SourceSnapshot(commonFile, SourceRevision("common-r1"), commonContent),
            ),
            listOf(SourceDependencyEdge(rootFile, commonFile, SourceRange(0, 0))),
        )

        val execution = (prepare(graph, contract(graph)) as PreparationResult.Success).execution
        assertEquals("SELECT id, name FROM users", normalize(execution.sqlWithPlaceholders))
    }

    @Test
    fun mappedStatementMustComeFromGraphClaimedRootSource() {
        val rootFile = SourceFileId("vfs:/root.xml")
        val otherFile = SourceFileId("vfs:/other.xml")
        val rootContent = mapper("example.Mapper", "<sql id=\"rootOnly\">1</sql>")
        val otherContent = mapper("example.Mapper", "<select id=\"find\">SELECT 1</select>")
        val graph = StatementSourceGraph(
            CapturedStatement(
                XmlStatementId(rootFile, "example.Mapper", "find"),
                StatementKind.SELECT,
                SourceRange(0, rootContent.length),
            ),
            listOf(
                SourceSnapshot(rootFile, SourceRevision("root-r1"), rootContent),
                SourceSnapshot(otherFile, SourceRevision("other-r1"), otherContent),
            ),
            listOf(SourceDependencyEdge(rootFile, otherFile, SourceRange(0, 0))),
        )

        val failure = (prepare(graph, contract(graph)) as PreparationResult.Failed).failure
        assertEquals(PreparationFailureKind.PREPARATION_INVARIANT, failure.kind)
        assertEquals("xml-preparation-root-resource-mismatch", failure.code)
    }

    @Test
    fun mappedStatementKindMustMatchAuthoritativeGraphKind() {
        val graph = graph("<update id=\"find\">UPDATE users SET active = 1</update>")

        val failure = (prepare(graph, contract(graph)) as PreparationResult.Failed).failure
        assertEquals(PreparationFailureKind.PREPARATION_INVARIANT, failure.kind)
        assertEquals("xml-preparation-statement-kind-mismatch", failure.code)
    }

    @Test
    fun mapperNamespaceCannotInitializeApplicationVisibleClass() {
        assertEquals(0, NamespaceTrapProbe.initializations.get())
        val namespace = NamespaceTrap::class.java.name
        val graph = graph(
            body = "<select id=\"find\">SELECT 1</select>",
            namespace = namespace,
        )

        val result = prepare(graph, contract(graph))
        assertEquals("SELECT 1", normalize((result as PreparationResult.Success).execution.sqlWithPlaceholders))
        assertEquals(0, NamespaceTrapProbe.initializations.get())
    }

    @Test
    fun internalInputAuthorityCannotEnterZeroInputXmlIsland() {
        val graph = graph("<select id=\"find\">SELECT 1</select>")
        val snapshot = graph.sourceSnapshots.single()
        val provenance = InputProvenance(
            listOf(
                InputEvidence.BindLocal(
                    name = "internal",
                    expression = "1",
                    source = SourceEvidence(
                        snapshot.fileId,
                        snapshot.revision,
                        SourceRange(0, snapshot.content.length),
                    ),
                ),
            ),
        )
        val contract = ParameterContract(
            graph.rootStatement.id,
            requirements = emptyList(),
            aliases = emptyList(),
            internalBindings = listOf(
                InternalBinding("internal", InternalBindingKind.BIND, provenance),
            ),
            blockingProblems = emptyList(),
            sourceRevisions = graph.sourceSnapshots.associate { it.fileId to it.revision },
        )

        val failure = (prepare(graph, contract) as PreparationResult.Failed).failure
        assertEquals(PreparationFailureKind.UNSUPPORTED_SEMANTIC, failure.kind)
        assertEquals("xml-preparation-input-contract-unsupported", failure.code)
    }

    @Test
    fun remainingStandardDynamicNodesFailWithoutRuntimeEvaluation() {
        val dynamicNodes = listOf(
            "<foreach collection=\"items\" item=\"item\">x</foreach>",
            "<bind name=\"x\" value=\"1\"/>x",
            "<set>value = 1</set>",
        )
        dynamicNodes.forEachIndexed { index, node ->
            val graph = graph("<select id=\"find\">SELECT * FROM users $node</select>", "vfs:/dynamic-$index.xml")
            val failure = (prepare(graph, contract(graph)) as PreparationResult.Failed).failure
            assertEquals(PreparationFailureKind.UNSUPPORTED_SEMANTIC, failure.kind)
            assertEquals("xml-preparation-dynamic-sql-unsupported", failure.code)
        }
    }

    private fun prepare(graph: StatementSourceGraph, contract: ParameterContract): PreparationResult {
        val environment = (InputEnvironment.validate(contract, emptyList()) as InputEnvironmentResult.Success).environment
        val request = MyBatisPreparationRequest.create(
            XmlMapperPreparationSource(graph),
            contract,
            environment,
        ) as PreparationRequestResult.Ready
        return XmlMapperPreparationEngine.prepare(request.request)
    }

    private fun contract(graph: StatementSourceGraph) = ParameterContract(
        graph.rootStatement.id,
        requirements = emptyList(),
        aliases = emptyList(),
        internalBindings = emptyList(),
        blockingProblems = emptyList(),
        sourceRevisions = graph.sourceSnapshots.associate { it.fileId to it.revision },
    )

    private fun graph(
        body: String,
        file: String = "vfs:/mapper.xml",
        namespace: String = "example.Mapper",
    ): StatementSourceGraph {
        val fileId = SourceFileId(file)
        val content = mapper(namespace, body)
        return StatementSourceGraph(
            CapturedStatement(
                XmlStatementId(fileId, namespace, "find"),
                StatementKind.SELECT,
                SourceRange(0, content.length),
            ),
            listOf(SourceSnapshot(fileId, SourceRevision("r1"), content)),
            emptyList(),
        )
    }

    private fun mapper(namespace: String, body: String): String = """
        <?xml version="1.0" encoding="UTF-8" ?>
        <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
        <mapper namespace="$namespace">$body</mapper>
    """.trimIndent()

    private fun normalize(sql: String): String = sql.trim().replace(Regex("\\s+"), " ")
}

private class NamespaceTrap {
    companion object {
        init {
            NamespaceTrapProbe.initializations.incrementAndGet()
        }
    }
}

private object NamespaceTrapProbe {
    val initializations = AtomicInteger()
}
