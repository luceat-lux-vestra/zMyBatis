package com.algorist.zMyBatis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class SensitiveLoggingContractTest {

    companion object {
        private val LOG_CALL = Regex("""\bLOG\.(?:trace|debug|info|warn|error)\s*\(""")
        private val DIRECT_SENSITIVE_INTERPOLATION = Regex(
            """\$(?:rawSql|pureSql|sqlContent|sql|script|paramValues|values|password|credential|credentials|secret|token)\b"""
        )
        private val BRACED_SENSITIVE_INTERPOLATION = Regex(
            """\$\{(?:rawSql|pureSql|sqlContent|sql|script|paramValues|values|password|credential|credentials|secret|token)(?!\.(?:length|size)\b)[^}]*}"""
        )
        private val EXTRACTED_SENSITIVE_INTERPOLATION = Regex(
            """\$\{extracted\.(?:params|objectParams)(?!\.size\b)[^}]*}"""
        )
        private val FORBIDDEN = listOf(
            DIRECT_SENSITIVE_INTERPOLATION,
            BRACED_SENSITIVE_INTERPOLATION,
            EXTRACTED_SENSITIVE_INTERPOLATION,
        )
    }

    @Test
    fun `production logs do not directly interpolate known sensitive query data`() {
        val productionRoot = Path.of("src", "main", "kotlin")
        val productionFiles = Files.walk(productionRoot).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .sorted()
                .toList()
        }
        assertFalse("production Kotlin source set must not be empty", productionFiles.isEmpty())

        val violations = productionFiles.flatMap { sourceFile ->
            loggingWindows(Files.readString(sourceFile)).flatMap { window ->
                findSensitiveInterpolations(window).map { match ->
                    "${sourceFile.toString().replace('\\', '/')}: $match"
                }
            }
        }

        assertTrue(
            "production logging must not directly interpolate known sensitive query/input data:\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `metadata only logging evidence remains in the shipping action`() {
        val source = Files.readString(
            Path.of("src", "main", "kotlin", "com", "algorist", "zMyBatis", "MyBatisExecuteProxyAction.kt")
        )
        val dollar = '$'
        val metadataOnlyEvidence = listOf(
            "parameters resolved (count=$dollar{paramValues.size})",
            "SQL evaluated (length=$dollar{rawSql.length})",
            "parameter values collected (count=$dollar{values.size})",
        )

        metadataOnlyEvidence.forEach { token ->
            assertTrue("expected metadata-only logging evidence is missing: $token", source.contains(token))
        }
    }

    @Test
    fun `logging detector rejects values but permits count and length metadata`() {
        val dollar = '$'
        val unsafe = listOf(
            "LOG.info(\"sql=$dollar" + "rawSql\")",
            "LOG.warn(\"values=$dollar{values.keys}\")",
            "LOG.debug(\"input=$dollar" + "paramValues\")",
            "LOG.error(\"secret=$dollar{credential.value}\")",
        )
        unsafe.forEach { sample ->
            assertFalse("unsafe sample must be rejected: $sample", findSensitiveInterpolations(sample).isEmpty())
        }

        val safeMetadata = listOf(
            "LOG.info(\"sqlLength=$dollar{sql.length}\")",
            "LOG.info(\"parameterCount=$dollar{values.size}\")",
            "LOG.info(\"discovered=$dollar{extracted.params.size}\")",
        )
        safeMetadata.forEach { sample ->
            assertTrue("metadata-only sample must remain allowed: $sample", findSensitiveInterpolations(sample).isEmpty())
        }
    }

    private fun loggingWindows(source: String): List<String> {
        val lines = source.lines()
        return lines.indices.mapNotNull { index ->
            if (!LOG_CALL.containsMatchIn(lines[index])) {
                null
            } else {
                lines.subList(index, minOf(index + 8, lines.size)).joinToString("\n")
            }
        }
    }

    private fun findSensitiveInterpolations(loggingWindow: String): List<String> =
        FORBIDDEN.flatMap { pattern -> pattern.findAll(loggingWindow).map { it.value }.toList() }
}
