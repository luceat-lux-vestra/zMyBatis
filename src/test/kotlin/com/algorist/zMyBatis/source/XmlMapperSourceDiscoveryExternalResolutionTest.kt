package com.algorist.zMyBatis.source

import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlMapperSourceDiscoveryExternalResolutionTest {
    @Test
    fun externalDtdLocationsAreNotFetched() {
        val unreachableSystemIds = listOf(
            "http://127.0.0.1:1/zmybatis-must-not-connect.dtd",
            "file:///__zmybatis_external_resolution_must_not_happen__/never.dtd",
        )

        for (systemId in unreachableSystemIds) {
            val xml = """
                <!DOCTYPE mapper SYSTEM "$systemId">
                <mapper namespace="fixture.Mapper">
                  <select id="find">SELECT 1</select>
                </mapper>
            """.trimIndent()

            val result = XmlMapperSourceDiscovery.discover(
                SourceSnapshot(
                    fileId = SourceFileId("vfs:file:///fixture/Mapper.xml"),
                    revision = SourceRevision("document:external-resolution"),
                    content = xml,
                ),
            )

            assertTrue(
                "external DTD location must not be fetched: $systemId; result=$result",
                result is XmlMapperDiscoveryResult.Discovered,
            )
        }
    }
}
