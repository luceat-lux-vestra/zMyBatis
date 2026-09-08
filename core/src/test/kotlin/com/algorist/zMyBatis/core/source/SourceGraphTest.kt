package com.algorist.zMyBatis.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceGraphTest {
    @Test
    fun sourceRangeAllowsZeroLengthButRejectsNegativeOrReversedBounds() {
        assertEquals(SourceRange(3, 3), SourceRange(3, 3))
        assertThrows(IllegalArgumentException::class.java) { SourceRange(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { SourceRange(4, 3) }
    }

    @Test
    fun graphRejectsMissingRootSnapshot() {
        val root = capturedXmlStatement(SourceFileId("root.xml"), SourceRange(0, 4))

        assertThrows(IllegalArgumentException::class.java) {
            StatementSourceGraph(
                rootStatement = root,
                sourceSnapshots = listOf(snapshot("other.xml")),
                dependencies = emptyList(),
            )
        }
    }

    @Test
    fun graphRejectsRootRangeOutsideCapturedSnapshot() {
        val rootFile = SourceFileId("root.xml")
        val root = capturedXmlStatement(rootFile, SourceRange(0, 11))

        assertThrows(IllegalArgumentException::class.java) {
            StatementSourceGraph(
                rootStatement = root,
                sourceSnapshots = listOf(snapshot(rootFile, "0123456789")),
                dependencies = emptyList(),
            )
        }
    }

    @Test
    fun graphRejectsDependenciesWhoseEndpointsWereNotCaptured() {
        val rootFile = SourceFileId("root.xml")
        val fragmentFile = SourceFileId("fragment.xml")
        val root = capturedXmlStatement(rootFile, SourceRange(0, 4))

        assertThrows(IllegalArgumentException::class.java) {
            StatementSourceGraph(
                rootStatement = root,
                sourceSnapshots = listOf(snapshot(rootFile)),
                dependencies = listOf(SourceDependencyEdge(rootFile, fragmentFile, SourceRange(1, 2))),
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            StatementSourceGraph(
                rootStatement = root,
                sourceSnapshots = listOf(snapshot(rootFile), snapshot(fragmentFile)),
                dependencies = listOf(
                    SourceDependencyEdge(SourceFileId("missing.xml"), rootFile, SourceRange(1, 2)),
                ),
            )
        }
    }

    @Test
    fun graphRejectsDependencyRangeOutsideDependentSnapshot() {
        val rootFile = SourceFileId("root.xml")
        val fragmentFile = SourceFileId("fragment.xml")

        assertThrows(IllegalArgumentException::class.java) {
            StatementSourceGraph(
                rootStatement = capturedXmlStatement(rootFile, SourceRange(0, 4)),
                sourceSnapshots = listOf(snapshot(rootFile), snapshot(fragmentFile)),
                dependencies = listOf(SourceDependencyEdge(rootFile, fragmentFile, SourceRange(9, 11))),
            )
        }
    }

    @Test
    fun graphRejectsDuplicateSourceSnapshotsEvenWhenRevisionDiffers() {
        val rootFile = SourceFileId("root.xml")

        assertThrows(IllegalArgumentException::class.java) {
            StatementSourceGraph(
                rootStatement = capturedXmlStatement(rootFile, SourceRange(0, 4)),
                sourceSnapshots = listOf(
                    SourceSnapshot(rootFile, SourceRevision("r1"), "0123456789"),
                    SourceSnapshot(rootFile, SourceRevision("r2"), "0123456789"),
                ),
                dependencies = emptyList(),
            )
        }
    }

    @Test
    fun graphRejectsDuplicateDependencyOccurrencesButAllowsDistinctOccurrences() {
        val rootFile = SourceFileId("root.xml")
        val fragmentFile = SourceFileId("fragment.xml")
        val firstOccurrence = SourceDependencyEdge(rootFile, fragmentFile, SourceRange(1, 2))
        val secondOccurrence = SourceDependencyEdge(rootFile, fragmentFile, SourceRange(4, 5))
        val snapshots = listOf(snapshot(rootFile), snapshot(fragmentFile))
        val root = capturedXmlStatement(rootFile, SourceRange(0, 4))

        assertThrows(IllegalArgumentException::class.java) {
            StatementSourceGraph(root, snapshots, listOf(firstOccurrence, firstOccurrence))
        }

        val graph = StatementSourceGraph(root, snapshots, listOf(firstOccurrence, secondOccurrence))
        assertEquals(listOf(firstOccurrence, secondOccurrence), graph.dependencies)
    }

    @Test
    fun graphDefensivelySnapshotsCallerOwnedCollections() {
        val rootFile = SourceFileId("root.xml")
        val fragmentFile = SourceFileId("fragment.xml")
        val rootSnapshot = snapshot(rootFile)
        val fragmentSnapshot = snapshot(fragmentFile)
        val edge = SourceDependencyEdge(rootFile, fragmentFile, SourceRange(1, 2))
        val snapshots = mutableListOf(rootSnapshot, fragmentSnapshot)
        val dependencies = mutableListOf(edge)
        val graph = StatementSourceGraph(
            rootStatement = capturedXmlStatement(rootFile, SourceRange(0, 4)),
            sourceSnapshots = snapshots,
            dependencies = dependencies,
        )

        snapshots.clear()
        dependencies.clear()

        assertEquals(listOf(fragmentSnapshot, rootSnapshot), graph.sourceSnapshots)
        assertEquals(listOf(edge), graph.dependencies)
    }

    @Test
    fun equivalentGraphsRemainEqualAndHashEqualIndependentOfInputOrder() {
        val rootFile = SourceFileId("root.xml")
        val fragmentA = SourceFileId("a-fragment.xml")
        val fragmentB = SourceFileId("b-fragment.xml")
        val root = capturedXmlStatement(rootFile, SourceRange(0, 4))
        val snapshots = listOf(snapshot(rootFile), snapshot(fragmentA), snapshot(fragmentB))
        val edgeA = SourceDependencyEdge(rootFile, fragmentA, SourceRange(1, 2))
        val edgeB = SourceDependencyEdge(rootFile, fragmentB, SourceRange(4, 5))

        val left = StatementSourceGraph(root, snapshots, listOf(edgeA, edgeB))
        val right = StatementSourceGraph(root, snapshots.reversed(), listOf(edgeB, edgeA))

        assertEquals(left, right)
        assertEquals(left.hashCode(), right.hashCode())
    }

    @Test
    fun statementKindAndSourceRangeParticipateInCapturedStatementEquality() {
        val source = SourceFileId("root.xml")
        val id = XmlStatementId(source, "com.acme.Mapper", "find")

        assertNotEquals(
            CapturedStatement(id, StatementKind.SELECT, SourceRange(0, 4)),
            CapturedStatement(id, StatementKind.UPDATE, SourceRange(0, 4)),
        )
        assertNotEquals(
            CapturedStatement(id, StatementKind.SELECT, SourceRange(0, 4)),
            CapturedStatement(id, StatementKind.SELECT, SourceRange(1, 4)),
        )
    }

    private fun capturedXmlStatement(
        sourceFileId: SourceFileId,
        sourceRange: SourceRange,
    ): CapturedStatement = CapturedStatement(
        id = XmlStatementId(sourceFileId, "com.acme.Mapper", "find"),
        kind = StatementKind.SELECT,
        sourceRange = sourceRange,
    )

    private fun snapshot(file: String): SourceSnapshot = snapshot(SourceFileId(file))

    private fun snapshot(
        fileId: SourceFileId,
        content: String = "0123456789",
    ): SourceSnapshot = SourceSnapshot(
        fileId = fileId,
        revision = SourceRevision("revision-${fileId.value}"),
        content = content,
    )
}
