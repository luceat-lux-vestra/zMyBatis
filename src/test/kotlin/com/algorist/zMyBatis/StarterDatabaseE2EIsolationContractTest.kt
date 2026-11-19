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
    fun `IDEA process artifact identity must match the maintained 2026 3 host claim`() {
        val workflow = source(".github/workflows/e2e.yml")

        assertTrue(
            "IDEA E2E identity must record the maintained 2026.3 host release",
            workflow.contains("echo \"ide_release=2026.3\""),
        )
        assertFalse(
            "IDEA E2E identity must not regress to the retired 2026.2.3 release label",
            workflow.contains("echo \"ide_release=2026.2.3\""),
        )
        assertTrue(workflow.contains("name: e2e-artifact-identity"))
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
            "zero-binding native and multi-binding prepared proofs must reuse the maintained PostgreSQL datasource creation path",
            e2e.contains("registeredActionExecutesZeroBindingQueryThroughRealDatabaseTools") &&
                e2e.contains("registeredActionExecutesMultiBindingPlanThroughPreparedResultPresentation") &&
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
            "PostgreSQL proof must create, persist, refresh, and select explicit public through maintained UI actions",
            e2e.contains("ActivateDatabaseToolWindow") &&
                e2e.contains("dataSourcesAndDriversDialog") &&
                e2e.contains("readPostgreSqlDataSourceId(projectDir)") &&
                e2e.contains("DatabaseView.Refresh") &&
                e2e.contains("waitOneText(\"public\")"),
        )
        assertFalse(
            "datasource creation must not treat Database Tools Test Connection as product readiness evidence",
            e2e.contains("testConnection()"),
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
        assertTrue(
            "P5 packaged acceptance must prove mutation declaration forces approval even when ordinary preview is disabled",
            e2e.contains("registeredMutationDeclarationForcesPreviewWhenSettingDisabled") &&
                e2e.contains("mutation declaration must force final-plan approval when sqlPreview=false") &&
                e2e.contains("cancelling mandatory mutation approval must not execute the declared mutation"),
        )
        assertTrue(
            "P5 packaged acceptance must prove target-derived databaseId selects the PostgreSQL variant",
            e2e.contains("registeredActionSelectsTargetDatabaseIdVariant") &&
                e2e.contains("databaseId=\"PostgreSQL\"") &&
                e2e.contains("SELECT 2620 AS RESULT_VALUE") &&
                e2e.contains("SELECT 2600 AS RESULT_VALUE") &&
                e2e.contains("target-derived PostgreSQL databaseId variant must win over the default variant"),
        )
        assertTrue(
            "P5 packaged acceptance must keep Provider annotations explicitly fail-closed at the action edge",
            e2e.contains("registeredProviderAnnotationFailsClosedWithExplicitNotice") &&
                e2e.contains("writeDirectJavaProviderMapper(projectDir)") &&
                e2e.contains("@SelectProvider(type = SqlProvider.class, method = \"sql\")") &&
                e2e.contains("zMyBatis: Provider Not Supported"),
        )
        assertTrue(
            "bound PostgreSQL proof must require multiple JDBC bindings and zMyBatis prepared-result presentation",
            e2e.contains("registeredActionExecutesMultiBindingPlanThroughPreparedResultPresentation") &&
                e2e.contains("preparedSql.count { it == '?' }") &&
                e2e.contains("Regex(\"\"\"#\\d+ property=value\"\"\")") &&
                e2e.contains("zMyBatis: Query Result") &&
                e2e.contains("com.intellij.ui.table.JBTable") &&
                e2e.contains("prepared approval must not literalize the bound value") &&
                e2e.contains("bound registered execution must use zMyBatis prepared-result presentation"),
        )
        assertTrue(
            "P5 packaged parity must prove built-in parameterType metadata reaches the prepared backend",
            e2e.contains("registeredActionExecutesBuiltInParameterTypeThroughPreparedRoute") &&
                e2e.contains("parameterType=\"long\"") &&
                e2e.contains("SELECT ?::BIGINT AS RESULT_VALUE") &&
                e2e.contains("#1 property=value") &&
                e2e.contains("javaType=java.lang.Long") &&
                e2e.contains("handler=org.apache.ibatis.type.LongTypeHandler") &&
                e2e.contains("parameterType parity must not reintroduce literalized bound SQL") &&
                e2e.contains("parameterType-backed bound execution must remain on the prepared-result route"),
        )
        assertFalse(
            "prepared registered-action proof must not reconstruct the retired BIGINT materialized artifact",
            e2e.contains("stockPostgreSqlBigintArtifact") ||
                e2e.contains("PostgreSqlBigintExecutionMaterializer") ||
                e2e.contains("positiveArtifact.executionSql") ||
                e2e.contains("negativeArtifact.executionSql"),
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
