package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.execution.ExecutionTargetDescriptor
import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.TargetResolutionFailure
import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.materialization.MaterializationFailure
import com.algorist.zMyBatis.core.materialization.MaterializedExecution
import com.algorist.zMyBatis.core.preparation.PreparationFailure
import com.algorist.zMyBatis.core.preparation.PreparationSource
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.source.SourceGraphRevalidationFailure
import com.algorist.zMyBatis.source.SourceGraphRevalidationResult
import com.algorist.zMyBatis.source.StatementSourceGraphRevalidator
import com.intellij.openapi.project.Project

/**
 * #66 application coordinator from approved preparation inputs to Database Tools.
 *
 * The registered action reaches this boundary through the Leap invocation chain. Immutable-artifact
 * approval and delivery remain the single execution authority with no legacy fallback.
 */
internal class LeapExecutionDeliveryCoordinator(project: Project) {
    private val targetResolver = DatabaseToolsTargetResolver(project)
    private val consoleAdapter = DatabaseToolsConsoleAdapter.getInstance(project)
    private val approvalAdapter = LeapExecutionApprovalAdapter(project)
    private val candidateAdapter = LeapExecutionCandidateAdapter(project)

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
        val artifact = when (candidate) {
            LeapExecutionCandidateResult.InvocationInvalidated -> {
                onFailure(LeapExecutionDeliveryFailure.InvocationInvalidated)
                return
            }
            is LeapExecutionCandidateResult.Ready -> candidate.execution
            is LeapExecutionCandidateResult.PreparationFailed -> {
                onFailure(LeapExecutionDeliveryFailure.PreparationFailed(candidate.failure))
                return
            }
            is LeapExecutionCandidateResult.MaterializationFailed -> {
                onFailure(LeapExecutionDeliveryFailure.MaterializationFailed(candidate.failure))
                return
            }
            is LeapExecutionCandidateResult.TargetIdentityMismatch -> {
                onFailure(
                    LeapExecutionDeliveryFailure.CandidateTargetIdentityMismatch(
                        expected = candidate.expected,
                        actual = candidate.actual,
                    ),
                )
                return
            }
        }

        if (!isInvocationActive()) {
            onFailure(LeapExecutionDeliveryFailure.InvocationInvalidated)
            return
        }
        if (artifact.sourceRevisions != authority.sourceRevisions) {
            onFailure(LeapExecutionDeliveryFailure.ArtifactSourceAuthorityMismatch)
            return
        }

        approvalAdapter.requestApproval(
            artifact = artifact,
            options = approvalOptions,
            isInvocationActive = isInvocationActive,
            onResult = { approval ->
                when (approval) {
                    LeapExecutionApprovalResult.Approved ->
                        deliverApproved(
                            authority = authority,
                            artifact = artifact,
                            targetDescriptor = targetDescriptor,
                            resourceKey = resourceKey,
                            forceNewConsole = forceNewConsole,
                            isInvocationActive = isInvocationActive,
                            onExecuted = onExecuted,
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
        artifact: MaterializedExecution,
        targetDescriptor: ExecutionTargetDescriptor,
        resourceKey: String,
        forceNewConsole: Boolean,
        isInvocationActive: () -> Boolean,
        onExecuted: () -> Unit,
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
        if (!artifactMatchesResolvedTarget(artifact, deliveryTarget.resolvedTarget)) {
            onFailure(LeapExecutionDeliveryFailure.TargetSemanticDrift)
            return
        }

        consoleAdapter.acquireAndDeliverConsole(
            dataSource = deliveryTarget.dataSource,
            schema = deliveryTarget.schema,
            resourceKey = resourceKey,
            forceNew = forceNewConsole,
            onConsoleReady = { console, releaseConsole ->
                consoleAdapter.executeMaterialized(
                    console = console,
                    artifact = artifact,
                    currentTarget = {
                        currentTargetForArtifact(targetDescriptor, artifact)
                    },
                    isInvocationActive = isInvocationActive,
                    areSourceRevisionsCurrent = { expected ->
                        expected == authority.sourceRevisions &&
                            revalidate(authority) is SourceGraphRevalidationResult.Current
                    },
                    onExecuted = onExecuted,
                    onFailure = { failure ->
                        onFailure(LeapExecutionDeliveryFailure.SqlExecutionFailed(failure))
                    },
                    onFinished = releaseConsole,
                )
            },
            onFailure = { failure ->
                onFailure(LeapExecutionDeliveryFailure.ConsoleAcquisitionFailed(failure))
            },
        )
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

    private fun currentTargetForArtifact(
        descriptor: ExecutionTargetDescriptor,
        artifact: MaterializedExecution,
    ) =
        when (val resolution = targetResolver.resolve(descriptor)) {
            is DatabaseToolsTargetResolution.Success ->
                resolution.resolvedTarget.takeIf { artifactMatchesResolvedTarget(artifact, it) }
            is DatabaseToolsTargetResolution.Failed -> null
        }

    private fun revalidate(authority: PreparationSourceAuthority): SourceGraphRevalidationResult =
        StatementSourceGraphRevalidator.revalidateSnapshots(authority.snapshots)
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

internal fun artifactMatchesResolvedTarget(
    artifact: MaterializedExecution,
    target: com.algorist.zMyBatis.core.execution.ResolvedExecutionTarget,
): Boolean =
    artifact.executionTargetId == target.targetId &&
        artifact.executionDialectId == target.effectiveDatabaseId?.value

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

    data class MaterializationFailed(
        val failure: MaterializationFailure,
    ) : LeapExecutionDeliveryFailure

    data class CandidateTargetIdentityMismatch(
        val expected: ExecutionTargetId,
        val actual: ExecutionTargetId?,
    ) : LeapExecutionDeliveryFailure

    data object ArtifactSourceAuthorityMismatch : LeapExecutionDeliveryFailure

    data object TargetSemanticDrift : LeapExecutionDeliveryFailure

    data class ConsoleAcquisitionFailed(
        val failure: DatabaseToolsConsoleAcquisitionFailure,
    ) : LeapExecutionDeliveryFailure

    data class SqlExecutionFailed(
        val failure: DatabaseToolsSqlExecutionFailure,
    ) : LeapExecutionDeliveryFailure
}
