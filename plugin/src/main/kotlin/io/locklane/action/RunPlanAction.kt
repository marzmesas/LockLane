package io.locklane.action

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import io.locklane.service.ResolverService
import java.nio.file.Files

class RunPlanAction : AnAction("Run Plan", "Generate an upgrade plan", AllIcons.Actions.Execute) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val panel = findLocklanePanel(project) ?: return
        val manifest = panel.state.manifestPath ?: return

        panel.setBusy(true)

        object : Task.Backgroundable(project, "Locklane: Generating plan...", true) {
            override fun run(indicator: ProgressIndicator) {
                val service = ResolverService.getInstance(project)
                val (plan, rawJson) = service.runPlanRaw(manifest)
                val tempFile = Files.createTempFile("locklane-plan-", ".json")
                Files.writeString(tempFile, rawJson)
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    panel.setBusy(false)
                    panel.showPlan(plan, tempFile)
                }
            }

            override fun onCancel() {
                panel.setBusy(false)
            }

            override fun onThrowable(error: Throwable) {
                panel.setBusy(false)
                panel.showError("Plan failed", error.message ?: "Unknown error")
            }
        }.queue()
    }

    override fun update(e: AnActionEvent) {
        val panel = e.project?.let { findLocklanePanel(it) }
        e.presentation.isEnabled = panel?.state?.canRunPlan == true
    }
}
