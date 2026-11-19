package com.algorist.zMyBatis

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataGripStarterDriverE2EContractTest {

    @Test
    fun `DataGrip host evidence remains bounded while owning one prepared XML route`() {
        val e2e = source(
            "src/integrationTest/kotlin/com/algorist/zMyBatis/e2e/" +
                "ZMyBatisDataGripStarterDriverE2ETest.kt",
        )

        assertTrue(e2e.contains("registeredXmlPreparedExecutionUsesDatabaseToolsSessionOnDataGrip"))
        assertTrue(e2e.contains("<bind name=\"value\" value=\"41L\"/>"))
        assertTrue(e2e.contains("SELECT #{value,jdbcType=BIGINT}::BIGINT AS RESULT_VALUE"))
        assertTrue(e2e.contains("SELECT ?::BIGINT AS RESULT_VALUE"))
        assertTrue(e2e.contains("#1 property=value"))
        assertTrue(e2e.contains("jdbcType=BIGINT"))
        assertTrue(e2e.contains("zMyBatis: Query Result"))
        assertTrue(e2e.contains("com.intellij.ui.table.JBTable"))
        assertTrue(e2e.contains("DataGrip prepared acceptance must not literalize the bound value"))
        assertTrue(
            e2e.contains(
                "DataGrip bound XML execution must remain on zMyBatis prepared-result presentation",
            ),
        )
        assertTrue(
            e2e.contains(
                "scenario=xml-prepared-postgresql result=PASS action_registered=true ",
            ),
        )

        assertFalse(
            "bounded DataGrip prepared proof must not claim direct-Java source parity",
            e2e.contains("JavaRawQueryMapper") ||
                e2e.contains("JavaProviderMapper") ||
                e2e.contains("writeMapperInterface("),
        )
    }

    @Test
    fun `DataGrip prepared process job owns its exact PostgreSQL prerequisite`() {
        val workflow = source(".github/workflows/e2e.yml")

        assertTrue(workflow.contains("Start pinned PostgreSQL E2E service for DataGrip"))
        assertTrue(workflow.contains("--name zmybatis-postgres-datagrip-e2e"))
        assertTrue(
            workflow.contains(
                "postgres@sha256:0b2882c46a2b8d8e148431b333394348cf4d7f15aec6df7f17d51dc641e56cfa",
            ),
        )
        assertTrue(
            workflow.contains(
                "docker exec zmybatis-postgres-datagrip-e2e \\",
            ),
        )
        assertTrue(workflow.contains("dataGripIntegrationTest --stacktrace"))
        assertTrue(workflow.contains("echo \"host=DATAGRIP\""))
        assertTrue(workflow.contains("echo \"ide_release=2026.3\""))
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
