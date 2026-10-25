package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.execution.ResolvedExecutionTarget
import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.preparation.PreparationSource
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

internal typealias LeapExecutionCandidateBuilder = (
    source: PreparationSource,
    parameterContract: ParameterContract,
    inputEnvironment: InputEnvironment,
    target: ResolvedExecutionTarget,
    isInvocationActive: () -> Boolean,
    checkCanceled: () -> Unit,
) -> LeapExecutionCandidateResult

/**
 * Cancellable IntelliJ background boundary for immutable candidate construction.
 *
 * The worker receives only immutable source/input/target contracts plus lifecycle probes. IDE UI
 * objects and Database Tools resources remain outside this task. Completion callbacks run through
 * the Task lifecycle and are revalidated before the delivery coordinator may resume.
 */
internal class LeapExecutionCandidateAdapter(
    private val project: Project,
    private val candidateBuilder: LeapExecutionCandidateBuilder =
        { source, parameterContract, inputEnvironment, target, isInvocationActive, checkCanceled ->
            LeapExecutionCandidateCoordinator.build(
                source = source,
                parameterContract = parameterContract,
                inputEnvironment = inputEnvironment,
                target = target,
                isInvocationActive = isInvocationActive,
                checkCanceled = checkCanceled,
            )
        },
) {
    fun requestCandidate(
        source: PreparationSource,
        parameterContract: ParameterContract,
        inputEnvironment: InputEnvironment,
        target: ResolvedExecutionTarget,
        isInvocationActive: () -> Boolean,
        onResult: (LeapExecutionCandidateResult) -> Unit,
        onCancelled: () -> Unit,
    ) {
        val liveInvocation = { !project.isDisposed && isInvocationActive() }
        if (!liveInvocation()) {
            onResult(LeapExecutionCandidateResult.InvocationInvalidated)
            return
        }

        object : Task.Backgroundable(project, "Preparing zMyBatis execution", true) {
            private var candidate: LeapExecutionCandidateResult? = null

            override fun run(indicator: ProgressIndicator) {
                candidate = candidateBuilder(
                    source,
                    parameterContract,
                    inputEnvironment,
                    target,
                    liveInvocation,
                    indicator::checkCanceled,
                )
            }

            override fun onSuccess() {
                val completed = checkNotNull(candidate) {
                    "candidate background task completed without a result"
                }
                onResult(
                    if (liveInvocation()) {
                        completed
                    } else {
                        LeapExecutionCandidateResult.InvocationInvalidated
                    },
                )
            }

            override fun onCancel() {
                if (liveInvocation()) {
                    onCancelled()
                } else {
                    onResult(LeapExecutionCandidateResult.InvocationInvalidated)
                }
            }
        }.queue()
    }
}
