package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.SqlFormatter
import com.algorist.zMyBatis.SqlPreviewDialog
import com.algorist.zMyBatis.core.execution.PreparedExecutionPlan
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

internal fun preparedPlanPreview(
    plan: PreparedExecutionPlan,
    sqlPresentation: String = plan.sqlWithPlaceholders,
    valuesMaterialized: Boolean = false,
): String {
    if (plan.orderedBindings.isEmpty()) return sqlPresentation

    val bindings = plan.orderedBindings.joinToString(separator = "\n") { binding ->
        val javaType = binding.metadata.mappingJavaTypeIdentity
            ?: binding.metadata.declaredJavaTypeIdentity?.value
            ?: "unknown"
        val jdbcType = binding.metadata.effectiveJdbcTypeIdentity
            ?: binding.metadata.jdbcTypeIdentity
            ?: "unknown"
        "#${binding.index + 1} property=${binding.property} " +
            "javaType=$javaType jdbcType=$jdbcType " +
            "handler=${binding.metadata.typeHandlerIdentity}"
    }

    return buildString {
        append(sqlPresentation)
        append(
            if (valuesMaterialized) {
                "\n\nBinding metadata (values materialized in SQL above):\n"
            } else {
                "\n\nBindings (values hidden):\n"
            },
        )
        append(bindings)
    }
}

/**
 * Testable approval kernel. [formatPreview] and [showPreview] may transform/display only a
 * presentation copy. [plan] remains the semantic authority and [executionSql] is the exact
 * materialized text that will be dispatched after approval.
 */
internal fun runLeapExecutionApproval(
    plan: PreparedExecutionPlan,
    executionSql: String = plan.sqlWithPlaceholders,
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
        safetyFlags = plan.safetyFlags,
    )
    if (!decision.requiresPreview) {
        return LeapExecutionApprovalResult.Approved
    }

    val previewSql =
        if (options.formatPreviewSql) {
            formatPreview(executionSql)
        } else {
            executionSql
        }
    val previewText = preparedPlanPreview(
        plan = plan,
        sqlPresentation = previewSql,
        valuesMaterialized = plan.orderedBindings.isNotEmpty(),
    )
    if (!showPreview(previewText)) {
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
 * A required preview is always scheduled onto the EDT. The original plan/materialized SQL are
 * never rewritten; formatting is presentation-only and execution resumes with the exact artifact.
 */
internal class LeapExecutionApprovalAdapter(private val project: Project) {
    fun requestApproval(
        plan: PreparedExecutionPlan,
        executionSql: String = plan.sqlWithPlaceholders,
        options: LeapExecutionApprovalOptions,
        isInvocationActive: () -> Boolean,
        onResult: (LeapExecutionApprovalResult) -> Unit,
    ) {
        val liveInvocation = { !project.isDisposed && isInvocationActive() }
        val decision = leapExecutionApprovalDecision(
            showSqlPreview = options.showSqlPreview,
            safetyFlags = plan.safetyFlags,
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
                    plan = plan,
                    executionSql = executionSql,
                    options = options,
                    isInvocationActive = liveInvocation,
                    formatPreview = { sql -> SqlFormatter.format(project, sql) },
                    showPreview = { sql -> SqlPreviewDialog(project, sql).showAndGet() },
                ),
            )
        }, ModalityState.defaultModalityState())
    }
}
