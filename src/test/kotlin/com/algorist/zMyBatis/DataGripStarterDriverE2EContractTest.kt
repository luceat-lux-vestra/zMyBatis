package com.algorist.zMyBatis

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataGripStarterDriverE2EContractTest {

    @Test
    fun `DataGrip host evidence remains bounded while owning one native bound XML route`() {
        val e2e = source(
            "src/integrationTest/kotlin/com/algorist/zMyBatis/e2e/" +
                "ZMyBatisDataGripStarterDriverE2ETest.kt",
        )

        assertTrue(e2e.contains("registeredXmlBoundExecutionUsesNativeDatabaseToolsResultOnDataGrip"))
        assertTrue(e2e.contains("<bind name=\"value\" value=\"41L\"/>"))
        assertTrue(e2e.contains("SELECT #{value,jdbcType=BIGINT}::BIGINT AS RESULT_VALUE"))
        assertTrue(e2e.contains("SELECT 41::BIGINT AS RESULT_VALUE"))
        assertTrue(e2e.contains("com.intellij.database.run.ui.table.TableResultView"))
        assertTrue(e2e.contains("scenario=xml-bound-native-postgresql result=PASS action_registered=true "))
        assertTrue(e2e.contains("native_result=true clean_shutdown=true"))
        assertTrue(
            "bound XML acceptance must explicitly prove the retired zMyBatis result modal stays absent",
            e2e.contains("ui.isDialogOpened(\"//div[@title='zMyBatis: Query Result']\")") &&
                e2e.contains(
                    "DataGrip bound XML execution must not open the retired zMyBatis result modal",
                ),
        )
        assertFalse(
            "static XML bind literal must not open the caller-parameter dialog",
            e2e.contains("ui.dialog(title = \"Enter MyBatis Parameters\")"),
        )
        assertFalse(
            "static XML bind literal must not submit a synthetic caller object",
            e2e.contains("dataGripTextArea().text = \"\"\"{\"value\":999}\"\"\""),
        )
        assertTrue(e2e.contains("DataGrip 2026.3 exposes the activated host surface as \"Database Explorer\""))
        assertTrue(e2e.contains("//div[@visible_text='PostgreSQL']"))
        assertTrue(e2e.contains("invokeAction(\"DatabaseView.Refresh\", now = false)"))
        assertFalse(
            "DataGrip host proof must not reuse the IU-only Database Tool Window locator",
            e2e.contains("databaseToolWindow"),
        )
        assertFalse(
            "bounded DataGrip proof must not claim direct-Java source parity",
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
        val pinnedImages = Regex("""(?m)^\s+postgres@sha256:[0-9a-f]{64}$""")
            .findAll(workflow).map { it.value.trim() }.toList()
        assertTrue(
            "both real PostgreSQL jobs must use the same immutable image digest",
            pinnedImages.size == 2 && pinnedImages.distinct().size == 1,
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
