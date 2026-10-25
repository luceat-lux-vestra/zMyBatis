package com.algorist.zMyBatis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class SensitiveLoggingContractTest {

    companion object {
        private val LOG_CALL = Regex("""\bLOG\.(?:trace|debug|info|warn|error)\s*\(""")
        private const val SENSITIVE_ALIAS =
            "rawSql|pureSql|sqlContent|sql|script|paramValues|values|password|credential|credentials|" +
                "secret|token|sourceFileId|resourceKey|mapperKey|dataSourceName|schemaName|consoleTitle|consoleName"
        private val DIRECT_SENSITIVE_INTERPOLATION = Regex(
            """\$(?:$SENSITIVE_ALIAS)\b"""
        )
        private val BRACED_SENSITIVE_INTERPOLATION = Regex(
            """\$\{(?:$SENSITIVE_ALIAS)(?!\.(?:length|size)\b)[^}]*}"""
        )
        private val EXTRACTED_SENSITIVE_INTERPOLATION = Regex(
            """\$\{extracted\.(?:params|objectParams)(?!\.size\b)[^}]*}"""
        )
        private val TARGET_OBJECT_PROPERTY_INTERPOLATION = Regex(
            """\$\{(?:dataSource\.name|schema\.name|console\.title)(?:[^}]*)}"""
        )
        private val EXCEPTION_MESSAGE_DETAIL = Regex(
            """\b(?:ex|exception|error|failure|fatal|restoreEx|cleanupFailure|cleanupFatal)\.message\b"""
        )
        private val PERSISTENCE_ID_INTERPOLATION = Regex(
            """target-selection[^"\n]*""" + Regex.escape("$") + """(?:id|\{id})"""
        )
        private val FORBIDDEN = listOf(
            DIRECT_SENSITIVE_INTERPOLATION,
            BRACED_SENSITIVE_INTERPOLATION,
            EXTRACTED_SENSITIVE_INTERPOLATION,
            TARGET_OBJECT_PROPERTY_INTERPOLATION,
            EXCEPTION_MESSAGE_DETAIL,
            PERSISTENCE_ID_INTERPOLATION,
        )
    }

    @Test
    fun `production logs do not directly interpolate sensitive query or identity data`() {
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
            "production logging must not directly interpolate sensitive query/input/source/target data:\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `logging detector rejects sensitive values but permits metadata`() {
        val dollar = '$'
        val unsafe = listOf(
            "LOG.info(\"sql=$dollar" + "rawSql\")",
            "LOG.warn(\"values=$dollar{values.keys}\")",
            "LOG.debug(\"input=$dollar" + "paramValues\")",
            "LOG.error(\"secret=$dollar{credential.value}\")",
            "LOG.info(\"source=$dollar" + "resourceKey\")",
            "LOG.warn(\"datasource=$dollar{dataSource.name}\")",
            "LOG.warn(\"schema=$dollar{schema.name}\")",
            "LOG.info(\"console=$dollar{console.title}\")",
            "LOG.warn(\"target-selection index id '$dollar" + "id'\")",
            "LOG.warn(\"failure=$dollar{ex.message}\")",
        )
        unsafe.forEach { sample ->
            assertFalse("unsafe sample must be rejected: $sample", findSensitiveInterpolations(sample).isEmpty())
        }

        val safeMetadata = listOf(
            "LOG.info(\"sqlLength=$dollar{sql.length}\")",
            "LOG.info(\"parameterCount=$dollar{values.size}\")",
            "LOG.info(\"discovered=$dollar{extracted.params.size}\")",
            "LOG.warn(\"failureType=$dollar{ex.javaClass.name}\")",
            "LOG.info(\"stage=target-resolution-failed\")",
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
