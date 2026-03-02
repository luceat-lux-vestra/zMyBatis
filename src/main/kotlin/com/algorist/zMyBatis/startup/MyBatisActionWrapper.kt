package com.algorist.zMyBatis.startup

import com.algorist.zMyBatis.MyBatisContextAnalyzer
import com.algorist.zMyBatis.MyBatisContextAnalyzer.analyze
import com.algorist.zMyBatis.MyBatisExecuteProxyAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ShortcutSet
import com.intellij.openapi.diagnostic.Logger

/**
 * Wraps one of the target Database/Console actions and intercepts its execution
 * when the caret is inside a MyBatis statement context.
 *
 * Instances are registered via [ActionManager.replaceAction] during plugin startup
 * (see [MyBatisActionInterceptorActivity]) so that IntelliJ routes the original
 * action ID through this wrapper instead.
 */
class MyBatisActionWrapper(val delegate: AnAction) : AnAction() {

    companion object {
        private val LOG = Logger.getInstance(MyBatisActionWrapper::class.java)
    }

    init {
        copyFrom(delegate)
    }

    /** Prevent IntelliJ from clobbering the shortcut set copied from the delegate. */
    override fun setShortcutSet(shortcutSet: ShortcutSet) {
        // Intentionally left blank
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        delegate.update(e)
        if (analyze(e) != MyBatisContextAnalyzer.ContextType.NONE) {
            e.presentation.isEnabledAndVisible = true
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val context = analyze(e)
        if (context != MyBatisContextAnalyzer.ContextType.NONE) {
            LOG.info("zMyBatis: intercepting action via wrapper (context=$context)")
            MyBatisExecuteProxyAction(delegate).actionPerformed(e)
        } else {
            delegate.actionPerformed(e)
        }
    }
}

