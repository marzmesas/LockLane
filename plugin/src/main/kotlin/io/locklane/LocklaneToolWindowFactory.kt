package io.locklane

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import io.locklane.action.ApplyPlanAction
import io.locklane.action.RollbackHistoryAction
import io.locklane.action.RunPlanAction
import io.locklane.action.SelectManifestAction
import io.locklane.action.VerifyPlanAction
import io.locklane.activity.AutoScanActivity
import io.locklane.service.LocklaneProjectState
import io.locklane.settings.LocklaneSettings
import io.locklane.ui.LocklanePanel
import java.awt.BorderLayout
import java.io.File
import java.nio.file.Path
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.SwingUtilities

class LocklaneToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = LocklanePanel(project)

        val actionGroup = DefaultActionGroup().apply {
            add(SelectManifestAction())
            add(Separator())
            add(RunPlanAction())
            add(VerifyPlanAction())
            add(ApplyPlanAction())
            add(Separator())
            add(RollbackHistoryAction())
        }

        val toolbar = ActionManager.getInstance()
            .createActionToolbar("LocklaneToolbar", actionGroup, true)
        toolbar.targetComponent = panel

        val wrapper = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(panel, BorderLayout.CENTER)
        }

        val content = ContentFactory.getInstance().createContent(wrapper, "", false)
        toolWindow.contentManager.addContent(content)

        // Check if auto-scan already ran and pre-populate the panel
        val projectState = LocklaneProjectState.getInstance(project)
        if (projectState.lastPlan != null && projectState.manifestPath != null) {
            panel.setManifest(projectState.manifestPath!!)
            panel.showPlan(projectState.lastPlan!!, projectState.lastPlanJson!!)
        } else {
            // Try persisted manifest from previous session
            val persisted = LocklaneSettings.getInstance(project).state.lastManifestPath
            if (persisted.isNotBlank() && Path.of(persisted).toFile().isFile) {
                panel.setManifest(Path.of(persisted))
            } else {
                autoDetectManifest(project, panel)
            }
        }
    }

    private fun autoDetectManifest(project: Project, panel: LocklanePanel) {
        val basePath = project.basePath ?: return
        val candidates = AutoScanActivity.MANIFEST_NAMES.mapNotNull { name ->
            val file = File(basePath, name)
            if (file.isFile) file else null
        }
        if (candidates.isEmpty()) return

        ApplicationManager.getApplication().invokeLater {
            val manifest = if (candidates.size == 1) {
                val answer = JOptionPane.showConfirmDialog(
                    SwingUtilities.getWindowAncestor(panel),
                    "Found ${candidates.first().name} in the project root. Use it as the manifest?",
                    "Locklane — Manifest Detected",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE,
                )
                if (answer == JOptionPane.YES_OPTION) candidates.first() else null
            } else {
                val options = candidates.map { it.name }.toTypedArray()
                val choice = JOptionPane.showInputDialog(
                    SwingUtilities.getWindowAncestor(panel),
                    "Multiple manifest files found. Choose one:",
                    "Locklane — Select Manifest",
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    options,
                    options.first(),
                )
                if (choice != null) candidates.first { it.name == choice } else null
            }

            if (manifest != null) {
                panel.setManifest(manifest.toPath())
            }
        }
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
