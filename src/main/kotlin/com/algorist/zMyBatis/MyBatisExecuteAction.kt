@file:Suppress("DialogTitleCapitalization", "UnstableApiUsage")

package com.algorist.zMyBatis

import com.algorist.zMyBatis.execution.LeapActionContextAdmissionCoordinator
import com.algorist.zMyBatis.execution.LeapActionContextAdmissionFailure
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/**
 * Registered zMyBatis execution action.
 *
 * Keep the IDE edge thin: update() owns only cheap context presentation, while actionPerformed()
 * delegates all supported execution semantics to the Leap admission/application chain.
 */
class MyBatisExecuteAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            MyBatisContextAnalyzer.analyze(e) != MyBatisContextAnalyzer.ContextType.NONE
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        LeapActionContextAdmissionCoordinator(project).execute(
            event = e,
            onExecuted = {},
            onCancelled = {},
            onFailure = { failure ->
                LeapActionFailurePresenter.present(project, failure)
            },
        )
    }
}

private object LeapActionFailurePresenter {
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

        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            Messages.showErrorDialog(
                project,
                presentation.message,
                presentation.title,
            )
        }, ModalityState.any())
    }
}

private data class FailurePresentation(
    val title: String,
    val message: String,
)
