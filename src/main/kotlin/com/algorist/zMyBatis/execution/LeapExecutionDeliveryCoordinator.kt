package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.execution.ExecutionTargetDescriptor
import com.algorist.zMyBatis.core.execution.PreparedExecutionPlan
import com.algorist.zMyBatis.core.execution.PreparedExecutionPlanFailure
import com.algorist.zMyBatis.core.execution.ResolvedExecutionTarget
import com.algorist.zMyBatis.core.execution.TargetResolutionFailure
import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.preparation.PreparationFailure
import com.algorist.zMyBatis.core.preparation.PreparationSource
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.source.SourceGraphRevalidationFailure
import com.algorist.zMyBatis.source.SourceGraphRevalidationResult
import com.algorist.zMyBatis.source.StatementSourceGraphRevalidator
import com.intellij.openapi.project.Project

/**
 * Registered-action coordinator from immutable preparation inputs to one approved execution plan.
 *
 * Backend selection is explicit and determined only by whether the approved plan still carries
 * JDBC bindings. No materialization/literalization fallback is permitted.
 */
internal class LeapExecutionDeliveryCoordinator(project: Project) {
    private val targetResolver = DatabaseToolsTargetResolver(project)
    private val consoleAdapter = DatabaseToolsConsoleAdapter.getInstance(project)
    private val approvalAdapter = LeapExecutionApprovalAdapter(project)
    private val candidateAdapter = LeapExecutionCandidateAdapter(project)
    private val nativeBackend = NativeConsoleSqlTextBackend(consoleAdapter)
    private val preparedBackend = DatabaseSessionPreparedBackend(
        PreparedResultLimits(
            maxRows = PREPARED_RESULT_MAX_ROWS,
            maxColumns = PREPARED_RESULT_MAX_COLUMNS,
        ),
    )
    private val preparedExecutionTask = PreparedBackendExecutionTaskAdapter(project)
    private val preparedResultAdapter = PreparedResultPresentationAdapter(project)

    fun execute(
        source: PreparationSource,
        parameterContract: ParameterContract,
        inputEnvironment: InputEnvironment,
        targetDescriptor: ExecutionTargetDescriptor,
        resourceKey: String,
        forceNewConsole: Boolean,
        approvalOptions: LeapExecutionApprovalOptions,
        isInvocationActive: () -> Boolean,
        onExecuted: () -> Unit,
        onCancelled: () -> Unit,
        onFailure: (LeapExecutionDeliveryFailure) -> Unit,
    ) {
        val authority = preparationSourceAuthority(source)
        val initialTarget = when (
            val admission = admit(
                authority = authority,
                targetDescriptor = targetDescriptor,
                isInvocationActive = isInvocationActive,
            )
        ) {
            is LeapDeliveryAdmission.Ready -> admission.resolution.resolvedTarget
            is LeapDeliveryAdmission.Rejected -> {
                onFailure(admission.failure)
                return
            }
        }

        candidateAdapter.requestCandidate(
            source = source,
            parameterContract = parameterContract,
            inputEnvironment = inputEnvironment,
            target = initialTarget,
            isInvocationActive = isInvocationActive,
            onResult = { candidate ->
                continueWithCandidate(
                    authority = authority,
                    candidate = candidate,
                    targetDescriptor = targetDescriptor,
                    resourceKey = resourceKey,
                    forceNewConsole = forceNewConsole,
                    approvalOptions = approvalOptions,
                    isInvocationActive = isInvocationActive,
                    onExecuted = onExecuted,
                    onCancelled = onCancelled,
                    onFailure = onFailure,
                )
            },
            onCancelled = onCancelled,
        )
    }

    private fun continueWithCandidate(
        authority: PreparationSourceAuthority,
        candidate: LeapExecutionCandidateResult,
        targetDescriptor: ExecutionTargetDescriptor,
        resourceKey: String,
        forceNewConsole: Boolean,
        approvalOptions: LeapExecutionApprovalOptions,
        isInvocationActive: () -> Boolean,
        onExecuted: () -> Unit,
        onCancelled: () -> Unit,
        onFailure: (LeapExecutionDeliveryFailure) -> Unit,
    ) {
        val plan = when (candidate) {
            LeapExecutionCandidateResult.InvocationInvalidated -> {
                onFailure(LeapExecutionDeliveryFailure.InvocationInvalidated)
                return
            }
            is LeapExecutionCandidateResult.Ready -> candidate.plan
            is LeapExecutionCandidateResult.PreparationFailed -> {
                onFailure(LeapExecutionDeliveryFailure.PreparationFailed(candidate.failure))
                return
            }
            is LeapExecutionCandidateResult.PlanFailed -> {
                onFailure(LeapExecutionDeliveryFailure.PlanFailed(candidate.failure))
                return
            }
        }

        if (!isInvocationActive()) {
            onFailure(LeapExecutionDeliveryFailure.InvocationInvalidated)
            return
        }
        if (plan.sourceRevisions != authority.sourceRevisions) {
            onFailure(LeapExecutionDeliveryFailure.PlanSourceAuthorityMismatch)
            return
        }

        approvalAdapter.requestApproval(
            plan = plan,
            options = approvalOptions,
            isInvocationActive = isInvocationActive,
            onResult = { approval ->
                when (approval) {
                    LeapExecutionApprovalResult.Approved ->
                        deliverApproved(
                            authority = authority,
                            plan = plan,
                            targetDescriptor = targetDescriptor,
                            resourceKey = resourceKey,
                            forceNewConsole = forceNewConsole,
                            isInvocationActive = isInvocationActive,
                            onExecuted = onExecuted,
                            onCancelled = onCancelled,
                            onFailure = onFailure,
                        )
                    LeapExecutionApprovalResult.UserCancelled -> onCancelled()
                    LeapExecutionApprovalResult.InvocationInvalidated ->
                        onFailure(LeapExecutionDeliveryFailure.InvocationInvalidated)
                }
            },
        )
    }

    private fun deliverApproved(
        authority: PreparationSourceAuthority,
        plan: PreparedExecutionPlan,
        targetDescriptor: ExecutionTargetDescriptor,
        resourceKey: String,
        forceNewConsole: Boolean,
        isInvocationActive: () -> Boolean,
        onExecuted: () -> Unit,
        onCancelled: () -> Unit,
        onFailure: (LeapExecutionDeliveryFailure) -> Unit,
    ) {
        val deliveryTarget = when (
            val admission = admit(
                authority = authority,
                targetDescriptor = targetDescriptor,
                isInvocationActive = isInvocationActive,
            )
        ) {
            is LeapDeliveryAdmission.Ready -> admission.resolution
            is LeapDeliveryAdmission.Rejected -> {
                onFailure(admission.failure)
                return
            }
        }
        if (!planMatchesResolvedTarget(plan, deliveryTarget.resolvedTarget)) {
            onFailure(LeapExecutionDeliveryFailure.TargetSemanticDrift)
            return
        }

        consoleAdapter.acquireAndDeliverConsole(
            dataSource = deliveryTarget.dataSource,
            schema = deliveryTarget.schema,
            resourceKey = resourceKey,
            forceNew = forceNewConsole,
            onConsoleReady = { console, releaseConsole ->
                val preExecutionCheck = {
                    planDeliveryValid(
                        authority = authority,
                        plan = plan,
                        targetDescriptor = targetDescriptor,
                        isInvocationActive = isInvocationActive,
                    )
                }

                if (plan.orderedBindings.isEmpty()) {
                    nativeBackend.execute(
                        console = console,
                        plan = plan,
                        preExecutionCheck = preExecutionCheck,
                        onExecuted = onExecuted,
                        onFailure = { failure ->
                            onFailure(LeapExecutionDeliveryFailure.NativeExecutionFailed(failure))
                        },
                        onFinished = releaseConsole,
                    )
                } else {
                    preparedExecutionTask.execute(
                        executeBackend = { onCompleted ->
                            preparedBackend.execute(
                                console = console,
                                plan = plan,
                                currentTarget = deliveryTarget.resolvedTarget,
                                preExecutionCheck = preExecutionCheck,
                                onCompleted = onCompleted,
                            )
                        },
                        onCompleted = { outcome ->
                            handlePreparedOutcome(
                                outcome = outcome,
                                isInvocationActive = isInvocationActive,
                                onExecuted = onExecuted,
                                onCancelled = onCancelled,
                                onFailure = onFailure,
                                onFinished = releaseConsole,
                            )
                        },
                    )
                }
            },
            onFailure = { failure ->
                onFailure(LeapExecutionDeliveryFailure.ConsoleAcquisitionFailed(failure))
            },
        )
    }

    private fun handlePreparedOutcome(
        outcome: DatabaseSessionPreparedOutcome,
        isInvocationActive: () -> Boolean,
        onExecuted: () -> Unit,
        onCancelled: () -> Unit,
        onFailure: (LeapExecutionDeliveryFailure) -> Unit,
        onFinished: () -> Unit,
    ) {
        preparedResultAdapter.present(
            outcome = outcome,
            isInvocationActive = isInvocationActive,
            onFinished = {
                try {
                    when (outcome) {
                        is DatabaseSessionPreparedOutcome.Query,
                        is DatabaseSessionPreparedOutcome.Update,
                        -> onExecuted()

                        DatabaseSessionPreparedOutcome.Cancelled ->
                            onCancelled()

                        is DatabaseSessionPreparedOutcome.Failed ->
                            onFailure(
                                LeapExecutionDeliveryFailure.PreparedExecutionFailed(
                                    outcome.failure,
                                ),
                            )
                    }
                } finally {
                    onFinished()
                }
            },
        )
    }

    private fun planDeliveryValid(
        authority: PreparationSourceAuthority,
        plan: PreparedExecutionPlan,
        targetDescriptor: ExecutionTargetDescriptor,
        isInvocationActive: () -> Boolean,
    ): Boolean {
        if (!isInvocationActive()) return false
        if (revalidate(authority) !is SourceGraphRevalidationResult.Current) return false

        return when (val resolution = targetResolver.resolve(targetDescriptor)) {
            is DatabaseToolsTargetResolution.Success ->
                planMatchesResolvedTarget(plan, resolution.resolvedTarget)
            is DatabaseToolsTargetResolution.Failed -> false
        }
    }

    private fun admit(
        authority: PreparationSourceAuthority,
        targetDescriptor: ExecutionTargetDescriptor,
        isInvocationActive: () -> Boolean,
    ): LeapDeliveryAdmission =
        leapDeliveryAdmission(
            authority = authority,
            isInvocationActive = isInvocationActive,
            revalidateSources = StatementSourceGraphRevalidator::revalidateSnapshots,
            resolveTarget = { targetResolver.resolve(targetDescriptor) },
        )

    private fun revalidate(authority: PreparationSourceAuthority): SourceGraphRevalidationResult =
        StatementSourceGraphRevalidator.revalidateSnapshots(authority.snapshots)

    companion object {
        private const val PREPARED_RESULT_MAX_ROWS = 1_000
        private const val PREPARED_RESULT_MAX_COLUMNS = 256
    }
}

internal sealed interface LeapDeliveryAdmission {
    data class Ready(
        val resolution: DatabaseToolsTargetResolution.Success,
    ) : LeapDeliveryAdmission

    data class Rejected(
        val failure: LeapExecutionDeliveryFailure,
    ) : LeapDeliveryAdmission
}

internal fun leapDeliveryAdmission(
    authority: PreparationSourceAuthority,
    isInvocationActive: () -> Boolean,
    revalidateSources: (List<SourceSnapshot>) -> SourceGraphRevalidationResult,
    resolveTarget: () -> DatabaseToolsTargetResolution,
): LeapDeliveryAdmission {
    if (!isInvocationActive()) {
        return LeapDeliveryAdmission.Rejected(LeapExecutionDeliveryFailure.InvocationInvalidated)
    }

    when (val source = revalidateSources(authority.snapshots)) {
        SourceGraphRevalidationResult.Current -> Unit
        is SourceGraphRevalidationResult.Failed ->
            return LeapDeliveryAdmission.Rejected(
                LeapExecutionDeliveryFailure.SourceInvalid(source.failure),
            )
    }

    return when (val target = resolveTarget()) {
        is DatabaseToolsTargetResolution.Success -> LeapDeliveryAdmission.Ready(target)
        is DatabaseToolsTargetResolution.Failed ->
            LeapDeliveryAdmission.Rejected(
                LeapExecutionDeliveryFailure.TargetResolutionFailed(target.failure),
            )
    }
}

internal fun planMatchesResolvedTarget(
    plan: PreparedExecutionPlan,
    target: ResolvedExecutionTarget,
): Boolean =
    plan.executionTargetId == target.targetId &&
        plan.databaseId == target.effectiveDatabaseId?.value

internal sealed interface LeapExecutionDeliveryFailure {
    data object InvocationInvalidated : LeapExecutionDeliveryFailure

    data class SourceInvalid(
        val failure: SourceGraphRevalidationFailure,
    ) : LeapExecutionDeliveryFailure

    data class TargetResolutionFailed(
        val failure: TargetResolutionFailure,
    ) : LeapExecutionDeliveryFailure

    data class PreparationFailed(
        val failure: PreparationFailure,
    ) : LeapExecutionDeliveryFailure

    data class PlanFailed(
        val failure: PreparedExecutionPlanFailure,
    ) : LeapExecutionDeliveryFailure

    data object PlanSourceAuthorityMismatch : LeapExecutionDeliveryFailure

    data object TargetSemanticDrift : LeapExecutionDeliveryFailure

    data class ConsoleAcquisitionFailed(
        val failure: DatabaseToolsConsoleAcquisitionFailure,
    ) : LeapExecutionDeliveryFailure

    data class NativeExecutionFailed(
        val failure: NativeConsoleSqlTextFailure,
    ) : LeapExecutionDeliveryFailure

    data class PreparedExecutionFailed(
        val failure: DatabaseSessionPreparedFailure,
    ) : LeapExecutionDeliveryFailure
}
