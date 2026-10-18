package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.preparation.PreparationSource
import com.algorist.zMyBatis.core.preparation.XmlMapperPreparationSource
import com.algorist.zMyBatis.core.source.CapturedStatement
import com.algorist.zMyBatis.core.source.JavaAnnotationStatementCapture
import com.algorist.zMyBatis.core.source.JavaStatementId
import com.algorist.zMyBatis.core.source.MethodSignature
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.StatementSourceGraph
import com.algorist.zMyBatis.core.source.XmlStatementId
import org.junit.Assert.assertEquals
import org.junit.Test

class PreparationSourceAuthorityTest {
    @Test
    fun javaAuthorityIsExactlyItsCapturedGraph() {
        val root = snapshot("vfs:/Mapper.java", "document:1")
        val graph = javaGraph(root)
        val capture = JavaAnnotationStatementCapture(
            sourceGraph = graph,
            sqlSegments = listOf("select 1"),
            parameters = emptyList(),
        )

        val authority = preparationSourceAuthority(PreparationSource.JavaAnnotation(capture))

        assertEquals(listOf(root), authority.snapshots)
        assertEquals(mapOf(root.fileId to root.revision), authority.sourceRevisions)
    }

    @Test
    fun xmlAuthorityIncludesAdditionalMapperMethodSnapshots() {
        val root = snapshot("vfs:/Mapper.xml", "vfs:1")
        val included = snapshot("vfs:/Fragments.xml", "vfs:2")
        val mapperMethod = snapshot("vfs:/Mapper.java", "document:3")
        val graph = xmlGraph(root, included)

        val authority = preparationSourceAuthority(
            XmlMapperPreparationSource(
                sourceGraph = graph,
                additionalAuthoritySnapshots = listOf(mapperMethod),
            ),
        )

        assertEquals(
            listOf(included, mapperMethod, root).sortedBy { it.fileId.value },
            authority.snapshots,
        )
        assertEquals(
            mapOf(
                root.fileId to root.revision,
                included.fileId to included.revision,
                mapperMethod.fileId to mapperMethod.revision,
            ),
            authority.sourceRevisions,
        )
    }

    private fun snapshot(id: String, revision: String) =
        SourceSnapshot(SourceFileId(id), SourceRevision(revision), "source")

    private fun javaGraph(root: SourceSnapshot): StatementSourceGraph =
        StatementSourceGraph(
            rootStatement = CapturedStatement(
                JavaStatementId(root.fileId, "fixture.Mapper", MethodSignature("find", emptyList())),
                StatementKind.SELECT,
                SourceRange(0, root.content.length),
            ),
            sourceSnapshots = listOf(root),
            dependencies = emptyList(),
        )

    private fun xmlGraph(root: SourceSnapshot, included: SourceSnapshot): StatementSourceGraph =
        StatementSourceGraph(
            rootStatement = CapturedStatement(
                XmlStatementId(root.fileId, "fixture.Mapper", "find"),
                StatementKind.SELECT,
                SourceRange(0, root.content.length),
            ),
            sourceSnapshots = listOf(root, included),
            dependencies = emptyList(),
        )
}
