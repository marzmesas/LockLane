package io.locklane.action

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import io.locklane.ui.LockLanePanel
import java.awt.Container

fun findLockLanePanel(project: Project?): LockLanePanel? {
    project ?: return null
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("LockLane") ?: return null
    val content = toolWindow.contentManager.getContent(0) ?: return null
    return findPanel(content.component as? Container)
}

private fun findPanel(container: Container?): LockLanePanel? {
    container ?: return null
    if (container is LockLanePanel) return container
    for (child in container.components) {
        if (child is LockLanePanel) return child
        if (child is Container) {
            val found = findPanel(child)
            if (found != null) return found
        }
    }
    return null
}
