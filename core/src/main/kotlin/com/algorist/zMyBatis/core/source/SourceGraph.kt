package com.algorist.zMyBatis.core.source

data class SourceSnapshot(
    val fileId: SourceFileId,
    val revision: SourceRevision,
    val content: String,
)

data class SourceRange(
    val startOffset: Int,
    val endOffsetExclusive: Int,
) {
    init {
        require(startOffset >= 0) { "source range start offset must not be negative" }
        require(endOffsetExclusive >= startOffset) {
            "source range end offset must not precede start offset"
        }
    }
}

enum class StatementKind {
    SELECT,
    INSERT,
    UPDATE,
    DELETE,
}

data class CapturedStatement(
    val id: StatementId,
    val kind: StatementKind,
    val sourceRange: SourceRange,
)

data class SourceDependencyEdge(
    val dependentFileId: SourceFileId,
    val requiredFileId: SourceFileId,
    val referenceRange: SourceRange,
)

class StatementSourceGraph(
    val rootStatement: CapturedStatement,
    sourceSnapshots: List<SourceSnapshot>,
    dependencies: List<SourceDependencyEdge>,
) {
    private val sourceSnapshotSnapshot: List<SourceSnapshot>
    private val dependencySnapshot: List<SourceDependencyEdge>

    init {
        val capturedSnapshots = sourceSnapshots.toList()
        val capturedDependencies = dependencies.toList()
        val sourceFileIds = capturedSnapshots.map { it.fileId }

        require(sourceFileIds.size == sourceFileIds.toSet().size) {
            "source graph must contain at most one snapshot per source file"
        }

        val snapshotsByFileId = capturedSnapshots.associateBy { it.fileId }
        val rootSourceFileId = rootStatement.id.sourceFileId
        require(rootSourceFileId in snapshotsByFileId) {
            "root statement source file must have a captured snapshot"
        }

        val rootSnapshot = snapshotsByFileId.getValue(rootSourceFileId)
        require(rootStatement.sourceRange.endOffsetExclusive <= rootSnapshot.content.length) {
            "root statement source range must fit within its captured snapshot"
        }

        require(capturedDependencies.size == capturedDependencies.toSet().size) {
            "source graph must not contain duplicate dependency edges"
        }
        require(
            capturedDependencies.all {
                it.dependentFileId in snapshotsByFileId && it.requiredFileId in snapshotsByFileId
            },
        ) {
            "source dependency endpoints must have captured snapshots"
        }
        require(
            capturedDependencies.all {
                it.referenceRange.endOffsetExclusive <= snapshotsByFileId.getValue(it.dependentFileId).content.length
            },
        ) {
            "source dependency reference range must fit within its dependent snapshot"
        }

        sourceSnapshotSnapshot = capturedSnapshots.sortedBy { it.fileId.value }
        dependencySnapshot = capturedDependencies.sortedWith(
            compareBy<SourceDependencyEdge>(
                { it.dependentFileId.value },
                { it.requiredFileId.value },
                { it.referenceRange.startOffset },
                { it.referenceRange.endOffsetExclusive },
            ),
        )
    }

    val sourceSnapshots: List<SourceSnapshot>
        get() = sourceSnapshotSnapshot.toList()

    val dependencies: List<SourceDependencyEdge>
        get() = dependencySnapshot.toList()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StatementSourceGraph) return false

        return rootStatement == other.rootStatement &&
            sourceSnapshotSnapshot == other.sourceSnapshotSnapshot &&
            dependencySnapshot == other.dependencySnapshot
    }

    override fun hashCode(): Int {
        var result = rootStatement.hashCode()
        result = 31 * result + sourceSnapshotSnapshot.hashCode()
        result = 31 * result + dependencySnapshot.hashCode()
        return result
    }
}
