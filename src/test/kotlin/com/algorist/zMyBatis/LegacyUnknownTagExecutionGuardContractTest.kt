package com.algorist.zMyBatis

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyUnknownTagExecutionGuardContractTest {

    @Test
    fun `shipping disables unknown-tag compatibility while direct evaluator owns the setting`() {
        val evaluator = source(
            "src/main/kotlin/com/algorist/zMyBatis/MyBatisEvaluator.kt",
        )

        val executionBody = evaluator
            .substringAfter("internal fun evaluateForExecution(")
            .substringBefore("@Suppress(\"TooGenericExceptionCaught\")\n    fun evaluate(")
        val directBody = evaluator
            .substringAfter("fun evaluate(xmlContent: String, params: Map<String, Any?>): String")
            .substringBefore("@Suppress(\"NestedBlockDepth\")")
        val internalBody = evaluator
            .substringAfter("private fun evaluateInternal(")
            .substringBefore("private fun legacyErrorSql")

        assertTrue(
            "shipping execution must explicitly disable unknown-tag compatibility",
            executionBody.contains("allowUnknownTagCompatibility = false"),
        )
        assertTrue(
            "direct compatibility must read the persisted unknown-tag switch",
            directBody.contains("val ignoreUnknownTags = settings?.ignoreUnknownTags ?: false"),
        )
        assertTrue(
            "direct compatibility must pass the setting explicitly",
            directBody.contains("allowUnknownTagCompatibility = ignoreUnknownTags"),
        )
        assertFalse(
            "shared internal evaluator must not read application settings implicitly",
            internalBody.contains("ZMyBatisSettings"),
        )
        assertFalse(
            "shared internal evaluator must not resolve ApplicationManager implicitly",
            internalBody.contains("ApplicationManager"),
        )
    }

    @Test
    fun `unknown-tag stripping is gated only by explicit compatibility policy`() {
        val evaluator = source(
            "src/main/kotlin/com/algorist/zMyBatis/MyBatisEvaluator.kt",
        )
        val internalBody = evaluator
            .substringAfter("private fun evaluateInternal(")
            .substringBefore("private fun legacyErrorSql")

        val policyGuard = internalBody.indexOf("if (allowUnknownTagCompatibility)")
        val stripping = internalBody.indexOf("cleanedXml = stripUnknownTags(cleanedXml)")

        assertTrue("explicit unknown-tag compatibility guard must exist", policyGuard >= 0)
        assertTrue(
            "unknown-tag stripping must only occur after the explicit compatibility guard",
            stripping > policyGuard,
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
