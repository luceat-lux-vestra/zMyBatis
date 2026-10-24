package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.SqlFormatter
import com.algorist.zMyBatis.SqlPreviewDialog
import com.algorist.zMyBatis.core.materialization.MaterializedExecution
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project

/**
 * Explicit presentation inputs for the replacement execution path.
 *
 * These values are supplied by the future thin action. The adapter deliberately does not read
 * application settings itself.
 */
internal data class LeapExecutionApprovalOptions(
    val showSqlPreview: Boolean,
    val formatPreviewSql: Boolean,
)

internal sealed interface LeapExecutionApprovalResult {
    data object Approved : LeapExecutionApprovalResult
    data object UserCancelled : LeapExecutionApprovalResult
    data object InvocationInvalidated : LeapExecutionApprovalResult
}

/**
 * Testable approval kernel. [formatPreview] and [showPreview] may transform/display only a
 * presentation copy. [artifact] remains the sole execution authority.
 */
internal fun runLeapExecutionApproval(
    artifact: MaterializedExecution,
    options: LeapExecutionApprovalOptions,
    isInvocationActive: () -> Boolean,
    formatPreview: (String) -> String,
    showPreview: (String) -> Boolean,
): LeapExecutionApprovalResult {
    if (!isInvocationActive()) {
        return LeapExecutionApprovalResult.InvocationInvalidated
    }

    val decision = leapExecutionApprovalDecision(
        showSqlPreview = options.showSqlPreview,
        safetyFlags = artifact.safetyFlags,
    )
    if (!decision.requiresPreview) {
        return LeapExecutionApprovalResult.Approved
    }

    val previewSql =
        if (options.formatPreviewSql) formatPreview(artifact.executionSql) else artifact.executionSql
    if (!showPreview(previewSql)) {
        return LeapExecutionApprovalResult.UserCancelled
    }

    return if (isInvocationActive()) {
        LeapExecutionApprovalResult.Approved
    } else {
        LeapExecutionApprovalResult.InvocationInvalidated
    }
}

/**
 * IDE modal adapter for immutable-artifact approval.
 *
 * A required preview is always scheduled onto the EDT. The original artifact is never rewritten;
 * formatting is presentation-only and approval resumes with the exact same artifact instance.
 */
internal class LeapExecutionApprovalAdapter(private val project: Project) {
    fun requestApproval(
        artifact: MaterializedExecution,
        options: LeapExecutionApprovalOptions,
        isInvocationActive: () -> Boolean,
        onResult: (LeapExecutionApprovalResult) -> Unit,
    ) {
        val liveInvocation = { !project.isDisposed && isInvocationActive() }
        val decision = leapExecutionApprovalDecision(
            showSqlPreview = options.showSqlPreview,
            safetyFlags = artifact.safetyFlags,
        )

        if (!decision.requiresPreview) {
            onResult(
                if (liveInvocation()) {
                    LeapExecutionApprovalResult.Approved
                } else {
                    LeapExecutionApprovalResult.InvocationInvalidated
                },
            )
            return
        }

        ApplicationManager.getApplication().invokeLater({
            onResult(
                runLeapExecutionApproval(
                    artifact = artifact,
                    options = options,
                    isInvocationActive = liveInvocation,
                    formatPreview = { sql -> SqlFormatter.format(project, sql) },
                    showPreview = { sql -> SqlPreviewDialog(project, sql).showAndGet() },
                ),
            )
        }, ModalityState.defaultModalityState())
    }
}
