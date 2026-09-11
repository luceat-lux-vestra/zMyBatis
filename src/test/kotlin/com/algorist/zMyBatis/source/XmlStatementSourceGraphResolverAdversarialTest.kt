package com.algorist.zMyBatis.source

import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.XmlStatementId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlStatementSourceGraphResolverAdversarialTest {
    @Test
    fun propertySubstitutedRefidFailsAsUnsupportedInsteadOfMissing() {
        val fixture = document(
            "dynamic.xml",
            "<mapper namespace=\"a.Mapper\"><sql id=\"base\">id</sql><select id=\"find\"><include refid=\"\${fragmentName}\"/></select></mapper>",
        )

        val failure = failed(
            XmlStatementSourceGraphResolver.resolve(
                rootStatementId = rootId(fixture, "a.Mapper", "find"),
                snapshots = listOf(fixture.snapshot),
                discoveries = listOf(fixture.discovery),
            ),
        )

        assertTrue(failure is XmlStatementSourceGraphFailure.UnsupportedReference)
        failure as XmlStatementSourceGraphFailure.UnsupportedReference
        assertEquals("\${fragmentName}", failure.refid)
        assertEquals(fixture.snapshot.fileId, failure.ownerFileId)
    }

    @Test
    fun propertySubstitutedMapperNamespaceFailsTyped() {
        val fixture = document(
            "dynamic-namespace.xml",
            "<mapper namespace=\"\${mapperNamespace}\"><select id=\"find\">SELECT 1</select></mapper>",
        )

        val failure = failed(
            XmlStatementSourceGraphResolver.resolve(
                rootStatementId = rootId(fixture, "\${mapperNamespace}", "find"),
                snapshots = listOf(fixture.snapshot),
                discoveries = listOf(fixture.discovery),
            ),
        )

        assertEquals(
            XmlStatementSourceGraphFailure.UnsupportedIdentifier(
                sourceFileId = fixture.snapshot.fileId,
                kind = XmlDynamicIdentifierKind.MAPPER_NAMESPACE,
                value = "\${mapperNamespace}",
                sourceRange = null,
            ),
            failure,
        )
    }

    @Test
    fun propertySubstitutedStatementIdFailsTyped() {
        val fixture = document(
            "dynamic-statement.xml",
            "<mapper namespace=\"a.Mapper\"><select id=\"\${statementId}\">SELECT 1</select></mapper>",
        )

        val failure = failed(
            XmlStatementSourceGraphResolver.resolve(
                rootStatementId = rootId(fixture, "a.Mapper", "\${statementId}"),
                snapshots = listOf(fixture.snapshot),
                discoveries = listOf(fixture.discovery),
            ),
        )

        assertTrue(failure is XmlStatementSourceGraphFailure.UnsupportedIdentifier)
        failure as XmlStatementSourceGraphFailure.UnsupportedIdentifier
        assertEquals(XmlDynamicIdentifierKind.STATEMENT_ID, failure.kind)
        assertEquals("\${statementId}", failure.value)
        assertTrue(failure.sourceRange != null)
    }

    @Test
    fun propertySubstitutedFragmentIdBlocksReachableDocumentConservatively() {
        val fixture = document(
            "dynamic-fragment.xml",
            "<mapper namespace=\"a.Mapper\"><sql id=\"\${fragmentId}\">id</sql><select id=\"find\">SELECT 1</select></mapper>",
        )

        val failure = failed(
            XmlStatementSourceGraphResolver.resolve(
                rootStatementId = rootId(fixture, "a.Mapper", "find"),
                snapshots = listOf(fixture.snapshot),
                discoveries = listOf(fixture.discovery),
            ),
        )

        assertTrue(failure is XmlStatementSourceGraphFailure.UnsupportedIdentifier)
        failure as XmlStatementSourceGraphFailure.UnsupportedIdentifier
        assertEquals(XmlDynamicIdentifierKind.FRAGMENT_ID, failure.kind)
        assertEquals("\${fragmentId}", failure.value)
        assertTrue(failure.sourceRange != null)
    }

    @Test
    fun deepAcyclicFragmentChainResolvesWithoutJvmRecursion() {
        val depth = 2_000
        val xml = buildString {
            append("<mapper namespace=\"deep.Mapper\">\n")
            for (index in 0 until depth) {
                append("  <sql id=\"f")
                append(index)
                append("\">")
                if (index + 1 < depth) {
                    append("<include refid=\"f")
                    append(index + 1)
                    append("\"/>")
                } else {
                    append("terminal")
                }
                append("</sql>\n")
            }
            append("  <select id=\"find\"><include refid=\"f0\"/></select>\n")
            append("</mapper>")
        }
        val fixture = document("deep.xml", xml)

        val result = XmlStatementSourceGraphResolver.resolve(
            rootStatementId = rootId(fixture, "deep.Mapper", "find"),
            snapshots = listOf(fixture.snapshot),
            discoveries = listOf(fixture.discovery),
        )

        assertTrue(result is XmlStatementSourceGraphResult.Resolved)
        result as XmlStatementSourceGraphResult.Resolved
        assertEquals(depth, result.graph.dependencies.size)
        assertEquals(listOf(fixture.snapshot), result.graph.sourceSnapshots)
    }

    @Test
    fun missingDiscoveryAndMissingSnapshotAreTypedAndDeterministic() {
        val first = document(
            "a.xml",
            """<mapper namespace="a.Mapper"><select id="find">SELECT 1</select></mapper>""",
        )
        val second = document(
            "b.xml",
            """<mapper namespace="b.Mapper"><select id="find">SELECT 1</select></mapper>""",
        )
        val rootId = rootId(first, "a.Mapper", "find")

        val missingDiscovery = failed(
            XmlStatementSourceGraphResolver.resolve(
                rootStatementId = rootId,
                snapshots = listOf(second.snapshot, first.snapshot),
                discoveries = listOf(second.discovery),
            ),
        )
        assertEquals(
            XmlStatementSourceGraphFailure.InputMismatch(
                XmlSourceGraphInputMismatchReason.MISSING_DISCOVERY,
                first.snapshot.fileId,
            ),
            missingDiscovery,
        )

        val missingSnapshot = failed(
            XmlStatementSourceGraphResolver.resolve(
                rootStatementId = rootId,
                snapshots = listOf(first.snapshot),
                discoveries = listOf(second.discovery, first.discovery),
            ),
        )
        assertEquals(
            XmlStatementSourceGraphFailure.InputMismatch(
                XmlSourceGraphInputMismatchReason.MISSING_SNAPSHOT,
                second.snapshot.fileId,
            ),
            missingSnapshot,
        )
    }

    private fun document(fileName: String, content: String): Fixture {
        val snapshot = SourceSnapshot(
            fileId = SourceFileId("vfs:$fileName"),
            revision = SourceRevision("revision:$fileName"),
            content = content,
        )
        val discovered = XmlMapperSourceDiscovery.discover(snapshot)
        assertTrue(discovered is XmlMapperDiscoveryResult.Discovered)
        return Fixture(
            snapshot,
            (discovered as XmlMapperDiscoveryResult.Discovered).mapper,
        )
    }

    private fun rootId(fixture: Fixture, namespace: String, statementId: String): XmlStatementId =
        XmlStatementId(fixture.snapshot.fileId, namespace, statementId)

    private fun failed(result: XmlStatementSourceGraphResult): XmlStatementSourceGraphFailure =
        (result as XmlStatementSourceGraphResult.Failed).failure

    private data class Fixture(
        val snapshot: SourceSnapshot,
        val discovery: XmlMapperDocumentDiscovery,
    )
}
