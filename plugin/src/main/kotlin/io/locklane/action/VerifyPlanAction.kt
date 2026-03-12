package io.locklane.action

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import io.locklane.service.ResolverService

class VerifyPlanAction : AnAction("Verify Plan", "Verify the generated plan", AllIcons.Actions.Checked) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val panel = findLockLanePanel(project) ?: return
        val manifest = panel.state.manifestPath ?: return
        val planJson = panel.state.lastPlanJson ?: return

        panel.setBusy(true)

        object : Task.Backgroundable(project, "LockLane: Verifying plan...", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Running verification pipeline..."
                val service = ResolverService.getInstance(project)
                val report = service.runVerifyPlan(manifest, planJson, indicator)
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    panel.setBusy(false)
                    panel.showVerification(report)
                }
            }

            override fun onCancel() {
                panel.setBusy(false)
            }

            override fun onThrowable(error: Throwable) {
                panel.setBusy(false)
                panel.showError("Verification failed", error.message ?: "Unknown error")
            }
        }.queue()
    }

    override fun update(e: AnActionEvent) {
        val panel = e.project?.let { findLockLanePanel(it) }
        e.presentation.isEnabled = panel?.state?.canVerify == true
    }
}
