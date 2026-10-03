package com.algorist.zMyBatis.execution

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseToolsDiagnosticSeverityContractTest {

    @Test
    fun `handled Database Tools exceptions are non fatal while fatal throwables are rethrown`() {
        val adapter = source(
            "src/main/kotlin/com/algorist/zMyBatis/execution/DatabaseToolsConsoleAdapter.kt"
        )

        assertTrue(adapter.contains("catch (ex: ProcessCanceledException)"))
        assertTrue(adapter.contains("catch (ex: Exception)"))
        assertTrue(adapter.contains("catch (fatal: Throwable)"))

        assertTrue(adapter.contains("zMyBatis: console creation failed"))
        assertTrue(adapter.contains("zMyBatis: schema switch failed"))
        assertTrue(adapter.contains("zMyBatis: SQL execution failed"))

        assertFalse(adapter.contains("LOG.error(\"zMyBatis: failed to create console"))
        assertFalse(adapter.contains("LOG.error(\"zMyBatis: execution failed\""))

        val fatalErrorCalls = Regex("""LOG\.error\("zMyBatis: fatal [^"]+", fatal\)""")
            .findAll(adapter)
            .count()
        assertEquals(3, fatalErrorCalls)

        val fatalRethrows = Regex("""catch \(fatal: Throwable\) \{[\s\S]*?throw fatal""")
            .findAll(adapter)
            .count()
        assertEquals(3, fatalRethrows)
    }

    @Test
    fun `cleanup does not swallow cancellation or JVM errors`() {
        val adapter = source(
            "src/main/kotlin/com/algorist/zMyBatis/execution/DatabaseToolsConsoleAdapter.kt"
        )
        val cleanup = adapter.substringAfter("private fun restoreConsoleDocumentAfterFailure")

        assertTrue(cleanup.contains("catch (restoreEx: ProcessCanceledException)"))
        assertTrue(cleanup.contains("throw restoreEx"))
        assertTrue(cleanup.contains("catch (restoreEx: Exception)"))
        assertFalse(cleanup.contains("catch (restoreEx: Throwable)"))
    }

    private fun source(relativePath: String): String =
        Files.readString(repositoryRoot().resolve(relativePath))

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
