package com.algorist.zMyBatis.core.preparation

import com.algorist.zMyBatis.core.source.StatementSourceGraph
import com.algorist.zMyBatis.core.source.XmlStatementId

/**
 * Immutable XML mapper preparation input backed only by the authoritative #62 source graph.
 *
 * XML semantic parsing belongs to :mybatis-engine. This core type deliberately carries no
 * MyBatis, IntelliJ, VFS, PSI, or Database Tools object.
 */
data class XmlMapperPreparationSource(
    override val sourceGraph: StatementSourceGraph,
) : PreparationSource {
    init {
        require(sourceGraph.rootStatement.id is XmlStatementId) {
            "XML mapper preparation source requires a canonical XML statement id"
        }
    }
}
