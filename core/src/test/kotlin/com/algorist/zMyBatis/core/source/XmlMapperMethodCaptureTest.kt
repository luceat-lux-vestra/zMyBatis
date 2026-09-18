package com.algorist.zMyBatis.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test

class XmlMapperMethodCaptureTest {
    @Test
    fun captureDefensivelyCopiesParametersAndKeepsXmlIdentity() {
        val statementId = XmlStatementId(
            SourceFileId("vfs:/mapper.xml"),
            "fixture.UserMapper",
            "find",
        )
        val mapperSource = SourceSnapshot(
            SourceFileId("vfs:/UserMapper.java"),
            SourceRevision("document:7"),
            "interface UserMapper { Object find(int id); }",
        )
        val parameters = mutableListOf(
            JavaMethodParameterMetadata(
                0,
                "id",
                JavaTypeIdentity("int"),
                "userId",
            ),
        )

        val capture = XmlMapperMethodCapture(
            statementId,
            mapperSource,
            SourceRange(23, 43),
            parameters,
        )
        parameters.clear()

        assertEquals(statementId, capture.statementId)
        assertEquals(mapperSource, capture.mapperSource)
        assertEquals(1, capture.parameters.size)
        assertEquals("userId", capture.parameters.single().myBatisParamAlias)
        assertNotSame(capture.parameters, capture.parameters)
    }

    @Test
    fun methodRangeMustFitCapturedJavaSource() {
        val statementId = XmlStatementId(
            SourceFileId("vfs:/mapper.xml"),
            "fixture.UserMapper",
            "find",
        )
        val mapperSource = SourceSnapshot(
            SourceFileId("vfs:/UserMapper.java"),
            SourceRevision("vfs:1"),
            "interface UserMapper {}",
        )

        assertThrows(IllegalArgumentException::class.java) {
            XmlMapperMethodCapture(
                statementId,
                mapperSource,
                SourceRange(0, mapperSource.content.length + 1),
                emptyList(),
            )
        }
    }

    @Test
    fun parametersMustRemainContiguousAndDeclarationOrdered() {
        val statementId = XmlStatementId(
            SourceFileId("vfs:/mapper.xml"),
            "fixture.UserMapper",
            "find",
        )
        val mapperSource = SourceSnapshot(
            SourceFileId("vfs:/UserMapper.java"),
            SourceRevision("vfs:1"),
            "interface UserMapper { Object find(int id); }",
        )

        assertThrows(IllegalArgumentException::class.java) {
            XmlMapperMethodCapture(
                statementId,
                mapperSource,
                SourceRange(0, mapperSource.content.length),
                listOf(
                    JavaMethodParameterMetadata(
                        1,
                        "id",
                        JavaTypeIdentity("int"),
                        "id",
                    ),
                ),
            )
        }
    }
}
