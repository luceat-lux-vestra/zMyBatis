package com.algorist.zMyBatis

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MyBatisActionUpdateBoundaryTest {

    @Test
    fun `update delegates only to local context classifier`() {
        val action = source("src/main/kotlin/com/algorist/zMyBatis/MyBatisExecuteAction.kt")
        val start = action.indexOf("override fun update(e: AnActionEvent)")
        val end = action.indexOf("override fun actionPerformed(e: AnActionEvent)", start)
        assertTrue("update method must exist", start >= 0)
        assertTrue("update method boundary must exist", end > start)

        val update = action.substring(start, end)

        assertTrue(update.contains("MyBatisContextAnalyzer.analyze(e)"))
        assertTrue(update.contains("ContextType.NONE"))
        listOf(
            "extractSqlContent",
            "resolveParameters",
            "DbPsiFacade",
            "DasUtil.getSchemas",
            "ExecutionTargetDescriptorStore",
            "DatabaseToolsConsoleAdapter",
            "ConsoleCacheService",
            "MyBatisEvaluator",
            "LeapActionContextAdmissionCoordinator",
        ).forEach { forbidden ->
            assertFalse("update must remain bounded and side-effect free: $forbidden", update.contains(forbidden))
        }
    }

    @Test
    fun `execution delegates only to Leap admission and has no legacy route`() {
        val action = source("src/main/kotlin/com/algorist/zMyBatis/MyBatisExecuteAction.kt")
        val actionStart = action.indexOf("override fun actionPerformed(e: AnActionEvent)")
        val actionEnd = action.indexOf("private object LeapActionFailurePresenter", actionStart)

        assertTrue(actionStart >= 0 && actionEnd > actionStart)

        val admission = action.substring(actionStart, actionEnd)

        assertTrue(admission.contains("LeapActionContextAdmissionCoordinator(project).execute"))
        assertTrue(admission.contains("onCancelled = {}"))
        listOf(
            "MyBatisExecuteProxyAction",
            "MyBatisEvaluator",
            "ParameterExtractor",
            "StoredExecutionTargetBridge",
            "runMyBatisQuery",
            "DatabaseToolsConsoleAdapter",
        ).forEach { forbidden ->
            assertFalse("shipping action must not route through legacy authority: $forbidden", admission.contains(forbidden))
        }
    }

    @Test
    fun `java update classification fails closed while indexes are unavailable`() {
        val analyzer = source("src/main/kotlin/com/algorist/zMyBatis/MyBatisContextAnalyzer.kt")

        assertTrue(analyzer.contains("DumbService.isDumb(project)"))
        assertTrue(analyzer.contains("catch (_: IndexNotReadyException)"))
        assertTrue(analyzer.contains("project.isDisposed"))
        assertTrue(analyzer.contains("!psiFile.isValid"))
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
