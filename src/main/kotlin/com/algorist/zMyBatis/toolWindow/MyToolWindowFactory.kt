package com.github.luceatluxvestra.zmybatisprivate.toolWindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

class MyToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) { /* unused */ }
    override fun shouldBeAvailable(project: Project) = false
}
