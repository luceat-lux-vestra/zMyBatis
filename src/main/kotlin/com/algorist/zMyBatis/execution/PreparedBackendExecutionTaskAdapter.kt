package com.algorist.zMyBatis.execution

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val PREPARED_CANCELLATION_FAILED = "prepared-backend-cancellation-failed"

/**
 * Cancellable project task that owns the live prepared-backend execution handle.
 *
 * The backend itself remains Database Tools-owned. This adapter only bridges IntelliJ invocation
 * cancellation/project disposal to the explicit [PreparedBackendExecutionHandle] returned by the
 * backend and delivers one terminal outcome back to orchestration.
 */
internal class PreparedBackendExecutionTaskAdapter(
    private val project: Project,
) {
    fun execute(
        executeBackend: ((DatabaseSessionPreparedOutcome) -> Unit) -> PreparedBackendExecutionHandle,
        onCompleted: (DatabaseSessionPreparedOutcome) -> Unit,
    ) {
        object : Task.Backgroundable(project, "Executing zMyBatis prepared statement", true) {
            private val outcome = AtomicReference<DatabaseSessionPreparedOutcome?>()
            private val handle = AtomicReference<PreparedBackendExecutionHandle?>()
            private val completion = CountDownLatch(1)
            private val cancellationRequested = AtomicBoolean(false)
            private val cancellationDispatched = AtomicBoolean(false)
            private val cancellationResult = AtomicReference<PreparedBackendCancellationResult?>()

            override fun run(indicator: ProgressIndicator) {
                try {
                    val executionHandle = executeBackend { completed ->
                        if (outcome.compareAndSet(null, completed)) {
                            completion.countDown()
                        }
                    }
                    handle.set(executionHandle)
                    if (cancellationRequested.get()) {
                        dispatchCancellation()
                    }

                    while (!completion.await(CANCELLATION_PROBE_MILLIS, TimeUnit.MILLISECONDS)) {
                        indicator.checkCanceled()
                    }
                    indicator.checkCanceled()
                } catch (cancelled: ProcessCanceledException) {
                    requestCancellation()
                    throw cancelled
                }
            }

            override fun onSuccess() {
                onCompleted(
                    checkNotNull(outcome.get()) {
                        "prepared backend task succeeded without a terminal outcome"
                    },
                )
            }

            override fun onCancel() {
                onCompleted(cancellationOutcome(requestCancellation()))
            }

            private fun requestCancellation(): PreparedBackendCancellationResult {
                cancellationRequested.set(true)
                return dispatchCancellation()
            }

            private fun dispatchCancellation(): PreparedBackendCancellationResult {
                cancellationResult.get()?.let { return it }
                val executionHandle = handle.get() ?: return PreparedBackendCancellationResult.Requested
                if (!cancellationDispatched.compareAndSet(false, true)) {
                    return cancellationResult.get() ?: PreparedBackendCancellationResult.Requested
                }
                return executionHandle.cancel().also(cancellationResult::set)
            }
        }.queue()
    }

    private fun cancellationOutcome(
        result: PreparedBackendCancellationResult,
    ): DatabaseSessionPreparedOutcome =
        when (result) {
            PreparedBackendCancellationResult.Requested,
            PreparedBackendCancellationResult.AlreadyFinished,
            -> DatabaseSessionPreparedOutcome.Cancelled

            is PreparedBackendCancellationResult.Failed ->
                DatabaseSessionPreparedOutcome.Failed(
                    DatabaseSessionPreparedFailure(
                        kind = DatabaseSessionPreparedFailureKind.EXECUTION_FAILED,
                        code = PREPARED_CANCELLATION_FAILED,
                        diagnosticType = result.diagnosticType,
                    ),
                )
        }

    companion object {
        private const val CANCELLATION_PROBE_MILLIS = 50L
    }
}
