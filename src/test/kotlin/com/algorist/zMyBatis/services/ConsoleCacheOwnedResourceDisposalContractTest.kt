package com.algorist.zMyBatis.services

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleCacheOwnedResourceDisposalContractTest {

    @Test
    fun `replacement detaches under lock and disposes superseded console after lock`() {
        val source = serviceSource()
        val registration = source
            .substringAfter("private fun registerConsole(")
            .substringBefore("@Suppress(\"TooGenericExceptionCaught\")")

        val replace = registration.indexOf("supersededEntry = cache.put(mapperKey, entry)")
        val acceptedEnd = registration.indexOf("if (!accepted) {")
        val disposeSuperseded = registration.indexOf("disposeDetachedEntries(listOf(it))")

        assertTrue(replace >= 0)
        assertTrue("replacement must be captured before acceptance handling", acceptedEnd > replace)
        assertTrue(
            "superseded resource disposal must happen after the synchronized replacement block",
            disposeSuperseded > acceptedEnd,
        )
    }

    @Test
    fun `service disposal detaches cache before disposing live resources`() {
        val source = serviceSource()
        val disposal = source.substringAfter("override fun dispose() {")

        val synchronizedStart = disposal.indexOf("val detachedEntries = synchronized(lifecycleLock)")
        val clear = disposal.indexOf("cache.clear()")
        val disposeDetached = disposal.indexOf("disposeDetachedEntries(detachedEntries)")

        assertTrue(synchronizedStart >= 0)
        assertTrue(clear > synchronizedStart)
        assertTrue(
            "actual resource disposal must happen after entries are detached from the cache",
            disposeDetached > clear,
        )
    }

    @Test
    fun `REUSE checks matching cache under a keyed lease before constructing a replacement`() {
        val adapter = Files.readString(
            repositoryRoot().resolve(
                "src/main/kotlin/com/algorist/zMyBatis/execution/DatabaseToolsConsoleAdapter.kt",
            ),
        )
        val acquisition = adapter
            .substringAfter("fun acquireAndDeliverConsole(")
            .substringBefore("private fun switchSchemaOnConsole")

        val lease = acquisition.indexOf("cache.beginReuseLease(resourceKey)")
        val cached = acquisition.indexOf("cache.get(resourceKey, targetId)")
        val schemaRecheck = acquisition.indexOf("switchSchemaOnConsole(cached, schema)")
        val reuse = acquisition.indexOf("onConsoleReady(cached, releaseReuseLease)")
        val construct = acquisition.indexOf("JdbcConsole.newConsole(project)")

        assertTrue("REUSE must acquire same-source ownership before cache access", lease >= 0)
        assertTrue("matching cache lookup must occur after keyed ownership", cached > lease)
        assertTrue("cached explicit schema must be re-applied before handoff", schemaRecheck > cached)
        assertTrue("a matching live console must be handed off directly", reuse > schemaRecheck)
        assertTrue("cache reuse must be attempted before constructing a replacement", construct > reuse)
        assertTrue(adapter.contains("data object ReuseBusy : DatabaseToolsConsoleAcquisitionFailure"))
    }

    @Test
    fun `detached console cleanup uses fail safe policy and stale sentinel is identity safe`() {
        val source = serviceSource()

        assertTrue(source.contains("disposeOwnedResourcesPreservingFailureSemantics("))
        assertTrue(source.contains("isDisposed = { it.sentinel.isDisposed }"))
        assertTrue(source.contains("disposeResource = { Disposer.dispose(it.console) }"))
        assertTrue(
            "stale sentinel callback must not remove a replacement entry",
            source.contains("cache.remove(mapperKey, entry)"),
        )
    }

    private fun serviceSource(): String =
        Files.readString(
            repositoryRoot().resolve(
                "src/main/kotlin/com/algorist/zMyBatis/services/ConsoleCacheService.kt",
            ),
        )

    private fun repositoryRoot(): Path {
        var current = Path.of("").toAbsolutePath().normalize()
        while (true) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
                return current
            }
            current = current.parent ?: error("Repository root not found")
        }
    }
}
