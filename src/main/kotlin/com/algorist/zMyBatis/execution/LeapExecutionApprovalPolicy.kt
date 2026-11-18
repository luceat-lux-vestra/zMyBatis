package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.execution.PreparedExecutionPlanSafetyFlags

internal enum class LeapExecutionApprovalReason {
    USER_PREVIEW_SETTING,
    DECLARED_MUTATION,
    RAW_INTERPOLATION,
}

internal data class LeapExecutionApprovalDecision(
    val reasons: Set<LeapExecutionApprovalReason>,
) {
    val requiresPreview: Boolean
        get() = reasons.isNotEmpty()
}

/**
 * Pure approval policy over explicit user policy plus immutable prepared-plan safety evidence.
 *
 * SQL text is deliberately not classified here. Mutation/raw evidence is established before this
 * boundary and carried by [PreparedExecutionPlanSafetyFlags].
 */
internal fun leapExecutionApprovalDecision(
    showSqlPreview: Boolean,
    safetyFlags: PreparedExecutionPlanSafetyFlags,
): LeapExecutionApprovalDecision {
    val reasons = linkedSetOf<LeapExecutionApprovalReason>()
    if (showSqlPreview) {
        reasons += LeapExecutionApprovalReason.USER_PREVIEW_SETTING
    }
    if (safetyFlags.declaredMutation) {
        reasons += LeapExecutionApprovalReason.DECLARED_MUTATION
    }
    if (safetyFlags.containsRawInterpolation) {
        reasons += LeapExecutionApprovalReason.RAW_INTERPOLATION
    }
    return LeapExecutionApprovalDecision(reasons.toSet())
}
