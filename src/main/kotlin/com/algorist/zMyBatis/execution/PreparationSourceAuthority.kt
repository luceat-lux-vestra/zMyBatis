package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.preparation.PreparationSource
import com.algorist.zMyBatis.core.preparation.XmlMapperPreparationSource
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot

/**
 * Immutable source authority carried across the registered #66 delivery orchestration.
 *
 * Java authority is its captured source graph. XML authority additionally includes every snapshot
 * required to prove mapper-method/input semantics.
 */
internal class PreparationSourceAuthority(snapshots: List<SourceSnapshot>) {
    private val snapshot = snapshots.sortedBy { it.fileId.value }

    init {
        require(snapshot.map { it.fileId }.distinct().size == snapshot.size) {
            "preparation source authority must contain unique source identities"
        }
    }

    val snapshots: List<SourceSnapshot>
        get() = snapshot.toList()

    val sourceRevisions: Map<SourceFileId, SourceRevision>
        get() = LinkedHashMap(snapshot.associate { it.fileId to it.revision })
}

internal fun preparationSourceAuthority(source: PreparationSource): PreparationSourceAuthority =
    PreparationSourceAuthority(
        when (source) {
            is PreparationSource.JavaAnnotation -> source.sourceGraph.sourceSnapshots
            is XmlMapperPreparationSource ->
                source.sourceGraph.sourceSnapshots + source.additionalAuthoritySnapshots
        },
    )
