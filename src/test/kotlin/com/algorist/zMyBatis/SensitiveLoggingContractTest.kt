package com.algorist.zMyBatis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SensitiveLoggingContractTest {

    @Test
    fun `normal logs do not interpolate sensitive query data`() {
        val source = File(
            "src/main/kotlin/com/algorist/zMyBatis/MyBatisExecuteProxyAction.kt"
        ).readText()

        val forbidden = listOf(
            "zMyBatis params: \$paramValues",
            "zMyBatis SQL: \$rawSql",
            "values: \$values",
            "\${values.keys}",
            "\${values.mapValues",
            "\${extracted.params}",
            "\${extracted.objectParams}"
        )

        forbidden.forEach { token ->
            assertFalse("production logging must not contain sensitive interpolation: $token", source.contains(token))
        }

        val metadataOnlyEvidence = listOf(
            "parameters resolved (count=\${paramValues.size})",
            "SQL evaluated (length=\${rawSql.length})",
            "parameter values collected (count=\${values.size})"
        )

        metadataOnlyEvidence.forEach { token ->
            assertTrue("expected metadata-only logging evidence is missing: $token", source.contains(token))
        }
    }
}
