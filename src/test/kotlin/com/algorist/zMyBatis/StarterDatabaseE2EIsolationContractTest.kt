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
            "real Database Tools E2E must declare exactly one H2 driver on the integration-test graph",
            build.lineSequence().count { line ->
                val declaration = line.trim()
                declaration.startsWith("integrationTestImplementation(\"com.h2database:h2:") &&
                    declaration.endsWith("\")")
            } == 1,
        )
        assertFalse(
            "H2 must never become a shipped plugin implementation dependency",
            build.lineSequence().any { line ->
                line.trim().startsWith("implementation(\"com.h2database:h2:")
            },
        )
        assertTrue(
            "PostgreSQL proof must declare exactly one pgJDBC driver on the integration-test graph",
            build.lineSequence().count { line ->
                line.trim() == "integrationTestImplementation(\"org.postgresql:postgresql:42.7.14\")"
            } == 1,
        )
        assertFalse(
            "pgJDBC must never become a shipped plugin implementation dependency",
            build.lineSequence().any { line ->
                line.trim().startsWith("implementation(\"org.postgresql:postgresql:")
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
            "project-local H2 fixture must connect without interactive Password Safe credentials",
            e2e.contains("dataSources.local.xml") &&
                e2e.contains(";USER=sa;PASSWORD=") &&
                e2e.contains("<auth-provider>no-auth</auth-provider>"),
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
        assertTrue(
            "PostgreSQL proof must use a local Database Tools pgJDBC driver and a real PostgreSQL URL",
            e2e.contains("<driver-ref>postgresql</driver-ref>") &&
                e2e.contains("org.postgresql.Driver") &&
                e2e.contains("jdbc:postgresql://127.0.0.1:5432/zmybatis"),
        )
        assertTrue(
            "PostgreSQL proof must introspect and select an explicit public schema through maintained UI actions",
            e2e.contains("ActivateDatabaseToolWindow") &&
                e2e.contains("hotKeyWithDefaultModifierKey(KeyEvent.VK_F5)") &&
                e2e.contains("waitOneText(\"public\")"),
        )
        assertTrue(
            "PostgreSQL proof must execute both stock-MyBatis if branches and observe native query history",
            e2e.contains("positiveArtifact.executionSql") &&
                e2e.contains("negativeArtifact.executionSql") &&
                e2e.contains("Console.History.Browse") &&
                e2e.contains("PostgreSqlQuery.xml - zMyBatis History"),
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
