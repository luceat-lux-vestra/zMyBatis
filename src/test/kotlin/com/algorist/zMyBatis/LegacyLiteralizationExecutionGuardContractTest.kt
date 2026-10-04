package com.algorist.zMyBatis

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyLiteralizationExecutionGuardContractTest {

    @Test
    fun `shipping and direct evaluator select opposite unsupported literalization policies`() {
        val evaluator = source(
            "src/main/kotlin/com/algorist/zMyBatis/MyBatisEvaluator.kt",
        )

        val executionBody = evaluator
            .substringAfter("internal fun evaluateForExecution(")
            .substringBefore("@Suppress(\"TooGenericExceptionCaught\")\n    fun evaluate(")
        val directBody = evaluator
            .substringAfter("fun evaluate(xmlContent: String, params: Map<String, Any?>): String")
            .substringBefore("@Suppress(\"NestedBlockDepth\")")

        assertTrue(
            "shipping execution must explicitly enable fail-closed unsupported literalization",
            executionBody.contains("failClosedUnsupportedLiteralization = true"),
        )
        assertTrue(
            "direct legacy evaluator must preserve compatibility literalization",
            directBody.contains("failClosedUnsupportedLiteralization = false"),
        )
    }

    @Test
    fun `unsupported shipping literals throw before legacy marker or toString fallback`() {
        val evaluator = source(
            "src/main/kotlin/com/algorist/zMyBatis/MyBatisEvaluator.kt",
        )
        val literalizer = evaluator
            .substringAfter("private fun convertToLiteral(")
            .substringBefore("\n    }\n}")

        val listBranch = literalizer
            .substringAfter("is List<*> -> {")
            .substringBefore("is Map<*, *> -> {")
        val mapBranch = literalizer
            .substringAfter("is Map<*, *> -> {")
            .substringBefore("else -> {")
        val objectBranch = literalizer.substringAfter("else -> {")

        assertFailClosedBeforeCompatibility(
            listBranch,
            "LegacyUnsupportedLiteralKind.LIST",
            "/*[ERROR: List",
        )
        assertFailClosedBeforeCompatibility(
            mapBranch,
            "LegacyUnsupportedLiteralKind.MAP",
            "/*[ERROR: Object",
        )
        assertFailClosedBeforeCompatibility(
            objectBranch,
            "LegacyUnsupportedLiteralKind.OBJECT",
            "value.toString()",
        )

        assertFalse(
            "unsupported literal diagnostic must not interpolate the parameter value",
            evaluator
                .substringAfter("internal class LegacyUnsupportedExecutionLiteralException(")
                .substringBefore("object MyBatisEvaluator")
                .contains("\$value"),
        )
    }

    private fun assertFailClosedBeforeCompatibility(
        branch: String,
        kind: String,
        fallback: String,
    ) {
        val guard = branch.indexOf("if (failClosedUnsupportedLiteralization)")
        val failure = branch.indexOf("LegacyUnsupportedExecutionLiteralException($kind)")
        val compatibility = branch.indexOf(fallback)

        assertTrue("fail-closed policy guard must exist for $kind", guard >= 0)
        assertTrue("typed unsupported failure must follow the guard for $kind", failure > guard)
        assertTrue("legacy compatibility fallback must follow the failure branch for $kind", compatibility > failure)
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
