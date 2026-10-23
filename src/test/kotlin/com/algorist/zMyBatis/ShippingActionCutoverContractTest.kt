package com.algorist.zMyBatis

import com.algorist.zMyBatis.execution.LeapActionContextAdmissionFailure
import com.algorist.zMyBatis.execution.LeapActionSourceEvidenceFailure
import com.algorist.zMyBatis.source.JavaAnnotationSourceCaptureFailure
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShippingActionCutoverContractTest {
    @Test
    fun `registered action is a thin single-authority Leap adapter`() {
        val action = source("src/main/kotlin/com/algorist/zMyBatis/MyBatisExecuteProxyAction.kt")

        assertTrue(action.contains("LeapActionContextAdmissionCoordinator(project).execute("))
        assertTrue(action.contains("onFailure = { failure -> presentFailure(project, failure) }"))
        assertFalse(action.contains("LegacyMyBatisExecuteProxyAction"))
        listOf(
            "MyBatisEvaluator",
            "ParameterExtractor",
            "StoredExecutionTargetBridge",
            "DatabaseToolsConsoleAdapter",
            "DbPsiFacade",
            "DasUtil.getSchemas",
            "JdbcConsole",
            "Use Default Schema",
            "runMyBatisQuery",
        ).forEach { forbidden ->
            assertFalse("registered action must not own legacy execution semantics: $forbidden", action.contains(forbidden))
        }
    }

    @Test
    fun `legacy god action is retained only as non-shipping cleanup debt`() {
        val legacy = source(
            "src/main/kotlin/com/algorist/zMyBatis/LegacyMyBatisExecuteProxyAction.kt",
        )
        val plugin = source("src/main/resources/META-INF/plugin.xml")

        assertTrue(legacy.contains("internal open class LegacyMyBatisExecuteProxyAction"))
        assertTrue(legacy.contains("MyBatisEvaluator.evaluateForExecution"))
        assertFalse(legacy.contains("class MyBatisExecuteAction"))
        assertTrue(plugin.contains("class=\"com.algorist.zMyBatis.MyBatisExecuteAction\""))
        assertFalse(plugin.contains("LegacyMyBatisExecuteProxyAction"))
    }

    @Test
    fun `provider annotation remains a typed visible refusal`() {
        val presentation = leapActionFailurePresentation(
            LeapActionContextAdmissionFailure.SourceCaptureFailed(
                LeapActionSourceEvidenceFailure.Java(
                    JavaAnnotationSourceCaptureFailure.PROVIDER_ANNOTATION,
                ),
            ),
        )

        assertEquals(LeapActionFailureSeverity.INFORMATION, presentation.severity)
        assertEquals("zMyBatis: Provider Not Supported", presentation.title)
        assertTrue(presentation.message.contains("runtime-only"))
    }

    private fun source(relativePath: String): String =
        Files.readString(repositoryRoot().resolve(relativePath))

    private fun repositoryRoot(): Path {
        var current = Path.of("").toAbsolutePath().normalize()
        while (true) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) return current
            current = current.parent ?: error("Repository root not found")
        }
    }
}
