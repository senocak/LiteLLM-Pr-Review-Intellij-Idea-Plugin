package com.github.senocak.prreview

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Must match the `toolWindow` id in plugin.xml exactly: it is also the place the panel's
 * `ActionToolbar` is registered under, and a mismatch fails silently at runtime rather than at
 * build time.
 */
const val PR_TOOL_WINDOW_ID = "LiteLLM PR Review"

class PRToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = PRMainPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        // The tool window is not closeable and is reused for the life of the project, but the
        // content can still be removed and recreated; tying the panel's lifetime to the content
        // keeps a re-open from accumulating panels that each hold a listener on the list.
        Disposer.register(content, panel)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
    }
}
