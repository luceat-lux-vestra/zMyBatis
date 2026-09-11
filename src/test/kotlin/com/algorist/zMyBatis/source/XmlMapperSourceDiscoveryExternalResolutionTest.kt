package com.algorist.zMyBatis.source

import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlMapperSourceDiscoveryExternalResolutionTest {
    @Test
    fun externalDtdLocationsAreNotFetched() {
        val connected = AtomicBoolean(false)
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread(start = true, isDaemon = true, name = "xml-external-dtd-probe") {
            try {
                server.accept().use { socket ->
                    connected.set(true)
                    socket.getOutputStream().bufferedWriter(Charsets.US_ASCII).use { writer ->
                        writer.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                        writer.flush()
                    }
                }
            } catch (_: SocketException) {
                // Expected when discovery performs no network access and the listener is closed below.
            }
        }

        try {
            val systemIds = listOf(
                "http://127.0.0.1:${server.localPort}/zmybatis-must-not-connect.dtd",
                "file:///__zmybatis_external_resolution_must_not_happen__/never.dtd",
            )

            for (systemId in systemIds) {
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
                    "external DTD location must not affect discovery: $systemId; result=$result",
                    result is XmlMapperDiscoveryResult.Discovered,
                )
            }
        } finally {
            server.close()
            responder.join(1_000)
        }

        assertFalse("XML parser attempted a loopback connection for an external DTD", connected.get())
    }
}
