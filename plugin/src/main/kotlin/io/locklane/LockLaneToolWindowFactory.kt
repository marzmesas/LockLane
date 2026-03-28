package io.locklane

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import io.locklane.action.ApplyPlanAction
import io.locklane.action.RollbackHistoryAction
import io.locklane.action.RunBaselineAction
import io.locklane.action.RunPlanAction
import io.locklane.action.SelectManifestAction
import io.locklane.action.VerifyPlanAction
import io.locklane.activity.AutoScanActivity
import io.locklane.service.LockLaneProjectState
import io.locklane.settings.LockLaneSettings
import io.locklane.ui.ManifestTabManager
import java.awt.BorderLayout
import java.io.File
import java.nio.file.Path
import javax.swing.JPanel

class LockLaneToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val tabManager = ManifestTabManager(project)

        val actionGroup = DefaultActionGroup().apply {
            add(SelectManifestAction())
            add(Separator())
            add(RunBaselineAction())
            add(RunPlanAction())
            add(VerifyPlanAction())
            add(ApplyPlanAction())
            add(Separator())
            add(RollbackHistoryAction())
        }

        val toolbar = ActionManager.getInstance()
            .createActionToolbar("LockLaneToolbar", actionGroup, true)
        toolbar.targetComponent = tabManager.component

        val wrapper = JPanel(BorderLayout()).apply {
            putClientProperty("ManifestTabManager", tabManager)
            add(toolbar.component, BorderLayout.NORTH)
            add(tabManager.component, BorderLayout.CENTER)
        }

        val content = ContentFactory.getInstance().createContent(wrapper, "", false)
        toolWindow.contentManager.addContent(content)

        // Try to populate from startup scan cache
        val projectState = LockLaneProjectState.getInstance(project)
        if (projectState.manifests.isNotEmpty()) {
            for ((path, state) in projectState.manifests) {
                val panel = tabManager.addManifest(path)
                val plan = state.plan
                val jsonPath = state.planJson
                if (plan != null && jsonPath != null) {
                    panel.showPlan(plan, jsonPath)
                    state.audit?.let { panel.updateVulnerabilities(it) }
                    state.enrich?.let { panel.updateLinks(it) }
                }
            }
        } else {
            // Try persisted manifests from previous session
            val persisted = LockLaneSettings.getInstance(project).state.lastManifestPaths
            val validPaths = persisted.filter { Path.of(it).toFile().isFile }
            if (validPaths.isNotEmpty()) {
                for (pathStr in validPaths) {
                    tabManager.addManifest(Path.of(pathStr))
                }
            } else {
                autoDetectManifests(project, tabManager)
            }
        }
    }

    private fun autoDetectManifests(project: Project, tabManager: ManifestTabManager) {
        val basePath = project.basePath ?: return
        val candidates = AutoScanActivity.MANIFEST_NAMES.mapNotNull { name ->
            val file = File(basePath, name)
            if (file.isFile) file else null
        }
        if (candidates.isEmpty()) return

        for (candidate in candidates) {
            tabManager.addManifest(candidate.toPath())
        }
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
