package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.execution.PreparedExecutionPlan
import com.algorist.zMyBatis.core.execution.PreparedExecutionPlanFailure
import com.algorist.zMyBatis.core.execution.PreparedExecutionPlanResult
import com.algorist.zMyBatis.core.execution.PreparedExecutionPlanner
import com.algorist.zMyBatis.core.execution.ResolvedExecutionTarget
import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationFailure
import com.algorist.zMyBatis.core.preparation.PreparationRequestResult
import com.algorist.zMyBatis.core.preparation.PreparationResult
import com.algorist.zMyBatis.core.preparation.PreparationSource
import com.algorist.zMyBatis.mybatis.MyBatisPreparationBoundary

/**
 * Application boundary for building the immutable execution candidate used by the registered action.
 *
 * This deliberately stops before Database Tools delivery; downstream approval and delivery retain
 * the exact candidate authority without a legacy execution fallback.
 */
internal object LeapExecutionCandidateCoordinator {
    fun build(
        source: PreparationSource,
        parameterContract: ParameterContract,
        inputEnvironment: InputEnvironment,
        target: ResolvedExecutionTarget,
        isInvocationActive: () -> Boolean = { true },
        checkCanceled: () -> Unit = {},
    ): LeapExecutionCandidateResult {
        fun invocationStillActive(): Boolean {
            checkCanceled()
            return isInvocationActive()
        }

        if (!invocationStillActive()) {
            return LeapExecutionCandidateResult.InvocationInvalidated
        }

        val requestResult = MyBatisPreparationRequest.create(
            source = source,
            parameterContract = parameterContract,
            inputEnvironment = inputEnvironment,
            effectiveDatabaseId = target.effectiveDatabaseId,
        )
        if (!invocationStillActive()) {
            return LeapExecutionCandidateResult.InvocationInvalidated
        }
        val request = when (requestResult) {
            is PreparationRequestResult.Ready -> requestResult.request
            is PreparationRequestResult.Failed ->
                return LeapExecutionCandidateResult.PreparationFailed(requestResult.failure)
        }

        val preparationResult = MyBatisPreparationBoundary.prepare(request)
        if (!invocationStillActive()) {
            return LeapExecutionCandidateResult.InvocationInvalidated
        }
        val prepared = when (preparationResult) {
            is PreparationResult.Success -> preparationResult.execution
            is PreparationResult.Failed ->
                return LeapExecutionCandidateResult.PreparationFailed(preparationResult.failure)
        }

        val planResult = PreparedExecutionPlanner.freeze(prepared, target)
        if (!invocationStillActive()) {
            return LeapExecutionCandidateResult.InvocationInvalidated
        }

        return when (planResult) {
            is PreparedExecutionPlanResult.Ready ->
                LeapExecutionCandidateResult.Ready(planResult.plan)
            is PreparedExecutionPlanResult.Failed ->
                LeapExecutionCandidateResult.PlanFailed(planResult.failure)
        }
    }
}

internal sealed interface LeapExecutionCandidateResult {
    data object InvocationInvalidated : LeapExecutionCandidateResult

    data class Ready(
        val plan: PreparedExecutionPlan,
    ) : LeapExecutionCandidateResult

    data class PreparationFailed(
        val failure: PreparationFailure,
    ) : LeapExecutionCandidateResult

    data class PlanFailed(
        val failure: PreparedExecutionPlanFailure,
    ) : LeapExecutionCandidateResult
}
