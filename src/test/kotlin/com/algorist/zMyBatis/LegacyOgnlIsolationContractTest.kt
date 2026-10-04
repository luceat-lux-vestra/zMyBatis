package com.algorist.zMyBatis

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Test

class LegacyOgnlIsolationContractTest {

    @Test
    fun `legacy evaluator does not register process-global OGNL accessors`() {
        val evaluator = source(
            "src/main/kotlin/com/algorist/zMyBatis/MyBatisEvaluator.kt",
        )

        assertFalse(
            "shipping legacy evaluator must not mutate MyBatis OGNL accessor state",
            evaluator.contains("OgnlRuntime.setPropertyAccessor"),
        )
        assertFalse(
            "legacy evaluator must not define a custom OGNL PropertyAccessor",
            evaluator.contains("PropertyAccessor"),
        )
        assertFalse(
            "legacy evaluator must not depend on OGNL accessor context plumbing",
            evaluator.contains("OgnlContext"),
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
