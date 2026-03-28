package io.locklane.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTabbedPane
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.SwingConstants

class ManifestTabManager(private val project: Project) {

    val component: JComponent get() = tabbedPane
    private val tabbedPane = JBTabbedPane(SwingConstants.TOP)
    private val tabPaths = mutableListOf<Path>()
    private val panels = mutableMapOf<Path, LockLanePanel>()

    fun addManifest(path: Path): LockLanePanel {
        panels[path]?.let { existing ->
            val index = tabPaths.indexOf(path)
            if (index >= 0) tabbedPane.selectedIndex = index
            return existing
        }

        val panel = LockLanePanel(project)
        panel.setManifest(path)

        val title = tabTitle(path)
        panels[path] = panel
        tabPaths.add(path)
        tabbedPane.addTab(title, panel)
        val index = tabbedPane.tabCount - 1
        tabbedPane.setToolTipTextAt(index, path.toString())
        tabbedPane.selectedIndex = index
        return panel
    }

    fun removeManifest(path: Path) {
        val index = tabPaths.indexOf(path)
        if (index >= 0) {
            tabbedPane.removeTabAt(index)
            tabPaths.removeAt(index)
            panels.remove(path)
        }
    }

    fun getActivePanel(): LockLanePanel? {
        val index = tabbedPane.selectedIndex
        if (index < 0 || index >= tabPaths.size) return null
        return panels[tabPaths[index]]
    }

    fun getAllPanels(): List<LockLanePanel> = tabPaths.mapNotNull { panels[it] }

    fun getManifestPaths(): List<Path> = tabPaths.toList()

    fun isEmpty(): Boolean = panels.isEmpty()

    private fun tabTitle(path: Path): String {
        val fileName = path.fileName.toString()
        val hasDuplicate = panels.keys.any { it != path && it.fileName.toString() == fileName }
        return if (hasDuplicate) {
            val parent = path.parent?.fileName?.toString() ?: ""
            if (parent.isNotEmpty()) "$parent/$fileName" else fileName
        } else {
            fileName
        }
    }
}
