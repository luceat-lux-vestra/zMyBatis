package com.algorist.zMyBatis

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StarterDatabaseE2EIsolationContractTest {

    @Test
    fun `H2 fixture remains integration-test-only and production artifact stays unchanged`() {
        val build = source("build.gradle.kts")

        assertTrue(
            "real Database Tools E2E must declare H2 only on the integration-test graph",
            build.contains("integrationTestImplementation(\"com.h2database:h2:2.2.224\")"),
        )
        assertFalse(
            "H2 must never become a shipped plugin implementation dependency",
            build.lineSequence().any { line ->
                line.trim().startsWith("implementation(\"com.h2database:h2:")
            },
        )
    }

    @Test
    fun `process golden path must traverse production UI and real Database Tools result`() {
        val e2e = source(
            "src/integrationTest/kotlin/com/algorist/zMyBatis/e2e/ZMyBatisStarterDriverE2ETest.kt",
        )

        assertTrue(e2e.contains("invokeAction(\"zMyBatis.Execute\", now = false)"))
        assertTrue(e2e.contains("Enter MyBatis Parameters"))
        assertTrue(e2e.contains("zMyBatis — SQL Preview"))
        assertTrue(e2e.contains("com.intellij.database.run.ui.table.TableResultView"))
        assertTrue(e2e.contains("LocalDataSourceManager"))
        assertTrue(
            "Driver Database Tools stubs must resolve through the non-embedded database core module",
            e2e.contains("plugin = \"com.intellij.database/intellij.database.core.impl\""),
        )
        assertTrue(
            "H2 must enter only the Starter IDE process classpath, not the plugin artifact",
            e2e.contains("idea.additional.classpath"),
        )
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
