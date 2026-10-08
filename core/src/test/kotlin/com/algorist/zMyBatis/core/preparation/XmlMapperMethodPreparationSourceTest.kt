package com.algorist.zMyBatis.core.preparation

import com.algorist.zMyBatis.core.source.CapturedStatement
import com.algorist.zMyBatis.core.source.JavaMethodParameterMetadata
import com.algorist.zMyBatis.core.source.JavaTypeIdentity
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.StatementSourceGraph
import com.algorist.zMyBatis.core.source.XmlMapperMethodCapture
import com.algorist.zMyBatis.core.source.XmlStatementId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class XmlMapperMethodPreparationSourceTest {
    @Test
    fun mapperSourceParticipatesInAuthorityAndCopiesCallerSnapshotList() {
        val fixture = fixture()
        val callerSnapshots = mutableListOf<SourceSnapshot>()
        val source = XmlMapperPreparationSource(fixture.first, callerSnapshots, mapperMethod = fixture.second)
        callerSnapshots += SourceSnapshot(SourceFileId("orphan"), SourceRevision("other"), "other")
        assertEquals(listOf(fixture.second.mapperSource), source.additionalAuthoritySnapshots)
        assertEquals(fixture.second, source.mapperMethod)
        assertEquals(mapOf(XML_FILE to REVISION, JAVA_FILE to REVISION), source.sourceRevisions)
    }

    @Test
    fun identicalExplicitMapperSnapshotIsDeduplicatedButContentAndRevisionDriftAreRefused() {
        val (graph, mapper) = fixture()
        val source = XmlMapperPreparationSource(graph, listOf(mapper.mapperSource), mapperMethod = mapper)
        assertEquals(listOf(mapper.mapperSource), source.additionalAuthoritySnapshots)
        for (changed in listOf(
            mapper.mapperSource.copy(content = "different source with same revision"),
            mapper.mapperSource.copy(revision = SourceRevision("changed")),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                XmlMapperPreparationSource(graph, listOf(changed), mapperMethod = mapper)
            }
        }
    }

    private fun fixture(): Pair<StatementSourceGraph, XmlMapperMethodCapture> {
        val id = XmlStatementId(XML_FILE, "example.Mapper", "find")
        val xml = "<mapper namespace=\"example.Mapper\"><select id=\"find\">SELECT 1</select></mapper>"
        val graph = StatementSourceGraph(CapturedStatement(id, StatementKind.SELECT, SourceRange(0, xml.length)), listOf(SourceSnapshot(XML_FILE, REVISION, xml)), emptyList())
        val mapper = XmlMapperMethodCapture(id, SourceSnapshot(JAVA_FILE, REVISION, "mapper method"), SourceRange(0, 13), listOf(JavaMethodParameterMetadata(0, "enabled", JavaTypeIdentity("boolean"), "enabled")))
        return graph to mapper
    }

    private companion object {
        val XML_FILE = SourceFileId("mapper.xml")
        val JAVA_FILE = SourceFileId("Mapper.java")
        val REVISION = SourceRevision("r1")
    }
}
