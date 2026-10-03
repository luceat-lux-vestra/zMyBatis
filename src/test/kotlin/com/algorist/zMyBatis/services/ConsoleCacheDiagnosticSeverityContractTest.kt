package com.algorist.zMyBatis.services

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleCacheDiagnosticSeverityContractTest {

    @Test
    fun `sentinel registration keeps handled exceptions non fatal and rethrows stronger failures`() {
        val service = source(
            "src/main/kotlin/com/algorist/zMyBatis/services/ConsoleCacheService.kt"
        )
        val registration = service
            .substringAfter("val entry = Entry(console, sentinel)")
            .substringBefore("val accepted = synchronized(lifecycleLock)")

        val cancellationCatch = registration.indexOf("catch (ex: ProcessCanceledException)")
        val ordinaryCatch = registration.indexOf("catch (ex: Exception)")
        val fatalCatch = registration.indexOf("catch (fatal: Throwable)")

        assertTrue("registration must classify cancellation first", cancellationCatch >= 0)
        assertTrue("ordinary Exception must follow cancellation", ordinaryCatch > cancellationCatch)
        assertTrue("fatal Throwable must follow ordinary Exception", fatalCatch > ordinaryCatch)

        val cancellationBranch = registration.substring(cancellationCatch, ordinaryCatch)
        assertTrue(cancellationBranch.contains("disposeSentinelAfterRegistrationFailure(sentinel, ex)"))
        assertTrue(cancellationBranch.contains("throw ex"))

        val ordinaryBranch = registration.substring(ordinaryCatch, fatalCatch)
        assertTrue(
            "ordinary registration failure must remain non-fatal",
            ordinaryBranch.contains("LOG.warn("),
        )
        assertTrue(ordinaryBranch.contains("(type=\${ex.javaClass.name})"))
        assertTrue(ordinaryBranch.contains("disposeSentinelAfterRegistrationFailure(sentinel, ex)"))
        assertTrue(ordinaryBranch.contains("return false"))
        assertFalse("ordinary failure must not use fatal reporting", ordinaryBranch.contains("LOG.error("))
        assertFalse("ordinary diagnostics must not include exception messages", ordinaryBranch.contains("ex.message"))
        assertFalse("ordinary diagnostics must not attach the exception object", ordinaryBranch.contains(", ex)"))

        val fatalBranch = registration.substring(fatalCatch)
        assertTrue(
            fatalBranch.contains(
                "LOG.error(\"zMyBatis: fatal console sentinel registration failure for \$mapperKey\", fatal)"
            )
        )
        assertTrue(fatalBranch.contains("disposeSentinelAfterRegistrationFailure(sentinel, fatal)"))
        assertTrue(fatalBranch.contains("throw fatal"))
    }

    @Test
    fun `sentinel cleanup cannot downgrade cancellation or fatal failure`() {
        val service = source(
            "src/main/kotlin/com/algorist/zMyBatis/services/ConsoleCacheService.kt"
        )
        val cleanup = service
            .substringAfter("private fun disposeSentinelAfterRegistrationFailure(")
            .substringBefore("private fun preservePrimaryOrRethrowCleanup(")
        val preserve = service
            .substringAfter("private fun preservePrimaryOrRethrowCleanup(")
            .substringBefore("/**\n     * Atomically closes resource acquisition")

        val cancellationCatch = cleanup.indexOf("catch (cleanupFailure: ProcessCanceledException)")
        val ordinaryCatch = cleanup.indexOf("catch (cleanupFailure: Exception)")
        val fatalCatch = cleanup.indexOf("catch (cleanupFatal: Throwable)")

        assertTrue("cleanup must classify cancellation first", cancellationCatch >= 0)
        assertTrue("ordinary cleanup Exception must follow cancellation", ordinaryCatch > cancellationCatch)
        assertTrue("fatal cleanup Throwable must follow ordinary Exception", fatalCatch > ordinaryCatch)

        assertTrue(
            cleanup.substring(cancellationCatch, ordinaryCatch)
                .contains("preservePrimaryOrRethrowCleanup(primaryFailure, cleanupFailure)")
        )
        assertTrue(
            cleanup.substring(fatalCatch)
                .contains("preservePrimaryOrRethrowCleanup(primaryFailure, cleanupFatal)")
        )

        val ordinaryCleanup = cleanup.substring(ordinaryCatch, fatalCatch)
        assertTrue(
            ordinaryCleanup.contains(
                "primaryFailure is ProcessCanceledException || primaryFailure !is Exception"
            )
        )
        assertTrue(ordinaryCleanup.contains("primaryFailure.addSuppressed(cleanupFailure)"))
        assertTrue(ordinaryCleanup.contains("(type=\${cleanupFailure.javaClass.name})"))
        assertFalse(ordinaryCleanup.contains("cleanupFailure.message"))

        assertTrue(
            preserve.contains(
                "primaryFailure is ProcessCanceledException || primaryFailure !is Exception"
            )
        )
        assertTrue(preserve.contains("primaryFailure.addSuppressed(cleanupFailure)"))
        assertTrue(preserve.contains("cleanupFailure.addSuppressed(primaryFailure)"))
        assertTrue(preserve.contains("throw cleanupFailure"))
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
