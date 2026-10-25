package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.ResolvedExecutionTarget
import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.materialization.ExecutionMaterializer
import com.algorist.zMyBatis.core.materialization.MaterializationFailure
import com.algorist.zMyBatis.core.materialization.MaterializationResult
import com.algorist.zMyBatis.core.materialization.MaterializedExecution
import com.algorist.zMyBatis.core.materialization.PostgreSqlBigintExecutionMaterializer
import com.algorist.zMyBatis.core.materialization.TargetBoundRawExecutionMaterializer
import com.algorist.zMyBatis.core.materialization.TargetBoundZeroBindingExecutionMaterializer
import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationFailure
import com.algorist.zMyBatis.core.preparation.PreparationRequestResult
import com.algorist.zMyBatis.core.preparation.PreparationResult
import com.algorist.zMyBatis.core.preparation.PreparationSource
import com.algorist.zMyBatis.core.preparation.PreparedExecution
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

        val materializationResult = materializer(prepared, target).materialize(prepared)
        if (!invocationStillActive()) {
            return LeapExecutionCandidateResult.InvocationInvalidated
        }
        val execution = when (materializationResult) {
            is MaterializationResult.Success -> materializationResult.execution
            is MaterializationResult.Failed ->
                return LeapExecutionCandidateResult.MaterializationFailed(materializationResult.failure)
        }

        if (execution.executionTargetId != target.targetId) {
            return LeapExecutionCandidateResult.TargetIdentityMismatch(
                expected = target.targetId,
                actual = execution.executionTargetId,
            )
        }

        return LeapExecutionCandidateResult.Ready(execution)
    }

    private fun materializer(
        prepared: PreparedExecution,
        target: ResolvedExecutionTarget,
    ): ExecutionMaterializer =
        when {
            prepared.rawInterpolations.isNotEmpty() && prepared.orderedBindings.isEmpty() ->
                TargetBoundRawExecutionMaterializer(target)
            prepared.orderedBindings.isEmpty() ->
                TargetBoundZeroBindingExecutionMaterializer(target)
            else ->
                PostgreSqlBigintExecutionMaterializer(target)
        }
}

internal sealed interface LeapExecutionCandidateResult {
    data object InvocationInvalidated : LeapExecutionCandidateResult

    data class Ready(
        val execution: MaterializedExecution,
    ) : LeapExecutionCandidateResult

    data class PreparationFailed(
        val failure: PreparationFailure,
    ) : LeapExecutionCandidateResult

    data class MaterializationFailed(
        val failure: MaterializationFailure,
    ) : LeapExecutionCandidateResult

    data class TargetIdentityMismatch(
        val expected: ExecutionTargetId,
        val actual: ExecutionTargetId?,
    ) : LeapExecutionCandidateResult
}
