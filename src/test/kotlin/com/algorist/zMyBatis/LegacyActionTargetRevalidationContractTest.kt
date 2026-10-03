package com.algorist.zMyBatis

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyActionTargetRevalidationContractTest {

    @Test
    fun `shipping action resolves target before cache reuse and binds cache lookup to target id`() {
        val action = source(
            "src/main/kotlin/com/algorist/zMyBatis/MyBatisExecuteProxyAction.kt",
        )

        val resolve = action.indexOf("val storedTarget = targetBridge.resolve(sourceFileId)")
        val cacheLookup = action.indexOf("cache.get(mapperKey, storedTarget.targetId)")
        assertTrue(resolve >= 0)
        assertTrue(cacheLookup > resolve)
        assertTrue(
            "persisted exact target reuse must require the same target id",
            action.contains("cache.get(mapperKey, storedTarget.targetId)"),
        )
        assertTrue(
            "non-persisted in-process target reuse must remain a distinct null-identity path",
            action.contains(
                "StoredExecutionTargetResolution.Missing -> " +
                    "cache.get(mapperKey, expectedTargetId = null)",
            ),
        )
        assertTrue(
            "stale persisted target must not use mapper-key cache reuse",
            action.contains("is StoredExecutionTargetResolution.Invalid -> null"),
        )
    }

    @Test
    fun `persisted target is revalidated through invocation and Database Tools boundaries`() {
        val action = source(
            "src/main/kotlin/com/algorist/zMyBatis/MyBatisExecuteProxyAction.kt",
        )

        assertTrue(
            "invocation checks must revalidate persisted exact target",
            action.contains("targetBridge.isCurrent(sourceFileId, expectedTargetId)"),
        )
        assertTrue(
            "adapter pre-execution callback must combine source and exact target validity",
            action.contains(
                "LegacyActionSourceRevisionGuard.isCurrent(project, sourceRevision) &&",
            ),
        )
        assertTrue(
            "persisted target invalidation must have a distinct refusal",
            action.contains("showTargetRevisionRefusal(project)"),
        )
        assertTrue(action.contains("zMyBatis: Target Changed"))
    }

    @Test
    fun `console cache entry carries target identity and rejects cross target reuse`() {
        val cache = source(
            "src/main/kotlin/com/algorist/zMyBatis/services/ConsoleCacheService.kt",
        )
        val adapter = source(
            "src/main/kotlin/com/algorist/zMyBatis/execution/DatabaseToolsConsoleAdapter.kt",
        )

        assertTrue(cache.contains("val targetId: ExecutionTargetId?"))
        assertTrue(cache.contains("expectedTargetId: ExecutionTargetId?"))
        assertTrue(cache.contains("if (entry.targetId != expectedTargetId)"))
        assertTrue(
            "mismatched cached console must be evicted rather than silently reused",
            cache.contains("cache.remove(mapperKey, entry)"),
        )
        assertTrue(
            "adapter must register the console using immutable target identity",
            adapter.contains("val targetId = databaseToolsExecutionTargetId(dataSource, schema)"),
        )
        assertTrue(adapter.contains("targetId = targetId"))
        assertTrue(adapter.contains("cache.get(resourceKey, targetId)"))
    }

    @Test
    fun `stored target pre execution revalidation does not prune persistence`() {
        val bridge = source(
            "src/main/kotlin/com/algorist/zMyBatis/execution/StoredExecutionTargetBridge.kt",
        )
        val revalidation = bridge
            .substringAfter("fun isCurrent(")
            .substringBefore("internal fun rememberIdentity(")

        assertTrue(revalidation.contains("selection.descriptor.targetId != expectedTargetId"))
        assertTrue(
            revalidation.contains(
                "resolveDescriptor(selection.descriptor) is DatabaseToolsTargetResolution.Success",
            ),
        )
        assertFalse(
            "pre-execution revalidation must not delete persisted authority",
            revalidation.contains("removeSelection("),
        )
        assertTrue(
            "successful ordinary resolution must expose the immutable target id used for reuse",
            bridge.contains("targetId = selection.descriptor.targetId"),
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
