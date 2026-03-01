package com.algorist.zMyBatis.startup

import com.algorist.zMyBatis.MyBatisExecuteProxyAction
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.impl.ActionManagerImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity


class MyBatisActionInterceptorActivity : ProjectActivity {

    // Target actions to replace
    private val targetActions: Array<String> = arrayOf<String>(
        "Console.Jdbc.ExplainPlan",                       // Explain Plan
        "Console.Jdbc.ExplainPlan.Raw",                   // Explain Plan (Raw)
        "Console.Jdbc.ExplainAnalyse",                    // Explain Analyse
        "Console.Jdbc.ExplainAnalyse.Raw",                // Explain Analyse (Raw)
        "Console.Jdbc.Execute",                           // Execute Query
        "Console.TableResult.ShowDumpDialogAction"        // Export Result
    )

    override suspend fun execute(project: Project) {
        val actionManager = ActionManager.getInstance() as ActionManagerImpl
        for (actionId in targetActions) {
            val originalAction: AnAction? = actionManager.getAction(actionId)
            if (originalAction != null && originalAction !is MyBatisExecuteProxyAction) {
                actionManager.replaceAction(actionId, MyBatisExecuteProxyAction(originalAction))
            }
        }
    }
}