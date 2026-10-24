package com.algorist.zMyBatis

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StarterDatabaseE2EIsolationContractTest {

    @Test
    fun `PostgreSQL process fixture remains integration-test-only and production artifact stays unchanged`() {
        val build = source("build.gradle.kts")

        assertFalse(
            "retired H2 process harness must not remain on the integration-test graph",
            build.lineSequence().any { line ->
                line.trim().startsWith("integrationTestImplementation(\"com.h2database:h2:")
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
    fun `process golden path must reuse one maintained PostgreSQL Database Tools stack`() {
        val e2e = source(
            "src/integrationTest/kotlin/com/algorist/zMyBatis/e2e/ZMyBatisStarterDriverE2ETest.kt",
        )

        assertTrue(e2e.contains("invokeAction(\"zMyBatis.Execute\", now = false)"))
        assertTrue(e2e.contains("zMyBatis — SQL Preview"))
        assertTrue(e2e.contains("com.intellij.database.run.ui.table.TableResultView"))
        assertFalse(
            "retired H2 datasource/driver/introspection harness must stay out of #448 process evidence",
            e2e.contains("H2Driver") ||
                e2e.contains("h2.unified") ||
                e2e.contains("jdbc:h2:") ||
                e2e.contains("connectAndPrimeH2DatabaseToolsSchemaMetadata"),
        )
        assertTrue(
            "zero-binding and BIGINT proofs must reuse the maintained PostgreSQL datasource creation path",
            e2e.contains("registeredActionExecutesZeroBindingQueryThroughRealDatabaseTools") &&
                e2e.contains("createPostgreSqlDataSourceThroughDatabaseTools(projectDir)") &&
                e2e.contains("POSTGRES_E2E_DATA_SOURCE_NAME") &&
                e2e.contains("waitOneText(\"public\")"),
        )
        assertTrue(
            "PostgreSQL proof must use a pinned local pgJDBC driver and a real PostgreSQL URL",
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
        assertTrue(
            "XML raw confirmation must retain real Java mapper caller authority while reusing the PostgreSQL process target",
            e2e.contains("registeredRawInterpolationForcesPreviewWhenSettingDisabled") &&
                e2e.contains("writeMapperInterface(projectDir, \"java.lang.String\")") &&
                e2e.contains("action-raw-confirmation-postgresql") &&
                e2e.contains("createPostgreSqlDataSourceThroughDatabaseTools(projectDir)"),
        )
        assertTrue(
            "direct Java annotation raw proof must exercise the registered action, non-retention, exact approval, native result, and native history",
            e2e.contains("registeredJavaAnnotationRawInterpolationExecutesExactApprovedSql") &&
                e2e.contains("writeDirectJavaRawMapper(projectDir)") &&
                e2e.contains("public interface JavaRawQueryMapper") &&
                e2e.contains("@Select(value =") &&
                e2e.contains("@Param(value = \"value\")") &&
                e2e.contains("raw Java input must never be retained across registered-action invocations") &&
                e2e.contains("assertNativePostgreSqlHistoryContainsAndExcludes"),
        )
        assertFalse(
            "mutation confirmation belongs to lower-level Leap approval evidence rather than a duplicate process datasource scenario",
            e2e.contains("registeredMutationDeclarationForcesPreviewWhenSettingDisabled"),
        )
        assertTrue(
            "PostgreSQL BIGINT proof must execute both stock-MyBatis if branches and verify persisted native query history",
            e2e.contains("positiveArtifact.executionSql") &&
                e2e.contains("negativeArtifact.executionSql") &&
                e2e.contains("assertNativePostgreSqlHistoryPersisted") &&
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
