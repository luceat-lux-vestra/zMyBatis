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

enum class XmlDynamicIdentifierKind {
    MAPPER_NAMESPACE,
    STATEMENT_ID,
    FRAGMENT_ID,
}

enum class XmlDeclarationIdentifierKind {
    STATEMENT_ID,
    FRAGMENT_ID,
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

    data class UnsupportedReference(
        val ownerFileId: SourceFileId,
        val refid: String,
        val referenceRange: SourceRange,
    ) : XmlStatementSourceGraphFailure

    data class UnsupportedIdentifier(
        val sourceFileId: SourceFileId,
        val kind: XmlDynamicIdentifierKind,
        val value: String,
        val sourceRange: SourceRange?,
    ) : XmlStatementSourceGraphFailure

    data class UnsupportedDeclarationIdentifier(
        val sourceFileId: SourceFileId,
        val kind: XmlDeclarationIdentifierKind,
        val value: String,
        val sourceRange: SourceRange,
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

        firstDocumentFailure(rootDiscovery)?.let {
            return XmlStatementSourceGraphResult.Failed(it)
        }

        if (rootDiscovery.namespace != rootStatementId.namespace) {
            return XmlStatementSourceGraphResult.Failed(
                XmlStatementSourceGraphFailure.RootStatementMissing(rootStatementId),
            )
        }
        val rootDeclaration = rootDiscovery.statements.singleOrNull { it.id == rootStatementId.statementId }
            ?: return XmlStatementSourceGraphResult.Failed(
                XmlStatementSourceGraphFailure.RootStatementMissing(rootStatementId),
            )

        val blockerByFileId = discoveriesByFileId.values.associate { discovery ->
            discovery.sourceFileId to firstDocumentFailure(discovery)
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

        fun traverseFrom(start: FragmentCandidate): XmlStatementSourceGraphFailure? {
            when (states[start.resolvedId]) {
                VisitState.VISITED -> return null
                VisitState.VISITING -> error("fragment cannot be VISITING without an active traversal frame")
                null -> Unit
            }

            blockerByFileId[start.discovery.sourceFileId]?.let { return it }

            val frames = mutableListOf<FragmentFrame>()
            states[start.resolvedId] = VisitState.VISITING
            frames += frameFor(start)

            while (frames.isNotEmpty()) {
                val frame = frames.last()
                if (frame.nextIncludeIndex >= frame.includes.size) {
                    states[frame.candidate.resolvedId] = VisitState.VISITED
                    frames.removeAt(frames.lastIndex)
                    continue
                }

                val include = frame.includes[frame.nextIncludeIndex++]
                when (
                    val targetResolution = resolveTarget(
                        ownerDiscovery = frame.candidate.discovery,
                        include = include,
                        candidatesByCanonicalName = candidatesByCanonicalName,
                    )
                ) {
                    is TargetResolution.Failed -> return targetResolution.failure
                    is TargetResolution.Resolved -> {
                        val target = targetResolution.candidate
                        reachableFileIds += target.discovery.sourceFileId
                        dependencyEdges += SourceDependencyEdge(
                            dependentFileId = frame.candidate.discovery.sourceFileId,
                            requiredFileId = target.discovery.sourceFileId,
                            referenceRange = include.sourceRange,
                        )

                        when (states[target.resolvedId]) {
                            VisitState.VISITED -> Unit
                            VisitState.VISITING -> {
                                val activePath = frames.map { it.candidate.resolvedId }
                                val cycleStart = activePath.indexOf(target.resolvedId)
                                check(cycleStart >= 0) {
                                    "VISITING fragment must have an active traversal frame"
                                }
                                return XmlStatementSourceGraphFailure.DependencyCycle(
                                    activePath.subList(cycleStart, activePath.size) + target.resolvedId,
                                )
                            }
                            null -> {
                                blockerByFileId[target.discovery.sourceFileId]?.let { return it }
                                states[target.resolvedId] = VisitState.VISITING
                                frames += frameFor(target)
                            }
                        }
                    }
                }
            }
            return null
        }

        val rootIncludes = rootDiscovery.includes
            .filter {
                it.owner == XmlMapperDeclarationRef.Statement(rootDeclaration.id, rootDeclaration.kind)
            }
            .sortedWith(includeComparator)

        for (include in rootIncludes) {
            when (
                val targetResolution = resolveTarget(
                    ownerDiscovery = rootDiscovery,
                    include = include,
                    candidatesByCanonicalName = candidatesByCanonicalName,
                )
            ) {
                is TargetResolution.Failed -> {
                    return XmlStatementSourceGraphResult.Failed(targetResolution.failure)
                }
                is TargetResolution.Resolved -> {
                    val target = targetResolution.candidate
                    reachableFileIds += target.discovery.sourceFileId
                    dependencyEdges += SourceDependencyEdge(
                        dependentFileId = rootDiscovery.sourceFileId,
                        requiredFileId = target.discovery.sourceFileId,
                        referenceRange = include.sourceRange,
                    )
                    traverseFrom(target)?.let {
                        return XmlStatementSourceGraphResult.Failed(it)
                    }
                }
            }
        }

        val graph = StatementSourceGraph(
            rootStatement = CapturedStatement(
                id = rootStatementId,
                kind = rootDeclaration.kind,
                sourceRange = rootDeclaration.sourceRange,
            ),
            sourceSnapshots = reachableFileIds.map { snapshotsByFileId.getValue(it) },
            dependencies = dependencyEdges,
        )
        return XmlStatementSourceGraphResult.Resolved(graph)
    }

    private fun resolveTarget(
        ownerDiscovery: XmlMapperDocumentDiscovery,
        include: XmlMapperIncludeReference,
        candidatesByCanonicalName: Map<String, List<FragmentCandidate>>,
    ): TargetResolution {
        if (containsPropertyPlaceholder(include.refid)) {
            return TargetResolution.Failed(
                XmlStatementSourceGraphFailure.UnsupportedReference(
                    ownerFileId = ownerDiscovery.sourceFileId,
                    refid = include.refid,
                    referenceRange = include.sourceRange,
                ),
            )
        }

        val canonicalName = if ('.' in include.refid) {
            include.refid
        } else {
            "${ownerDiscovery.namespace}.${include.refid}"
        }
        val candidates = candidatesByCanonicalName[canonicalName].orEmpty()
        if (candidates.isEmpty()) {
            return TargetResolution.Failed(
                XmlStatementSourceGraphFailure.MissingFragment(
                    ownerFileId = ownerDiscovery.sourceFileId,
                    refid = include.refid,
                    referenceRange = include.sourceRange,
                ),
            )
        }
        if (candidates.size != 1) {
            return TargetResolution.Failed(
                XmlStatementSourceGraphFailure.AmbiguousFragment(
                    ownerFileId = ownerDiscovery.sourceFileId,
                    refid = include.refid,
                    referenceRange = include.sourceRange,
                    candidates = candidates.map { it.resolvedId },
                ),
            )
        }
        return TargetResolution.Resolved(candidates.single())
    }

    private fun firstDocumentFailure(
        discovery: XmlMapperDocumentDiscovery,
    ): XmlStatementSourceGraphFailure? {
        if (containsPropertyPlaceholder(discovery.namespace)) {
            return XmlStatementSourceGraphFailure.UnsupportedIdentifier(
                sourceFileId = discovery.sourceFileId,
                kind = XmlDynamicIdentifierKind.MAPPER_NAMESPACE,
                value = discovery.namespace,
                sourceRange = null,
            )
        }

        val dynamicDeclaration = buildList {
            discovery.statements.forEach {
                if (containsPropertyPlaceholder(it.id)) {
                    add(
                        DynamicIdentifier(
                            kind = XmlDynamicIdentifierKind.STATEMENT_ID,
                            value = it.id,
                            sourceRange = it.sourceRange,
                        ),
                    )
                }
            }
            discovery.fragments.forEach {
                if (containsPropertyPlaceholder(it.id)) {
                    add(
                        DynamicIdentifier(
                            kind = XmlDynamicIdentifierKind.FRAGMENT_ID,
                            value = it.id,
                            sourceRange = it.sourceRange,
                        ),
                    )
                }
            }
        }.minWithOrNull(
            compareBy<DynamicIdentifier>(
                { it.sourceRange.startOffset },
                { it.sourceRange.endOffsetExclusive },
                { it.kind.name },
                { it.value },
            ),
        )
        if (dynamicDeclaration != null) {
            return XmlStatementSourceGraphFailure.UnsupportedIdentifier(
                sourceFileId = discovery.sourceFileId,
                kind = dynamicDeclaration.kind,
                value = dynamicDeclaration.value,
                sourceRange = dynamicDeclaration.sourceRange,
            )
        }

        val dottedDeclaration = buildList {
            discovery.statements.forEach {
                if ('.' in it.id) {
                    add(
                        DottedDeclaration(
                            kind = XmlDeclarationIdentifierKind.STATEMENT_ID,
                            value = it.id,
                            sourceRange = it.sourceRange,
                        ),
                    )
                }
            }
            discovery.fragments.forEach {
                if ('.' in it.id) {
                    add(
                        DottedDeclaration(
                            kind = XmlDeclarationIdentifierKind.FRAGMENT_ID,
                            value = it.id,
                            sourceRange = it.sourceRange,
                        ),
                    )
                }
            }
        }.minWithOrNull(
            compareBy<DottedDeclaration>(
                { it.sourceRange.startOffset },
                { it.sourceRange.endOffsetExclusive },
                { it.kind.name },
                { it.value },
            ),
        )
        if (dottedDeclaration != null) {
            return XmlStatementSourceGraphFailure.UnsupportedDeclarationIdentifier(
                sourceFileId = discovery.sourceFileId,
                kind = dottedDeclaration.kind,
                value = dottedDeclaration.value,
                sourceRange = dottedDeclaration.sourceRange,
            )
        }

        val unsupported = discovery.unsupportedSemantics.minWithOrNull(
            compareBy<XmlUnsupportedSemanticsEvidence>(
                { it.sourceRange.startOffset },
                { it.sourceRange.endOffsetExclusive },
                { it.kind.name },
                { it.elementName },
                { it.value },
            ),
        )
        return unsupported?.let {
            XmlStatementSourceGraphFailure.UnsupportedSemantics(discovery.sourceFileId, it)
        }
    }

    private fun containsPropertyPlaceholder(value: String): Boolean = value.contains("\${")

    private fun frameFor(candidate: FragmentCandidate): FragmentFrame =
        FragmentFrame(
            candidate = candidate,
            includes = candidate.discovery.includes
                .filter { it.owner == XmlMapperDeclarationRef.Fragment(candidate.declaration.id) }
                .sortedWith(includeComparator),
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

    private data class DynamicIdentifier(
        val kind: XmlDynamicIdentifierKind,
        val value: String,
        val sourceRange: SourceRange,
    )

    private data class DottedDeclaration(
        val kind: XmlDeclarationIdentifierKind,
        val value: String,
        val sourceRange: SourceRange,
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

    private data class FragmentFrame(
        val candidate: FragmentCandidate,
        val includes: List<XmlMapperIncludeReference>,
        var nextIncludeIndex: Int = 0,
    )

    private sealed interface TargetResolution {
        data class Resolved(val candidate: FragmentCandidate) : TargetResolution
        data class Failed(val failure: XmlStatementSourceGraphFailure) : TargetResolution
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
