package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.materialization.MaterializationSafetyFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeapExecutionApprovalPolicyTest {
    @Test
    fun ordinaryReadWithoutUserPreviewNeedsNoModalApproval() {
        val decision = decision(
            showSqlPreview = false,
            declaredMutation = false,
            containsRawInterpolation = false,
        )

        assertFalse(decision.requiresPreview)
        assertTrue(decision.reasons.isEmpty())
    }

    @Test
    fun userPreviewSettingRequiresApproval() {
        val decision = decision(
            showSqlPreview = true,
            declaredMutation = false,
            containsRawInterpolation = false,
        )

        assertEquals(
            setOf(LeapExecutionApprovalReason.USER_PREVIEW_SETTING),
            decision.reasons,
        )
    }

    @Test
    fun declaredMutationForcesApprovalWhenUserPreviewIsOff() {
        val decision = decision(
            showSqlPreview = false,
            declaredMutation = true,
            containsRawInterpolation = false,
        )

        assertEquals(
            setOf(LeapExecutionApprovalReason.DECLARED_MUTATION),
            decision.reasons,
        )
    }

    @Test
    fun rawInterpolationForcesApprovalWhenUserPreviewIsOff() {
        val decision = decision(
            showSqlPreview = false,
            declaredMutation = false,
            containsRawInterpolation = true,
        )

        assertEquals(
            setOf(LeapExecutionApprovalReason.RAW_INTERPOLATION),
            decision.reasons,
        )
    }

    @Test
    fun independentReasonsCompose() {
        val decision = decision(
            showSqlPreview = true,
            declaredMutation = true,
            containsRawInterpolation = true,
        )

        assertEquals(
            setOf(
                LeapExecutionApprovalReason.USER_PREVIEW_SETTING,
                LeapExecutionApprovalReason.DECLARED_MUTATION,
                LeapExecutionApprovalReason.RAW_INTERPOLATION,
            ),
            decision.reasons,
        )
    }

    private fun decision(
        showSqlPreview: Boolean,
        declaredMutation: Boolean,
        containsRawInterpolation: Boolean,
    ): LeapExecutionApprovalDecision =
        leapExecutionApprovalDecision(
            showSqlPreview = showSqlPreview,
            safetyFlags = MaterializationSafetyFlags(
                declaredMutation = declaredMutation,
                containsRawInterpolation = containsRawInterpolation,
            ),
        )
}
