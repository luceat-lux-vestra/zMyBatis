package com.algorist.zMyBatis.core.preparation

import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.StatementSourceGraph
import com.algorist.zMyBatis.core.source.XmlStatementId

/**
 * Immutable XML mapper preparation input.
 *
 * The source graph contains the XML documents that MyBatis may parse. Additional authority snapshots
 * carry non-XML source revisions that were required to prove the input contract (for example the
 * captured Java mapper method source). They participate in stale-source validation but are never
 * passed to the XML parser.
 *
 * XML semantic parsing belongs to :mybatis-engine. This core type deliberately carries no MyBatis,
 * IntelliJ, VFS, PSI, or Database Tools object.
 */
class XmlMapperPreparationSource(
    override val sourceGraph: StatementSourceGraph,
    additionalAuthoritySnapshots: List<SourceSnapshot> = emptyList(),
) : PreparationSource {
    private val additionalAuthoritySnapshot = additionalAuthoritySnapshots.toList()

    init {
        require(sourceGraph.rootStatement.id is XmlStatementId) {
            "XML mapper preparation source requires a canonical XML statement id"
        }

        val graphFileIds = sourceGraph.sourceSnapshots.mapTo(linkedSetOf()) { it.fileId }
        require(additionalAuthoritySnapshot.map { it.fileId }.distinct().size == additionalAuthoritySnapshot.size) {
            "XML mapper preparation authority snapshots must have unique file identities"
        }
        require(additionalAuthoritySnapshot.none { it.fileId in graphFileIds }) {
            "XML mapper preparation authority snapshots must not duplicate XML graph sources"
        }
    }

    val additionalAuthoritySnapshots: List<SourceSnapshot>
        get() = additionalAuthoritySnapshot.toList()

    val sourceRevisions: Map<SourceFileId, SourceRevision>
        get() = buildMap {
            sourceGraph.sourceSnapshots.forEach { snapshot ->
                put(snapshot.fileId, snapshot.revision)
            }
            additionalAuthoritySnapshot.forEach { snapshot ->
                put(snapshot.fileId, snapshot.revision)
            }
        }
}
