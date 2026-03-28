package io.locklane.action

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import io.locklane.ui.LockLanePanel
import io.locklane.ui.ManifestTabManager
import java.awt.Container

fun findLockLanePanel(project: Project?): LockLanePanel? {
    return findManifestTabManager(project)?.getActivePanel()
}

fun findManifestTabManager(project: Project?): ManifestTabManager? {
    project ?: return null
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("LockLane") ?: return null
    val content = toolWindow.contentManager.getContent(0) ?: return null
    return findComponent(content.component as? Container)
}

private fun findComponent(container: Container?): ManifestTabManager? {
    container ?: return null
    // The ManifestTabManager is stored as a client property on the wrapper panel
    val manager = (container as? javax.swing.JComponent)?.getClientProperty("ManifestTabManager")
    if (manager is ManifestTabManager) return manager
    for (child in container.components) {
        if (child is Container) {
            val found = findComponent(child)
            if (found != null) return found
        }
    }
    return null
}
