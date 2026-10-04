package com.algorist.zMyBatis

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyXmlIncludeExecutionGuardContractTest {

    @Test
    fun `shipping action refuses include before extraction target parameter or execution work`() {
        val action = source("src/main/kotlin/com/algorist/zMyBatis/MyBatisExecuteProxyAction.kt")
        val run = action
            .substringAfter("private fun runMyBatisQuery(")
            .substringBefore("private fun proceedWithParamsAndExecute(")

        val rootCapture = run.indexOf("LegacyActionSourceRevisionGuard.capture(project, editor, psiFile)")
        val statementLookup = run.indexOf("findCurrentXmlStatementTag(editor, psiFile)")
        val includeGuard = run.indexOf(
            "LegacyXmlIncludeExecutionGuard.containsIncludeDependency(statementXmlTag)",
        )
        val refusal = run.indexOf("showXmlIncludeDependencyRefusal(project)")
        val guardedExtraction = run.indexOf("statementXmlTag != null -> statementXmlTag.text")
        val targetResolution = run.indexOf("StoredExecutionTargetBridge.forProject(project)")
        val executionHandoff = run.indexOf("proceedWithParamsAndExecute(")

        assertTrue(rootCapture >= 0)
        assertTrue(statementLookup > rootCapture)
        assertTrue(includeGuard > statementLookup)
        assertTrue(refusal > includeGuard)
        assertTrue(
            "the exact guarded statement PSI must supply the extracted XML text",
            guardedExtraction > refusal,
        )
        assertTrue(
            "target resolution must not happen before XML dependency refusal",
            targetResolution > guardedExtraction,
        )
        assertTrue(
            "execution orchestration must not start before XML dependency refusal",
            executionHandoff > targetResolution,
        )
    }

    @Test
    fun `shipping refusal explains authoritative dependency resolution is unavailable`() {
        val action = source("src/main/kotlin/com/algorist/zMyBatis/MyBatisExecuteProxyAction.kt")

        assertTrue(action.contains("zMyBatis: Mapper Dependency Not Executable"))
        assertTrue(action.contains("cannot resolve those dependencies"))
        assertTrue(action.contains("execution was refused instead of producing partial SQL"))
    }

    @Test
    fun `guard uses XmlTag descendants rather than raw include text matching`() {
        val guard = source("src/main/kotlin/com/algorist/zMyBatis/LegacyXmlIncludeExecutionGuard.kt")

        assertTrue(guard.contains("PsiTreeUtil.findChildrenOfType(statementTag, XmlTag::class.java)"))
        assertTrue(guard.contains(".any { it.localName == \"include\" }"))
        assertTrue(!guard.contains("Regex("))
        assertTrue(!guard.contains("contains(\"<include"))
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
