@file:Suppress("DialogTitleCapitalization")

package com.algorist.zMyBatis

import com.algorist.zMyBatis.MyBatisContextAnalyzer.analyze
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
 * The action owns only bounded context admission and IDE presentation. Source/input/MyBatis/
 * materialization/target/console semantics live behind [LeapActionContextAdmissionCoordinator].
 */
open class MyBatisExecuteProxyAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            analyze(e) != MyBatisContextAnalyzer.ContextType.NONE
    }

    override fun actionPerformed(e: AnActionEvent) {
        if (analyze(e) == MyBatisContextAnalyzer.ContextType.NONE) return
        val project = e.project ?: return
        if (project.isDisposed) return

        LeapActionContextAdmissionCoordinator(project).execute(
            event = e,
            onExecuted = {},
            onCancelled = {},
            onFailure = { failure -> presentFailure(project, failure) },
        )
    }

    private fun presentFailure(
        project: Project,
        failure: LeapActionContextAdmissionFailure,
    ) {
        val presentation = leapActionFailurePresentation(failure)
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            when (presentation.severity) {
                LeapActionFailureSeverity.INFORMATION ->
                    Messages.showInfoMessage(project, presentation.message, presentation.title)
                LeapActionFailureSeverity.ERROR ->
                    Messages.showErrorDialog(project, presentation.message, presentation.title)
            }
        }, ModalityState.any())
    }
}

class MyBatisExecuteAction : MyBatisExecuteProxyAction()
