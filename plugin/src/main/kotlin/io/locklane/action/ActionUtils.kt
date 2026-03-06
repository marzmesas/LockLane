package io.locklane.action

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import io.locklane.ui.LocklanePanel
import java.awt.Container

fun findLocklanePanel(project: Project?): LocklanePanel? {
    project ?: return null
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Locklane") ?: return null
    val content = toolWindow.contentManager.getContent(0) ?: return null
    return findPanel(content.component as? Container)
}

private fun findPanel(container: Container?): LocklanePanel? {
    container ?: return null
    if (container is LocklanePanel) return container
    for (child in container.components) {
        if (child is LocklanePanel) return child
        if (child is Container) {
            val found = findPanel(child)
            if (found != null) return found
        }
    }
    return null
}
