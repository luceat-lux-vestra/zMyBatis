package com.algorist.zMyBatis.source

import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.XmlStatementId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlStatementSourceGraphResolverTest {
    @Test
    fun sameNamespaceIncludeResolvesToSameFileDependency() {
        val root = document(
            "root.xml",
            """
                <mapper namespace="a.Mapper">
                  <sql id="base">id, name</sql>
                  <select id="find">
                    SELECT <include refid="base"/> FROM users
                  </select>
                </mapper>
            """.trimIndent(),
        )

        val result = resolve(root, rootId(root, "a.Mapper", "find"))
        val graph = resolved(result)

        assertEquals(StatementKind.SELECT, graph.rootStatement.kind)
        assertEquals(listOf(root.snapshot), graph.sourceSnapshots)
        assertEquals(1, graph.dependencies.size)
        assertEquals(root.snapshot.fileId, graph.dependencies.single().dependentFileId)
        assertEquals(root.snapshot.fileId, graph.dependencies.single().requiredFileId)
        assertEquals(
            "<include refid=\"base\"/>",
            root.snapshot.content.substring(
                graph.dependencies.single().referenceRange.startOffset,
                graph.dependencies.single().referenceRange.endOffsetExclusive,
            ),
        )
    }

    @Test
    fun qualifiedCrossNamespaceAndNestedDependenciesResolveAcrossDocuments() {
        val root = document(
            "root.xml",
            """
                <mapper namespace="a.Root">
                  <select id="find">
                    SELECT <include refid="b.Common.mid"/>
                  </select>
                </mapper>
            """.trimIndent(),
        )
        val common = document(
            "common.xml",
            """
                <mapper namespace="b.Common">
                  <sql id="mid">
                    <include refid="c.Leaf.columns"/>
                  </sql>
                </mapper>
            """.trimIndent(),
        )
        val leaf = document(
            "leaf.xml",
            """
                <mapper namespace="c.Leaf">
                  <sql id="columns">id, name</sql>
                </mapper>
            """.trimIndent(),
        )

        val graph = resolved(resolve(rootId(root, "a.Root", "find"), root, common, leaf))

        assertEquals(
            listOf("vfs:common.xml", "vfs:leaf.xml", "vfs:root.xml"),
            graph.sourceSnapshots.map { it.fileId.value },
        )
        assertEquals(
            listOf(
                "vfs:common.xml->vfs:leaf.xml",
                "vfs:root.xml->vfs:common.xml",
            ),
            graph.dependencies.map { "${it.dependentFileId.value}->${it.requiredFileId.value}" },
        )
    }

    @Test
    fun repeatedIncludeOccurrencesRemainDistinctEdges() {
        val root = document(
            "root.xml",
            """
                <mapper namespace="a.Mapper">
                  <sql id="base">id</sql>
                  <select id="find">
                    <include refid="base"/>
                    <include refid="base"/>
                  </select>
                </mapper>
            """.trimIndent(),
        )

        val graph = resolved(resolve(root, rootId(root, "a.Mapper", "find")))

        assertEquals(2, graph.dependencies.size)
        assertNotEquals(graph.dependencies[0].referenceRange, graph.dependencies[1].referenceRange)
        assertEquals(
            listOf("<include refid=\"base\"/>", "<include refid=\"base\"/>"),
            graph.dependencies.map {
                root.snapshot.content.substring(it.referenceRange.startOffset, it.referenceRange.endOffsetExclusive)
            },
        )
    }

    @Test
    fun missingFragmentFailsTypedWithoutTruncatedGraph() {
        val root = document(
            "root.xml",
            """
                <mapper namespace="a.Mapper">
                  <select id="find"><include refid="missing"/></select>
                </mapper>
            """.trimIndent(),
        )

        val failure = failed(resolve(root, rootId(root, "a.Mapper", "find")))
        assertTrue(failure is XmlStatementSourceGraphFailure.MissingFragment)
        failure as XmlStatementSourceGraphFailure.MissingFragment
        assertEquals("missing", failure.refid)
        assertEquals(root.snapshot.fileId, failure.ownerFileId)
    }

    @Test
    fun duplicateCanonicalFragmentCandidatesFailAmbiguousIndependentOfInputOrder() {
        val root = document(
            "root.xml",
            """
                <mapper namespace="a.Root">
                  <select id="find"><include refid="shared.Common.base"/></select>
                </mapper>
            """.trimIndent(),
        )
        val first = document(
            "first.xml",
            """
                <mapper namespace="shared.Common"><sql id="base">first</sql></mapper>
            """.trimIndent(),
        )
        val second = document(
            "second.xml",
            """
                <mapper namespace="shared.Common"><sql id="base">second</sql></mapper>
            """.trimIndent(),
        )
        val id = rootId(root, "a.Root", "find")

        val left = failed(resolve(id, root, first, second))
        val right = failed(resolve(id, second, root, first))

        assertEquals(left, right)
        assertTrue(left is XmlStatementSourceGraphFailure.AmbiguousFragment)
        left as XmlStatementSourceGraphFailure.AmbiguousFragment
        assertEquals(
            listOf("vfs:first.xml", "vfs:second.xml"),
            left.candidates.map { it.sourceFileId.value },
        )
    }

    @Test
    fun directFragmentCycleFailsTypedWithClosedPath() {
        val root = document(
            "root.xml",
            """
                <mapper namespace="a.Mapper">
                  <sql id="loop"><include refid="loop"/></sql>
                  <select id="find"><include refid="loop"/></select>
                </mapper>
            """.trimIndent(),
        )

        val failure = failed(resolve(root, rootId(root, "a.Mapper", "find")))
        assertTrue(failure is XmlStatementSourceGraphFailure.DependencyCycle)
        failure as XmlStatementSourceGraphFailure.DependencyCycle
        assertEquals(listOf("loop", "loop"), failure.path.map { it.fragmentId })
    }

    @Test
    fun indirectCrossDocumentCycleFailsTypedDeterministically() {
        val root = document(
            "root.xml",
            """
                <mapper namespace="root.Mapper">
                  <select id="find"><include refid="a.Mapper.one"/></select>
                </mapper>
            """.trimIndent(),
        )
        val a = document(
            "a.xml",
            """
                <mapper namespace="a.Mapper">
                  <sql id="one"><include refid="b.Mapper.two"/></sql>
                </mapper>
            """.trimIndent(),
        )
        val b = document(
            "b.xml",
            """
                <mapper namespace="b.Mapper">
                  <sql id="two"><include refid="a.Mapper.one"/></sql>
                </mapper>
            """.trimIndent(),
        )

        val failure = failed(resolve(rootId(root, "root.Mapper", "find"), b, root, a))
        assertTrue(failure is XmlStatementSourceGraphFailure.DependencyCycle)
        failure as XmlStatementSourceGraphFailure.DependencyCycle
        assertEquals(
            listOf("a.Mapper.one", "b.Mapper.two", "a.Mapper.one"),
            failure.path.map { "${it.namespace}.${it.fragmentId}" },
        )
    }

    @Test
    fun unreachableBrokenFragmentAndUnreachableUnsupportedDocumentDoNotContaminateRoot() {
        val root = document(
            "root.xml",
            """
                <mapper namespace="a.Mapper">
                  <sql id="unused"><include refid="doesNotExist"/></sql>
                  <select id="find">SELECT 1</select>
                </mapper>
            """.trimIndent(),
        )
        val unrelatedUnsupported = document(
            "unsupported.xml",
            """
                <mapper namespace="x.Other">
                  <select id="other" databaseId="vendor">SELECT 2</select>
                </mapper>
            """.trimIndent(),
        )

        val graph = resolved(resolve(rootId(root, "a.Mapper", "find"), unrelatedUnsupported, root))

        assertEquals(listOf(root.snapshot), graph.sourceSnapshots)
        assertTrue(graph.dependencies.isEmpty())
    }

    @Test
    fun documentLevelUnsupportedSemanticsBlocksReachableDocumentConservatively() {
        val root = document(
            "root.xml",
            """
                <mapper namespace="a.Mapper">
                  <sql id="unused" lang="custom.Driver">unused</sql>
                  <select id="find">SELECT 1</select>
                </mapper>
            """.trimIndent(),
        )

        val failure = failed(resolve(root, rootId(root, "a.Mapper", "find")))
        assertTrue(failure is XmlStatementSourceGraphFailure.UnsupportedSemantics)
        failure as XmlStatementSourceGraphFailure.UnsupportedSemantics
        assertEquals(XmlUnsupportedSemanticsKind.LANGUAGE_DRIVER, failure.evidence.kind)
        assertEquals(root.snapshot.fileId, failure.sourceFileId)
    }

    @Test
    fun duplicateAndRevisionMismatchedInputsFailClosedDeterministically() {
        val root = document(
            "root.xml",
            """
                <mapper namespace="a.Mapper"><select id="find">SELECT 1</select></mapper>
            """.trimIndent(),
        )
        val id = rootId(root, "a.Mapper", "find")

        val duplicateSnapshot = XmlStatementSourceGraphResolver.resolve(
            rootStatementId = id,
            snapshots = listOf(root.snapshot, root.snapshot),
            discoveries = listOf(root.discovery),
        )
        assertEquals(
            XmlStatementSourceGraphFailure.InputMismatch(
                XmlSourceGraphInputMismatchReason.DUPLICATE_SNAPSHOT,
                root.snapshot.fileId,
            ),
            failed(duplicateSnapshot),
        )

        val mismatchedSnapshot = root.snapshot.copy(revision = SourceRevision("changed"))
        val revisionMismatch = XmlStatementSourceGraphResolver.resolve(
            rootStatementId = id,
            snapshots = listOf(mismatchedSnapshot),
            discoveries = listOf(root.discovery),
        )
        assertEquals(
            XmlStatementSourceGraphFailure.InputMismatch(
                XmlSourceGraphInputMismatchReason.REVISION_MISMATCH,
                root.snapshot.fileId,
            ),
            failed(revisionMismatch),
        )
    }

    @Test
    fun callerOrderingDoesNotChangeResolvedGraph() {
        val root = document(
            "root.xml",
            """
                <mapper namespace="a.Root">
                  <select id="find">
                    <include refid="b.Common.one"/>
                    <include refid="c.Common.two"/>
                  </select>
                </mapper>
            """.trimIndent(),
        )
        val b = document(
            "b.xml",
            """
                <mapper namespace="b.Common"><sql id="one">1</sql></mapper>
            """.trimIndent(),
        )
        val c = document(
            "c.xml",
            """
                <mapper namespace="c.Common"><sql id="two">2</sql></mapper>
            """.trimIndent(),
        )
        val id = rootId(root, "a.Root", "find")

        val left = resolved(resolve(id, root, b, c))
        val right = resolved(resolve(id, c, root, b))

        assertEquals(left, right)
        assertEquals(left.hashCode(), right.hashCode())
    }

    @Test
    fun missingRootSourceAndStatementFailTyped() {
        val root = document(
            "root.xml",
            """
                <mapper namespace="a.Mapper"><select id="find">SELECT 1</select></mapper>
            """.trimIndent(),
        )
        val absentFileId = XmlStatementId(SourceFileId("vfs:absent.xml"), "a.Mapper", "find")
        assertTrue(
            failed(
                XmlStatementSourceGraphResolver.resolve(absentFileId, emptyList(), emptyList()),
            ) is XmlStatementSourceGraphFailure.RootSourceMissing,
        )

        val absentStatement = rootId(root, "a.Mapper", "missing")
        assertTrue(
            failed(resolve(root, absentStatement)) is XmlStatementSourceGraphFailure.RootStatementMissing,
        )
    }

    private fun resolve(
        root: DocumentFixture,
        rootStatementId: XmlStatementId,
    ): XmlStatementSourceGraphResult = resolve(rootStatementId, root)

    private fun resolve(
        rootStatementId: XmlStatementId,
        vararg fixtures: DocumentFixture,
    ): XmlStatementSourceGraphResult = XmlStatementSourceGraphResolver.resolve(
        rootStatementId = rootStatementId,
        snapshots = fixtures.map { it.snapshot },
        discoveries = fixtures.map { it.discovery },
    )

    private fun resolved(result: XmlStatementSourceGraphResult) =
        (result as XmlStatementSourceGraphResult.Resolved).graph

    private fun failed(result: XmlStatementSourceGraphResult) =
        (result as XmlStatementSourceGraphResult.Failed).failure

    private fun rootId(
        fixture: DocumentFixture,
        namespace: String,
        statementId: String,
    ): XmlStatementId = XmlStatementId(
        sourceFileId = fixture.snapshot.fileId,
        namespace = namespace,
        statementId = statementId,
    )

    private fun document(
        fileName: String,
        content: String,
    ): DocumentFixture {
        val snapshot = SourceSnapshot(
            fileId = SourceFileId("vfs:$fileName"),
            revision = SourceRevision("revision:$fileName"),
            content = content,
        )
        val discoveryResult = XmlMapperSourceDiscovery.discover(snapshot)
        assertTrue(
            "fixture must be valid mapper XML but was $discoveryResult",
            discoveryResult is XmlMapperDiscoveryResult.Discovered,
        )
        return DocumentFixture(
            snapshot = snapshot,
            discovery = (discoveryResult as XmlMapperDiscoveryResult.Discovered).mapper,
        )
    }

    private data class DocumentFixture(
        val snapshot: SourceSnapshot,
        val discovery: XmlMapperDocumentDiscovery,
    )
}