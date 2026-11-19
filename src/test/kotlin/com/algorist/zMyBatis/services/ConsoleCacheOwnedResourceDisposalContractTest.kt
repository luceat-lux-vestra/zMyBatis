package com.algorist.zMyBatis.services

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
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
    fun `shipping acquisition reuses first and owns only consoles it creates`() {
        val adapter = Files.readString(
            repositoryRoot().resolve(
                "src/main/kotlin/com/algorist/zMyBatis/execution/DatabaseToolsConsoleAdapter.kt",
            ),
        )
        val acquisition = adapter
            .substringAfter("fun acquireAndDeliverConsole(")
            .substringBefore("private fun switchSchemaOnConsole")

        val lease = acquisition.indexOf("cache.beginReuseLease(resourceKey)")
        val sessions = acquisition.indexOf("DatabaseSessionManager.getSessions(project, sessionDataSource)")
        val remembered = acquisition.indexOf("cache.rememberedConsole(resourceKey, it, consoles)")
        val mapperMatch = acquisition.indexOf("console.file.virtualFile?.name == mapperName")
        val chooser = acquisition.indexOf("requestConsoleChoice(")
        val create = acquisition.indexOf("createAndDeliver(consoles)")

        assertTrue("same-source ownership must precede candidate discovery", lease >= 0)
        assertTrue("Database Tools sessions must be discovered after keyed ownership", sessions > lease)
        assertTrue("remembered source+target association must precede filename heuristics", remembered > sessions)
        assertTrue("dynamic mapper-name matching must remain only a reuse heuristic", mapperMatch > remembered)
        assertTrue("ambiguous candidates must route to an explicit chooser", chooser > mapperMatch)
        assertTrue("no-candidate flow must create a console instead of failing", create > mapperMatch)

        assertTrue(adapter.contains("JdbcConsole.newConsole(project)"))
        assertTrue(adapter.contains("cache.putEphemeral(ownershipKey, console, targetId)"))
        assertTrue(adapter.contains("cache.rememberConsole(resourceKey, targetId, console)"))
        assertTrue(adapter.contains("\"Create New Console\""))
        assertTrue(adapter.contains("data object ReuseBusy : DatabaseToolsConsoleAcquisitionFailure"))
        assertTrue(adapter.contains("data object ConsoleCreationFailed : DatabaseToolsConsoleAcquisitionFailure"))
        assertTrue(adapter.contains("data object ConsoleSelectionUnavailable : DatabaseToolsConsoleAcquisitionFailure"))

        assertFalse("existing-console reuse must not open a console tab", adapter.contains("OpenFileDescriptor"))
        assertFalse("existing-console reuse must not request editor focus", adapter.contains("requestFocusInWindow"))
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
