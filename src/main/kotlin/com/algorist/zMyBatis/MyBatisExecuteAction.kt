@file:Suppress("DialogTitleCapitalization", "UnstableApiUsage")

package com.algorist.zMyBatis

import com.algorist.zMyBatis.execution.DatabaseToolsConsoleAcquisitionFailure
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
            MyBatisContextAnalyzer.ContextType.NONE -> {
                LOG.warn("zMyBatis: execution refused (stage=context-unavailable)")
                LeapActionFailurePresenter.presentContextUnavailable(project)
                return
            }
            MyBatisContextAnalyzer.ContextType.PROVIDER -> {
                LeapActionFailurePresenter.presentUnsupportedProvider(project)
                return
            }
            else -> Unit
        }

        LeapActionContextAdmissionCoordinator(project).execute(
            event = e,
            onExecuted = {},
            onCancelled = { LOG.info("zMyBatis: execution cancelled by user") },
            onFailure = { failure ->
                LOG.warn(
                    "zMyBatis: execution refused " +
                        "(stage=${leapActionFailureDiagnosticCode(failure)})",
                )
                LeapActionFailurePresenter.present(project, failure)
            },
        )
    }
}

private fun leapActionFailureDiagnosticCode(
    failure: LeapActionContextAdmissionFailure,
): String =
    when (failure) {
        LeapActionContextAdmissionFailure.ProjectMismatch ->
            "project-mismatch"
        LeapActionContextAdmissionFailure.InvocationInvalidated ->
            "invocation-invalidated"
        LeapActionContextAdmissionFailure.MissingEditor ->
            "missing-editor"
        LeapActionContextAdmissionFailure.MissingPsiFile ->
            "missing-psi-file"
        is LeapActionContextAdmissionFailure.SourceCaptureFailed ->
            "source-capture-failed:" + failure.failure.javaClass.simpleName
        is LeapActionContextAdmissionFailure.SourceSelectionFailed ->
            "source-selection-failed:" + failure.failure.javaClass.simpleName
        LeapActionContextAdmissionFailure.SourceSelectionCancelled ->
            "source-selection-cancelled"
        LeapActionContextAdmissionFailure.ProjectXmlExpansionCancelled ->
            "project-xml-expansion-cancelled"
        is LeapActionContextAdmissionFailure.SourceSelectionTaskFailed ->
            "source-selection-task-failed:" + failure.exceptionClass.name
        is LeapActionContextAdmissionFailure.ProjectXmlExpansionTaskFailed ->
            "project-xml-expansion-task-failed:" + failure.exceptionClass.name
        is LeapActionContextAdmissionFailure.SourceInvalid ->
            "source-invalid:" + failure.failure.javaClass.simpleName
        is LeapActionContextAdmissionFailure.TargetAdmissionFailed ->
            "target-admission-failed:" + failure.failure.javaClass.simpleName
        LeapActionContextAdmissionFailure.TargetChanged ->
            "target-changed"
        is LeapActionContextAdmissionFailure.InvocationFailed ->
            "invocation-failed:" + leapInvocationFailureDiagnosticCode(failure.failure)
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
        LeapExecutionDeliveryFailure.CandidatePreparationCancelled ->
            "candidate-preparation-cancelled"
        is LeapExecutionDeliveryFailure.SourceInvalid ->
            "source-invalid:" + failure.failure.javaClass.simpleName
        is LeapExecutionDeliveryFailure.TargetResolutionFailed ->
            "target-resolution-failed:" + failure.failure.javaClass.simpleName
        is LeapExecutionDeliveryFailure.PreparationFailed ->
            "preparation-failed:" +
                failure.failure.kind.name.lowercase().replace('_', '-') +
                ":" +
                failure.failure.code
        is LeapExecutionDeliveryFailure.PlanFailed ->
            "plan-failed:" +
                failure.failure.kind.name.lowercase().replace('_', '-') +
                ":" +
                failure.failure.code
        LeapExecutionDeliveryFailure.PlanSourceAuthorityMismatch ->
            "plan-source-authority-mismatch"
        LeapExecutionDeliveryFailure.TargetSemanticDrift ->
            "target-semantic-drift"
        is LeapExecutionDeliveryFailure.MaterializationFailed ->
            "materialization-failed:" +
                failure.failure.kind.name.lowercase().replace('_', '-') +
                ":" +
                failure.failure.code
        is LeapExecutionDeliveryFailure.ConsoleAcquisitionFailed ->
            "console-acquisition-failed:" + failure.failure.javaClass.simpleName
        is LeapExecutionDeliveryFailure.NativeExecutionFailed ->
            "native-execution-failed:" + failure.failure.javaClass.simpleName
        is LeapExecutionDeliveryFailure.PreparedExecutionFailed ->
            "prepared-execution-failed:" +
                failure.failure.kind.name.lowercase().replace('_', '-') +
                ":" +
                failure.failure.code
    }

private object LeapActionFailurePresenter {
    fun presentContextUnavailable(project: Project) {
        show(
            project = project,
            presentation = FailurePresentation(
                title = "zMyBatis: Execution Context Unavailable",
                message = "zMyBatis was invoked without a valid mapper statement context. " +
                    "Place the caret inside the mapper statement and run the action again.",
            ),
            error = true,
        )
    }

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
        val preparedAlreadyPresented =
            (failure as? LeapActionContextAdmissionFailure.InvocationFailed)
                ?.failure
                ?.let { it as? LeapExecutionInvocationFailure.DeliveryFailed }
                ?.failure is LeapExecutionDeliveryFailure.PreparedExecutionFailed
        if (preparedAlreadyPresented) {
            return
        }

        val presentation = when (failure) {
            LeapActionContextAdmissionFailure.ProjectMismatch,
            LeapActionContextAdmissionFailure.InvocationInvalidated,
            LeapActionContextAdmissionFailure.MissingEditor,
            LeapActionContextAdmissionFailure.MissingPsiFile,
            -> FailurePresentation(
                title = "zMyBatis: Execution Context Unavailable",
                message = "The current IDE execution context is no longer valid. Run zMyBatis again from the mapper statement.",
            )

            LeapActionContextAdmissionFailure.SourceSelectionCancelled,
            LeapActionContextAdmissionFailure.ProjectXmlExpansionCancelled,
            is LeapActionContextAdmissionFailure.SourceSelectionTaskFailed,
            is LeapActionContextAdmissionFailure.ProjectXmlExpansionTaskFailed,
            -> FailurePresentation(
                title = "zMyBatis: Mapper Source Resolution Interrupted",
                message = "Mapper source resolution stopped before execution. " +
                    "No SQL was executed. Run zMyBatis again; if this repeats, inspect the IDE log for the recorded stage.",
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

            is LeapActionContextAdmissionFailure.InvocationFailed -> {
                val consoleFailure =
                    (failure.failure as? LeapExecutionInvocationFailure.DeliveryFailed)
                        ?.failure
                        ?.let { it as? LeapExecutionDeliveryFailure.ConsoleAcquisitionFailed }
                        ?.failure
                when (consoleFailure) {
                    DatabaseToolsConsoleAcquisitionFailure.ConsoleCreationFailed ->
                        FailurePresentation(
                            title = "zMyBatis: Database Console Unavailable",
                            message = "zMyBatis could not create a Database Tools console for the selected datasource. " +
                                "Check the datasource/session state and run the mapper again.",
                        )
                    DatabaseToolsConsoleAcquisitionFailure.ConsoleSelectionUnavailable ->
                        FailurePresentation(
                            title = "zMyBatis: Database Console Selection Unavailable",
                            message = "zMyBatis could not present the Database Tools console chooser for this execution. " +
                                "Run the mapper again from an active editor.",
                        )
                    else ->
                        FailurePresentation(
                            title = "zMyBatis: Execution Refused",
                            message = "zMyBatis could not complete this statement through the supported execution path. Review the mapper, inputs, and target, then try again.",
                        )
                }
            }
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
