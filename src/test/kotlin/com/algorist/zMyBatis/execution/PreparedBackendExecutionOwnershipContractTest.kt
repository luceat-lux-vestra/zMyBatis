package com.algorist.zMyBatis.execution

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedBackendExecutionOwnershipContractTest {

    @Test
    fun `backend publishes cancellation handle before Database Tools request dispatch`() {
        val backend = source(
            "src/main/kotlin/com/algorist/zMyBatis/execution/DatabaseSessionPreparedBackend.kt",
        )
        val execute = backend
            .substringAfter("fun execute(")
            .substringBefore("internal fun executeOnConnection(")

        val control = execute.indexOf("val control = PreparedBackendCancellationControl()")
        val published = execute.indexOf("onHandleReady(control)")
        val requestDispatch = execute.indexOf("console.messageBus.dataProducer.processRequest(request)")

        assertTrue(control >= 0)
        assertTrue(published > control)
        assertTrue(
            "prepared cancellation handle must be published before Database Tools request dispatch",
            requestDispatch > published,
        )
    }

    @Test
    fun `project task owns prepared handle and forwards cancellation exactly through handle`() {
        val adapter = source(
            "src/main/kotlin/com/algorist/zMyBatis/execution/PreparedBackendExecutionTaskAdapter.kt",
        )

        assertTrue(
            adapter.contains(
                "Task.Backgroundable(project, \"Executing zMyBatis prepared statement\", true)",
            ),
        )
        assertTrue(adapter.contains("onHandleReady: (PreparedBackendExecutionHandle) -> Unit"))
        assertTrue(adapter.contains("indicator.checkCanceled()"))
        assertTrue(adapter.contains("executionHandle.cancel()"))
        assertTrue(adapter.contains("cancellationDispatched.compareAndSet(false, true)"))
    }

    @Test
    fun `shipping route no longer dispatches through prepared modal backend`() {
        val delivery = source(
            "src/main/kotlin/com/algorist/zMyBatis/execution/LeapExecutionDeliveryCoordinator.kt",
        )

        assertTrue(delivery.contains("NativeExecutionPlanMaterializer.materialize(plan)"))
        assertTrue(delivery.contains("nativeBackend.execute("))
        assertFalse(delivery.contains("preparedExecutionTask.execute("))
        assertFalse(delivery.contains("preparedBackend.execute("))
        assertFalse(delivery.contains("preparedResultAdapter.present("))
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
