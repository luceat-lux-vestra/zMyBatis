package com.algorist.zMyBatis

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MyBatisActionUpdateBoundaryTest {

    @Test
    fun `update delegates only to local context classifier`() {
        val action = source("src/main/kotlin/com/algorist/zMyBatis/MyBatisExecuteProxyAction.kt")
        val start = action.indexOf("override fun update(e: AnActionEvent)")
        val end = action.indexOf("private fun isProjectUnavailable", start)
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
            "StoredExecutionTargetBridge",
            "DatabaseToolsConsoleAdapter",
            "ConsoleCacheService",
            "MyBatisEvaluator",
        ).forEach { forbidden ->
            assertFalse("update must remain bounded and side-effect free: $forbidden", update.contains(forbidden))
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
