package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.JavaAnnotationParameterContractFactory
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.input.XmlMapperMethodParameterContractFactory
import com.algorist.zMyBatis.core.input.XmlStatementParameterContractFactory
import com.algorist.zMyBatis.core.preparation.PreparationSource
import com.algorist.zMyBatis.core.preparation.XmlMapperPreparationSource
import com.algorist.zMyBatis.input.ContractParameterInputDialog
import com.algorist.zMyBatis.services.ExecutionTargetDescriptorStore
import com.algorist.zMyBatis.settings.ConsoleSessionPolicy
import com.algorist.zMyBatis.settings.ZMyBatisSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project

/**
 * Immutable policy snapshot taken once at the IDE invocation edge.
 *
 * Downstream coordinators receive explicit values and never re-read application settings.
 */
internal data class LeapExecutionInvocationPolicy(
    val approvalOptions: LeapExecutionApprovalOptions,
    val forceNewConsole: Boolean,
)

internal fun leapExecutionInvocationPolicy(
    showSqlPreview: Boolean,
    formatPreviewSql: Boolean,
    consoleSessionPolicy: ConsoleSessionPolicy,
): LeapExecutionInvocationPolicy =
    LeapExecutionInvocationPolicy(
        approvalOptions = LeapExecutionApprovalOptions(
            showSqlPreview = showSqlPreview,
            formatPreviewSql = formatPreviewSql,
        ),
        forceNewConsole = consoleSessionPolicy == ConsoleSessionPolicy.NEW_EACH,
    )

/**
 * Derives the only accepted parameter contract from the immutable preparation source.
 *
 * Callers cannot pair one source with an independently assembled contract.
 */
internal fun leapExecutionParameterContract(source: PreparationSource): ParameterContract =
    when (source) {
        is PreparationSource.JavaAnnotation ->
            JavaAnnotationParameterContractFactory.build(source.capture)
        is XmlMapperPreparationSource ->
            source.mapperMethod?.let { mapperMethod ->
                XmlMapperMethodParameterContractFactory.build(source.sourceGraph, mapperMethod)
            } ?: XmlStatementParameterContractFactory.build(source.sourceGraph)
    }

internal sealed interface LeapExecutionInputResult {
    data class Accepted(val environment: InputEnvironment) : LeapExecutionInputResult
    data object UserCancelled : LeapExecutionInputResult
    data object InvocationInvalidated : LeapExecutionInputResult
}

/**
 * Modal-boundary kernel shared by the IDE adapter and focused tests.
 *
 * A successful dialog may already have persisted explicitly retained input by the time
 * [showAndRead] returns. A later invalidation therefore stops execution but does not roll back
 * accepted input history.
 */
internal fun runLeapExecutionInput(
    isInvocationActive: () -> Boolean,
    showAndRead: () -> InputEnvironment?,
): LeapExecutionInputResult {
    if (!isInvocationActive()) {
        return LeapExecutionInputResult.InvocationInvalidated
    }

    val environment = showAndRead() ?: return LeapExecutionInputResult.UserCancelled
    return if (isInvocationActive()) {
        LeapExecutionInputResult.Accepted(environment)
    } else {
        LeapExecutionInputResult.InvocationInvalidated
    }
}

internal class LeapExecutionInputAdapter(private val project: Project) {
    fun requestInput(
        contract: ParameterContract,
        isInvocationActive: () -> Boolean,
        onResult: (LeapExecutionInputResult) -> Unit,
    ) {
        val liveInvocation = { !project.isDisposed && isInvocationActive() }
        ApplicationManager.getApplication().invokeLater({
            onResult(
                runLeapExecutionInput(
                    isInvocationActive = liveInvocation,
                    showAndRead = {
                        val dialog = ContractParameterInputDialog(project, contract)
                        if (dialog.showAndGet()) dialog.inputEnvironment() else null
                    },
                ),
            )
        }, ModalityState.any())
    }
}

internal sealed interface LeapExecutionInvocationFailure {
    data object InvocationInvalidated : LeapExecutionInvocationFailure

    data class InputContractBlocked(
        val problemCodes: List<String>,
    ) : LeapExecutionInvocationFailure

    data object MissingPersistedTarget : LeapExecutionInvocationFailure
    data object PersistedTargetChanged : LeapExecutionInvocationFailure

    data class DeliveryFailed(
        val failure: LeapExecutionDeliveryFailure,
    ) : LeapExecutionInvocationFailure
}

/**
 * Non-shipping production-orchestration seam for #66.
 *
 * Source capture and missing-target chooser ownership remain outside this slice. Given one immutable
 * preparation source, this coordinator derives its matching input contract, snapshots one persisted
 * exact target and explicit user policy, crosses the contract input modal, rechecks target/lifecycle
 * authority, and then delegates the exact accepted environment to the existing delivery coordinator.
 */
@Suppress("unused")
internal class LeapExecutionInvocationCoordinator(private val project: Project) {
    private val targetStore = ExecutionTargetDescriptorStore.getInstance(project)
    private val inputAdapter = LeapExecutionInputAdapter(project)
    private val deliveryCoordinator = LeapExecutionDeliveryCoordinator(project)

    fun execute(
        source: PreparationSource,
        isInvocationActive: () -> Boolean,
        onExecuted: () -> Unit,
        onCancelled: () -> Unit,
        onFailure: (LeapExecutionInvocationFailure) -> Unit,
    ) {
        val liveInvocation = { !project.isDisposed && isInvocationActive() }
        if (!liveInvocation()) {
            onFailure(LeapExecutionInvocationFailure.InvocationInvalidated)
            return
        }

        val parameterContract = leapExecutionParameterContract(source)
        if (parameterContract.isPreparationBlocked) {
            onFailure(
                LeapExecutionInvocationFailure.InputContractBlocked(
                    parameterContract.blockingProblems.map { it.code },
                ),
            )
            return
        }

        val sourceFileId = source.sourceGraph.rootStatement.id.sourceFileId
        val persistedTarget = targetStore.load(sourceFileId)
            ?: run {
                onFailure(LeapExecutionInvocationFailure.MissingPersistedTarget)
                return
            }
        val targetDescriptor = persistedTarget.descriptor

        val settings = ZMyBatisSettings.getInstance()
        val policy = leapExecutionInvocationPolicy(
            showSqlPreview = settings.sqlPreview,
            formatPreviewSql = settings.autoFormatSql,
            consoleSessionPolicy = settings.consoleSessionPolicy,
        )

        inputAdapter.requestInput(
            contract = parameterContract,
            isInvocationActive = liveInvocation,
            onResult = { input ->
                when (input) {
                    is LeapExecutionInputResult.Accepted -> {
                        val currentTarget = targetStore.load(sourceFileId)
                        if (currentTarget?.descriptor != targetDescriptor) {
                            onFailure(LeapExecutionInvocationFailure.PersistedTargetChanged)
                        } else {
                            deliveryCoordinator.execute(
                                source = source,
                                parameterContract = parameterContract,
                                inputEnvironment = input.environment,
                                targetDescriptor = targetDescriptor,
                                resourceKey = sourceFileId.value,
                                forceNewConsole = policy.forceNewConsole,
                                approvalOptions = policy.approvalOptions,
                                isInvocationActive = liveInvocation,
                                onExecuted = onExecuted,
                                onCancelled = onCancelled,
                                onFailure = { failure ->
                                    onFailure(LeapExecutionInvocationFailure.DeliveryFailed(failure))
                                },
                            )
                        }
                    }
                    LeapExecutionInputResult.UserCancelled -> onCancelled()
                    LeapExecutionInputResult.InvocationInvalidated ->
                        onFailure(LeapExecutionInvocationFailure.InvocationInvalidated)
                }
            },
        )
    }
}
