package com.algorist.zMyBatis

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveLoggingContractTest {

    @Test
    fun `legacy action keeps parameter and rendered sql content out of logger calls`() {
        val source = File(
            "src/main/kotlin/com/algorist/zMyBatis/MyBatisExecuteProxyAction.kt"
        ).readText()
        val loggerCalls = extractLoggerCalls(source).joinToString("\n")

        val forbiddenFragments = listOf(
            "\$paramValues",
            "\${paramValues}",
            "\$rawSql",
            "\${rawSql}",
            "\$values",
            "\${values}",
            "\${values.keys}",
            "\${extracted.params}",
            "\${extracted.objectParams}",
            "mapValues { (_, v) ->",
            "\$sqlContent",
            "\${sqlContent}",
            "\$pureSql",
            "\${pureSql}"
        )

        forbiddenFragments.forEach { fragment ->
            assertFalse(
                "logger call must not contain sensitive source/input/SQL fragment: $fragment",
                loggerCalls.contains(fragment)
            )
        }

        assertTrue(source.contains("parameters resolved (count=\$parameterCount)"))
        assertTrue(source.contains("SQL evaluated (length=\$sqlLength)"))
        assertTrue(
            source.contains(
                "parameter extraction completed \" +\n" +
                    "                \"(count=\$extractedParameterCount, structuredCount=\$structuredParameterCount)"
            )
        )
        assertTrue(source.contains("parameter input completed (count=\$inputParameterCount)"))
    }

    private fun extractLoggerCalls(source: String): List<String> {
        val startPattern = Regex("""LOG\.(?:trace|debug|info|warn|error)\s*\(""")
        return startPattern.findAll(source).map { match ->
            val start = match.range.first
            var index = match.range.last + 1
            var depth = 1
            var inString = false
            var escaped = false

            while (index < source.length && depth > 0) {
                val char = source[index]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        char == '\\' -> escaped = true
                        char == '"' -> inString = false
                    }
                } else {
                    when (char) {
                        '"' -> inString = true
                        '(' -> depth++
                        ')' -> depth--
                    }
                }
                index++
            }

            source.substring(start, index)
        }.toList()
    }
}
