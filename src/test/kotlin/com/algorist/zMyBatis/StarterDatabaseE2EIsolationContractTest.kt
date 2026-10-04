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
        assertTrue(
            "golden path must prepare a real project-level Database Tools datasource",
            e2e.contains("dataSources.xml") &&
                e2e.contains("DataSourceManagerImpl") &&
                e2e.contains("<driver-ref>h2.unified</driver-ref>"),
        )
        assertTrue(
            "project-local Database Tools credentials must be isolated from the shared datasource descriptor",
            e2e.contains("dataSources.local.xml") &&
                e2e.contains("<user-name>sa</user-name>"),
        )
        assertFalse(
            "E2E must not depend on internal Database Tools LocalDataSource Driver stubs",
            e2e.contains("LocalDataSourceManager") || e2e.contains("LocalDataSourceFactoryRemote"),
        )
        assertTrue(
            "H2 must be registered as a local Database Tools driver library in Starter config",
            e2e.contains("databaseDrivers.xml") &&
                e2e.contains("LocalDatabaseDriverManager") &&
                e2e.contains("<artifact use=\"false\" />") &&
                e2e.contains("configDir.resolve(\"jdbc-drivers\")"),
        )
        assertFalse(
            "H2 must not rely on the IDE process classpath instead of Database Tools driver isolation",
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
