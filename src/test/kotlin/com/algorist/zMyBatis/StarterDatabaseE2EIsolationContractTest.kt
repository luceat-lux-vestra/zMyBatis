package com.algorist.zMyBatis

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StarterDatabaseE2EIsolationContractTest {

    @Test
    fun `PostgreSQL fixture remains integration-test-only and production artifact stays unchanged`() {
        val build = source("build.gradle.kts")

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
        assertFalse(
            "obsolete H2 action fixture must not remain on the process E2E graph",
            build.contains("com.h2database:h2:"),
        )
    }

    @Test
    fun `process golden path must traverse registered Leap authority and explicit PostgreSQL target`() {
        val e2e = source(
            "src/integrationTest/kotlin/com/algorist/zMyBatis/e2e/ZMyBatisStarterDriverE2ETest.kt",
        )

        assertTrue(e2e.contains("invokeAction(\"zMyBatis.Execute\", now = false)"))
        assertTrue(e2e.contains("Enter MyBatis Parameters"))
        assertTrue(e2e.contains("zMyBatis — SQL Preview"))
        assertTrue(e2e.contains("com.intellij.database.run.ui.table.TableResultView"))
        assertTrue(
            "PostgreSQL proof must use a pinned local Database Tools pgJDBC driver and real service URL",
            e2e.contains("LocalDatabaseDriverManager") &&
                e2e.contains("driver-class=\"org.postgresql.Driver\"") &&
                e2e.contains("jdbc:postgresql://127.0.0.1:5432/zmybatis"),
        )
        assertTrue(
            "PostgreSQL proof must create, connect, refresh, and select explicit public through maintained UI actions",
            e2e.contains("ActivateDatabaseToolWindow") &&
                e2e.contains("dataSourcesAndDriversDialog") &&
                e2e.contains("testConnection()") &&
                e2e.contains("DatabaseView.Refresh") &&
                e2e.contains("waitOneText(\"public\")"),
        )
        assertFalse(
            "shipping cutover E2E must not restore the legacy default-schema target",
            e2e.contains("Use Default Schema"),
        )
        assertTrue(
            "real project source must provide mapper-method caller authority for the XML statement",
            e2e.contains("public interface QueryMapper") &&
                e2e.contains("Object ping(@Param(\"value\") long value);") &&
                e2e.contains("Object raw(@Param(\"value\") String value);"),
        )
        assertTrue(
            "one registered action path must prove zero-binding, PostgreSQL BIGINT, raw approval and mutation cancellation",
            e2e.contains("line = 4") &&
                e2e.contains("input = null") &&
                e2e.contains("positiveArtifact.executionSql") &&
                e2e.contains("rawInput = true") &&
                e2e.contains("cancelPostgreSqlMapperCase("),
        )
        assertTrue(
            "native history must contain exact executed artifacts and exclude the cancelled mutation",
            e2e.contains("expectedSql.forEach") &&
                e2e.contains("forbiddenSql in historyText") &&
                e2e.contains("consoles/.history"),
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
