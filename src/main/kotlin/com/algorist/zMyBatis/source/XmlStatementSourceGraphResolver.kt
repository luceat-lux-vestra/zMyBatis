package com.algorist.zMyBatis.source

import com.algorist.zMyBatis.core.source.CapturedStatement
import com.algorist.zMyBatis.core.source.SourceDependencyEdge
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.StatementSourceGraph
import com.algorist.zMyBatis.core.source.XmlStatementId

sealed interface XmlStatementSourceGraphResult {
    data class Resolved(val graph: StatementSourceGraph) : XmlStatementSourceGraphResult
    data class Failed(val failure: XmlStatementSourceGraphFailure) : XmlStatementSourceGraphResult
}

enum class XmlSourceGraphInputMismatchReason {
    DUPLICATE_SNAPSHOT,
    DUPLICATE_DISCOVERY,
    MISSING_DISCOVERY,
    MISSING_SNAPSHOT,
    REVISION_MISMATCH,
}

data class XmlResolvedFragmentId(
    val sourceFileId: SourceFileId,
    val namespace: String,
    val fragmentId: String,
) {
    init {
        require(namespace.isNotBlank()) { "fragment namespace must not be blank" }
        require(fragmentId.isNotBlank()) { "fragment id must not be blank" }
    }
}

sealed interface XmlStatementSourceGraphFailure {
    data class InputMismatch(
        val reason: XmlSourceGraphInputMismatchReason,
        val sourceFileId: SourceFileId,
    ) : XmlStatementSourceGraphFailure

    data class RootSourceMissing(
        val statementId: XmlStatementId,
    ) : XmlStatementSourceGraphFailure

    data class RootStatementMissing(
        val statementId: XmlStatementId,
    ) : XmlStatementSourceGraphFailure

    data class MissingFragment(
        val ownerFileId: SourceFileId,
        val refid: String,
        val referenceRange: SourceRange,
    ) : XmlStatementSourceGraphFailure

    class AmbiguousFragment(
        val ownerFileId: SourceFileId,
        val refid: String,
        val referenceRange: SourceRange,
        candidates: List<XmlResolvedFragmentId>,
    ) : XmlStatementSourceGraphFailure {
        private val candidateSnapshot = candidates.toList()

        val candidates: List<XmlResolvedFragmentId>
            get() = candidateSnapshot.toList()

        override fun equals(other: Any?): Boolean =
            other is AmbiguousFragment &&
                ownerFileId == other.ownerFileId &&
                refid == other.refid &&
                referenceRange == other.referenceRange &&
                candidateSnapshot == other.candidateSnapshot

        override fun hashCode(): Int {
            var result = ownerFileId.hashCode()
            result = 31 * result + refid.hashCode()
            result = 31 * result + referenceRange.hashCode()
            result = 31 * result + candidateSnapshot.hashCode()
            return result
        }
    }

    class DependencyCycle(
        path: List<XmlResolvedFragmentId>,
    ) : XmlStatementSourceGraphFailure {
        private val pathSnapshot = path.toList()

        val path: List<XmlResolvedFragmentId>
            get() = pathSnapshot.toList()

        override fun equals(other: Any?): Boolean =
            other is DependencyCycle && pathSnapshot == other.pathSnapshot

        override fun hashCode(): Int = pathSnapshot.hashCode()
    }

    data class UnsupportedSemantics(
        val sourceFileId: SourceFileId,
        val evidence: XmlUnsupportedSemanticsEvidence,
    ) : XmlStatementSourceGraphFailure
}

object XmlStatementSourceGraphResolver {
    fun resolve(
        rootStatementId: XmlStatementId,
        snapshots: List<SourceSnapshot>,
        discoveries: List<XmlMapperDocumentDiscovery>,
    ): XmlStatementSourceGraphResult {
        val snapshotGroups = snapshots.groupBy { it.fileId }
        duplicateKey(snapshotGroups)?.let {
            return failedInput(XmlSourceGraphInputMismatchReason.DUPLICATE_SNAPSHOT, it)
        }

        val discoveryGroups = discoveries.groupBy { it.sourceFileId }
        duplicateKey(discoveryGroups)?.let {
            return failedInput(XmlSourceGraphInputMismatchReason.DUPLICATE_DISCOVERY, it)
        }

        val snapshotsByFileId = snapshotGroups.mapValues { it.value.single() }
        val discoveriesByFileId = discoveryGroups.mapValues { it.value.single() }

        firstSorted(snapshotsByFileId.keys - discoveriesByFileId.keys)?.let {
            return failedInput(XmlSourceGraphInputMismatchReason.MISSING_DISCOVERY, it)
        }
        firstSorted(discoveriesByFileId.keys - snapshotsByFileId.keys)?.let {
            return failedInput(XmlSourceGraphInputMismatchReason.MISSING_SNAPSHOT, it)
        }
        snapshotsByFileId.keys.sortedBy { it.value }.firstOrNull {
            snapshotsByFileId.getValue(it).revision != discoveriesByFileId.getValue(it).sourceRevision
        }?.let {
            return failedInput(XmlSourceGraphInputMismatchReason.REVISION_MISMATCH, it)
        }

        val rootSnapshot = snapshotsByFileId[rootStatementId.sourceFileId]
            ?: return XmlStatementSourceGraphResult.Failed(
                XmlStatementSourceGraphFailure.RootSourceMissing(rootStatementId),
            )
        val rootDiscovery = discoveriesByFileId[rootStatementId.sourceFileId]
            ?: return XmlStatementSourceGraphResult.Failed(
                XmlStatementSourceGraphFailure.RootSourceMissing(rootStatementId),
            )

        if (rootDiscovery.namespace != rootStatementId.namespace) {
            return XmlStatementSourceGraphResult.Failed(
                XmlStatementSourceGraphFailure.RootStatementMissing(rootStatementId),
            )
        }
        val rootDeclaration = rootDiscovery.statements.singleOrNull { it.id == rootStatementId.statementId }
            ?: return XmlStatementSourceGraphResult.Failed(
                XmlStatementSourceGraphFailure.RootStatementMissing(rootStatementId),
            )

        firstUnsupported(rootDiscovery)?.let {
            return XmlStatementSourceGraphResult.Failed(
                XmlStatementSourceGraphFailure.UnsupportedSemantics(rootDiscovery.sourceFileId, it),
            )
        }

        val candidatesByCanonicalName = discoveriesByFileId.values
            .sortedBy { it.sourceFileId.value }
            .flatMap { discovery ->
                discovery.fragments.map { fragment -> FragmentCandidate(discovery, fragment) }
            }
            .groupBy { it.canonicalName }
            .mapValues { (_, candidates) -> candidates.sortedWith(fragmentCandidateComparator) }

        val reachableFileIds = linkedSetOf(rootSnapshot.fileId)
        val dependencyEdges = mutableListOf<SourceDependencyEdge>()
        val states = mutableMapOf<XmlResolvedFragmentId, VisitState>()
        val stack = mutableListOf<XmlResolvedFragmentId>()

        fun visitFragment(candidate: FragmentCandidate): XmlStatementSourceGraphFailure? {
            val id = candidate.resolvedId
            when (states[id]) {
                VisitState.VISITED -> return null
                VisitState.VISITING -> {
                    val cycleStart = stack.indexOf(id).coerceAtLeast(0)
                    return XmlStatementSourceGraphFailure.DependencyCycle(
                        stack.subList(cycleStart, stack.size).toList() + id,
                    )
                }
                null -> Unit
            }

            firstUnsupported(candidate.discovery)?.let {
                return XmlStatementSourceGraphFailure.UnsupportedSemantics(
                    candidate.discovery.sourceFileId,
                    it,
                )
            }

            states[id] = VisitState.VISITING
            stack += id

            val includes = candidate.discovery.includes
                .filter { it.owner == XmlMapperDeclarationRef.Fragment(candidate.declaration.id) }
                .sortedWith(includeComparator)

            for (include in includes) {
                val failure = resolveInclude(
                    ownerDiscovery = candidate.discovery,
                    include = include,
                    candidatesByCanonicalName = candidatesByCanonicalName,
                    reachableFileIds = reachableFileIds,
                    dependencyEdges = dependencyEdges,
                    visitFragment = ::visitFragment,
                )
                if (failure != null) {
                    return failure
                }
            }

            stack.removeAt(stack.lastIndex)
            states[id] = VisitState.VISITED
            return null
        }

        val rootIncludes = rootDiscovery.includes
            .filter {
                it.owner == XmlMapperDeclarationRef.Statement(rootDeclaration.id, rootDeclaration.kind)
            }
            .sortedWith(includeComparator)

        for (include in rootIncludes) {
            val failure = resolveInclude(
                ownerDiscovery = rootDiscovery,
                include = include,
                candidatesByCanonicalName = candidatesByCanonicalName,
                reachableFileIds = reachableFileIds,
                dependencyEdges = dependencyEdges,
                visitFragment = ::visitFragment,
            )
            if (failure != null) {
                return XmlStatementSourceGraphResult.Failed(failure)
            }
        }

        val graph = StatementSourceGraph(
            rootStatement = CapturedStatement(
                id = rootStatementId,
                kind = rootDeclaration.kind,
                sourceRange = rootDeclaration.sourceRange,
            ),
            sourceSnapshots = reachableFileIds
                .map { snapshotsByFileId.getValue(it) },
            dependencies = dependencyEdges,
        )
        return XmlStatementSourceGraphResult.Resolved(graph)
    }

    private fun resolveInclude(
        ownerDiscovery: XmlMapperDocumentDiscovery,
        include: XmlMapperIncludeReference,
        candidatesByCanonicalName: Map<String, List<FragmentCandidate>>,
        reachableFileIds: MutableSet<SourceFileId>,
        dependencyEdges: MutableList<SourceDependencyEdge>,
        visitFragment: (FragmentCandidate) -> XmlStatementSourceGraphFailure?,
    ): XmlStatementSourceGraphFailure? {
        val canonicalName = if ('.' in include.refid) {
            include.refid
        } else {
            "${ownerDiscovery.namespace}.${include.refid}"
        }
        val candidates = candidatesByCanonicalName[canonicalName].orEmpty()
        if (candidates.isEmpty()) {
            return XmlStatementSourceGraphFailure.MissingFragment(
                ownerFileId = ownerDiscovery.sourceFileId,
                refid = include.refid,
                referenceRange = include.sourceRange,
            )
        }
        if (candidates.size != 1) {
            return XmlStatementSourceGraphFailure.AmbiguousFragment(
                ownerFileId = ownerDiscovery.sourceFileId,
                refid = include.refid,
                referenceRange = include.sourceRange,
                candidates = candidates.map { it.resolvedId },
            )
        }

        val target = candidates.single()
        reachableFileIds += target.discovery.sourceFileId
        dependencyEdges += SourceDependencyEdge(
            dependentFileId = ownerDiscovery.sourceFileId,
            requiredFileId = target.discovery.sourceFileId,
            referenceRange = include.sourceRange,
        )
        return visitFragment(target)
    }

    private fun firstUnsupported(
        discovery: XmlMapperDocumentDiscovery,
    ): XmlUnsupportedSemanticsEvidence? =
        discovery.unsupportedSemantics.minWithOrNull(
            compareBy<XmlUnsupportedSemanticsEvidence>(
                { it.sourceRange.startOffset },
                { it.sourceRange.endOffsetExclusive },
                { it.kind.name },
                { it.elementName },
                { it.value },
            ),
        )

    private fun <T> duplicateKey(groups: Map<SourceFileId, List<T>>): SourceFileId? =
        groups.entries
            .asSequence()
            .filter { it.value.size > 1 }
            .map { it.key }
            .sortedBy { it.value }
            .firstOrNull()

    private fun firstSorted(ids: Collection<SourceFileId>): SourceFileId? =
        ids.minByOrNull { it.value }

    private fun failedInput(
        reason: XmlSourceGraphInputMismatchReason,
        sourceFileId: SourceFileId,
    ): XmlStatementSourceGraphResult =
        XmlStatementSourceGraphResult.Failed(
            XmlStatementSourceGraphFailure.InputMismatch(reason, sourceFileId),
        )

    private data class FragmentCandidate(
        val discovery: XmlMapperDocumentDiscovery,
        val declaration: XmlMapperFragmentDeclaration,
    ) {
        val canonicalName: String = "${discovery.namespace}.${declaration.id}"
        val resolvedId: XmlResolvedFragmentId = XmlResolvedFragmentId(
            sourceFileId = discovery.sourceFileId,
            namespace = discovery.namespace,
            fragmentId = declaration.id,
        )
    }

    private enum class VisitState {
        VISITING,
        VISITED,
    }

    private val fragmentCandidateComparator =
        compareBy<FragmentCandidate>(
            { it.discovery.sourceFileId.value },
            { it.declaration.sourceRange.startOffset },
            { it.declaration.sourceRange.endOffsetExclusive },
            { it.declaration.id },
        )

    private val includeComparator =
        compareBy<XmlMapperIncludeReference>(
            { it.sourceRange.startOffset },
            { it.sourceRange.endOffsetExclusive },
            { it.refid },
        )
}