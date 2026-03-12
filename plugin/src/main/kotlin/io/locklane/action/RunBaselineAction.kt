package io.locklane.action

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import io.locklane.service.ResolverService

class RunBaselineAction : AnAction("Baseline", "Show current dependency versions", AllIcons.Actions.ListFiles) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val panel = findLockLanePanel(project) ?: return
        val manifest = panel.state.manifestPath ?: return

        panel.setBusy(true)

        object : Task.Backgroundable(project, "LockLane: Reading baseline...", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Parsing dependencies and resolving versions..."
                val service = ResolverService.getInstance(project)
                val baseline = service.runBaseline(manifest, indicator)
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    panel.setBusy(false)
                    panel.showBaseline(baseline)
                }
            }

            override fun onCancel() {
                panel.setBusy(false)
            }

            override fun onThrowable(error: Throwable) {
                panel.setBusy(false)
                panel.showError("Baseline failed", error.message ?: "Unknown error")
            }
        }.queue()
    }

    override fun update(e: AnActionEvent) {
        val panel = e.project?.let { findLockLanePanel(it) }
        e.presentation.isEnabled = panel?.state?.canRunPlan == true
    }
}
