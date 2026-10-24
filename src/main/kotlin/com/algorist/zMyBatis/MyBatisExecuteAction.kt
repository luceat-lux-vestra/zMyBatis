@file:Suppress("DialogTitleCapitalization", "UnstableApiUsage")

package com.algorist.zMyBatis

import com.algorist.zMyBatis.execution.LeapActionContextAdmissionCoordinator
import com.algorist.zMyBatis.execution.LeapActionContextAdmissionFailure
import com.algorist.zMyBatis.execution.LeapExecutionDeliveryFailure
import com.algorist.zMyBatis.execution.LeapExecutionInvocationFailure
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/**
 * Registered zMyBatis execution action.
 *
 * Keep the IDE edge thin: update() owns only cheap context presentation, while actionPerformed()
 * delegates all supported execution semantics to the Leap admission/application chain.
 */
class MyBatisExecuteAction : AnAction() {
    companion object {
        private val LOG = Logger.getInstance(MyBatisExecuteAction::class.java)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            MyBatisContextAnalyzer.analyze(e) != MyBatisContextAnalyzer.ContextType.NONE
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        when (MyBatisContextAnalyzer.analyze(e)) {
            MyBatisContextAnalyzer.ContextType.NONE -> return
            MyBatisContextAnalyzer.ContextType.PROVIDER -> {
                LeapActionFailurePresenter.presentUnsupportedProvider(project)
                return
            }
            else -> Unit
        }

        LeapActionContextAdmissionCoordinator(project).execute(
            event = e,
            onExecuted = {},
            onCancelled = {},
            onFailure = { failure ->
                if (failure is LeapActionContextAdmissionFailure.InvocationFailed) {
                    LOG.warn(
                        "zMyBatis: Leap invocation refused " +
                            "(stage=${leapInvocationFailureDiagnosticCode(failure.failure)})",
                    )
                }
                LeapActionFailurePresenter.present(project, failure)
            },
        )
    }
}

private fun leapInvocationFailureDiagnosticCode(
    failure: LeapExecutionInvocationFailure,
): String =
    when (failure) {
        LeapExecutionInvocationFailure.InvocationInvalidated ->
            "invocation-invalidated"
        is LeapExecutionInvocationFailure.InputContractBlocked ->
            "input-contract-blocked:" + failure.problemCodes.sorted().joinToString(",")
        LeapExecutionInvocationFailure.MissingPersistedTarget ->
            "missing-persisted-target"
        LeapExecutionInvocationFailure.PersistedTargetChanged ->
            "persisted-target-changed"
        is LeapExecutionInvocationFailure.DeliveryFailed ->
            "delivery-failed:" + leapDeliveryFailureDiagnosticCode(failure.failure)
    }

private fun leapDeliveryFailureDiagnosticCode(
    failure: LeapExecutionDeliveryFailure,
): String =
    when (failure) {
        LeapExecutionDeliveryFailure.InvocationInvalidated ->
            "invocation-invalidated"
        is LeapExecutionDeliveryFailure.SourceInvalid ->
            "source-invalid:" + failure.failure.javaClass.simpleName
        is LeapExecutionDeliveryFailure.TargetResolutionFailed ->
            "target-resolution-failed:" + failure.failure.javaClass.simpleName
        is LeapExecutionDeliveryFailure.PreparationFailed ->
            "preparation-failed:" +
                failure.failure.kind.name.lowercase().replace('_', '-') +
                ":" +
                failure.failure.code
        is LeapExecutionDeliveryFailure.MaterializationFailed ->
            "materialization-failed:" + failure.failure.javaClass.simpleName
        is LeapExecutionDeliveryFailure.CandidateTargetIdentityMismatch ->
            "candidate-target-identity-mismatch"
        LeapExecutionDeliveryFailure.ArtifactSourceAuthorityMismatch ->
            "artifact-source-authority-mismatch"
        LeapExecutionDeliveryFailure.TargetSemanticDrift ->
            "target-semantic-drift"
        is LeapExecutionDeliveryFailure.ConsoleAcquisitionFailed ->
            "console-acquisition-failed:" + failure.failure.javaClass.simpleName
        is LeapExecutionDeliveryFailure.SqlExecutionFailed ->
            "sql-execution-failed:" + failure.failure.javaClass.simpleName
    }

private object LeapActionFailurePresenter {
    fun presentUnsupportedProvider(project: Project) {
        show(
            project = project,
            presentation = FailurePresentation(
                title = "zMyBatis: Provider Not Supported",
                message = "@SelectProvider / @InsertProvider / @UpdateProvider / @DeleteProvider " +
                    "generate SQL dynamically at runtime. Run zMyBatis from a supported XML mapper " +
                    "or direct Java statement annotation instead.",
            ),
            error = false,
        )
    }

    fun present(
        project: Project,
        failure: LeapActionContextAdmissionFailure,
    ) {
        val presentation = when (failure) {
            LeapActionContextAdmissionFailure.ProjectMismatch,
            LeapActionContextAdmissionFailure.InvocationInvalidated,
            LeapActionContextAdmissionFailure.MissingEditor,
            LeapActionContextAdmissionFailure.MissingPsiFile,
            -> FailurePresentation(
                title = "zMyBatis: Execution Context Unavailable",
                message = "The current IDE execution context is no longer valid. Run zMyBatis again from the mapper statement.",
            )

            is LeapActionContextAdmissionFailure.SourceCaptureFailed,
            is LeapActionContextAdmissionFailure.SourceSelectionFailed,
            is LeapActionContextAdmissionFailure.SourceInvalid,
            -> FailurePresentation(
                title = "zMyBatis: Mapper Source Not Executable",
                message = "The current mapper source could not be proven executable. Refresh the source context and run zMyBatis again.",
            )

            is LeapActionContextAdmissionFailure.TargetAdmissionFailed,
            LeapActionContextAdmissionFailure.TargetChanged,
            -> FailurePresentation(
                title = "zMyBatis: Execution Target Unavailable",
                message = "The configured datasource or explicit schema is unavailable or changed. Select the execution target again.",
            )

            is LeapActionContextAdmissionFailure.InvocationFailed ->
                FailurePresentation(
                    title = "zMyBatis: Execution Refused",
                    message = "zMyBatis could not complete this statement through the supported execution path. Review the mapper, inputs, and target, then try again.",
                )
        }

        show(project, presentation, error = true)
    }

    private fun show(
        project: Project,
        presentation: FailurePresentation,
        error: Boolean,
    ) {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            if (error) {
                Messages.showErrorDialog(project, presentation.message, presentation.title)
            } else {
                Messages.showInfoMessage(project, presentation.message, presentation.title)
            }
        }, ModalityState.any())
    }
}

private data class FailurePresentation(
    val title: String,
    val message: String,
)
