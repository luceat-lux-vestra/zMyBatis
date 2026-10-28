package com.algorist.zMyBatis.source

import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.XmlStatementId
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlStatementSourceGraphResolverScaleTest {

    @Test
    fun deepReachableGraphRecordsScaleWithoutLatencySla() {
        val depth = DEEP_GRAPH_DEPTH
        val xml = buildString {
            append("<mapper namespace=\"scale.Deep\">")
            for (index in 0 until depth) {
                append("<sql id=\"f")
                append(index)
                append("\">")
                if (index + 1 < depth) {
                    append("<include refid=\"f")
                    append(index + 1)
                    append("\"/>")
                } else {
                    append("terminal")
                }
                append("</sql>")
            }
            append("<select id=\"find\"><include refid=\"f0\"/></select></mapper>")
        }
        val fixture = document("deep-scale.xml", xml)

        val startedNanos = System.nanoTime()
        val result = XmlStatementSourceGraphResolver.resolve(
            rootStatementId = rootId(fixture, "scale.Deep", "find"),
            snapshots = listOf(fixture.snapshot),
            discoveries = listOf(fixture.discovery),
        )
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)

        assertTrue(result is XmlStatementSourceGraphResult.Resolved)
        result as XmlStatementSourceGraphResult.Resolved
        assertEquals(depth, result.graph.dependencies.size)
        assertEquals(1, result.graph.sourceSnapshots.size)

        println(
            "ZMYBATIS_XML_SCALE phase=source-graph-depth " +
                "graph_depth=$depth " +
                "graph_breadth=1 " +
                "dependencies=${result.graph.dependencies.size} " +
                "source_documents=${result.graph.sourceSnapshots.size} " +
                "aggregate_source_bytes=${fixture.snapshot.content.toByteArray(Charsets.UTF_8).size} " +
                "elapsed_ms=$elapsedMillis",
        )
    }

    @Test
    fun broadReachableGraphRecordsCrossDocumentScaleWithoutLatencySla() {
        val children = (0 until BROAD_GRAPH_WIDTH).map { index ->
            document(
                "broad-${index.toString().padStart(3, '0')}.xml",
                "<mapper namespace=\"scale.Broad$index\">" +
                    "<sql id=\"fragment\">column_$index</sql></mapper>",
            )
        }
        val root = document(
            "broad-root.xml",
            buildString {
                append("<mapper namespace=\"scale.BroadRoot\"><select id=\"find\">")
                for (index in children.indices) {
                    append("<include refid=\"scale.Broad")
                    append(index)
                    append(".fragment\"/>")
                }
                append("</select></mapper>")
            },
        )
        val fixtures = listOf(root) + children
        val aggregateBytes = fixtures.sumOf { fixture ->
            fixture.snapshot.content.toByteArray(Charsets.UTF_8).size.toLong()
        }

        val startedNanos = System.nanoTime()
        val result = XmlStatementSourceGraphResolver.resolve(
            rootStatementId = rootId(root, "scale.BroadRoot", "find"),
            snapshots = fixtures.map(Fixture::snapshot),
            discoveries = fixtures.map(Fixture::discovery),
        )
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)

        assertTrue(result is XmlStatementSourceGraphResult.Resolved)
        result as XmlStatementSourceGraphResult.Resolved
        assertEquals(BROAD_GRAPH_WIDTH, result.graph.dependencies.size)
        assertEquals(BROAD_GRAPH_WIDTH + 1, result.graph.sourceSnapshots.size)

        println(
            "ZMYBATIS_XML_SCALE phase=source-graph-breadth " +
                "graph_depth=1 " +
                "graph_breadth=$BROAD_GRAPH_WIDTH " +
                "dependencies=${result.graph.dependencies.size} " +
                "source_documents=${result.graph.sourceSnapshots.size} " +
                "aggregate_source_bytes=$aggregateBytes " +
                "elapsed_ms=$elapsedMillis",
        )
    }

    private fun document(fileName: String, content: String): Fixture {
        val snapshot = SourceSnapshot(
            fileId = SourceFileId("vfs:$fileName"),
            revision = SourceRevision("revision:$fileName"),
            content = content,
        )
        val discovery = when (val result = XmlMapperSourceDiscovery.discover(snapshot)) {
            is XmlMapperDiscoveryResult.Discovered -> result.mapper
            is XmlMapperDiscoveryResult.Failed ->
                throw AssertionError("scale mapper discovery failed for $fileName: ${result.reason}")
        }
        return Fixture(snapshot, discovery)
    }

    private fun rootId(fixture: Fixture, namespace: String, statementId: String): XmlStatementId =
        XmlStatementId(fixture.snapshot.fileId, namespace, statementId)

    private data class Fixture(
        val snapshot: SourceSnapshot,
        val discovery: XmlMapperDocumentDiscovery,
    )

    companion object {
        private const val DEEP_GRAPH_DEPTH = 2_000
        private const val BROAD_GRAPH_WIDTH = 512
    }
}
