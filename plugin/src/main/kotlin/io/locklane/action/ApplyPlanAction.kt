package io.locklane.action

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.vfs.LocalFileSystem
import io.locklane.service.ResolverService

class ApplyPlanAction : AnAction("Apply Plan", "Apply the plan (dry-run first)", AllIcons.Actions.Download) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val panel = findLocklanePanel(project) ?: return
        val manifest = panel.state.manifestPath ?: return
        val planJson = panel.state.lastPlanJson ?: return

        panel.setBusy(true)

        object : Task.Backgroundable(project, "Locklane: Applying plan (dry-run)...", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Generating patch preview..."
                val service = ResolverService.getInstance(project)
                val result = service.runApply(manifest, planJson, dryRun = true, indicator = indicator)
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    panel.setBusy(false)
                    panel.showApply(result) {
                        applyForReal(project, panel, manifest, planJson)
                    }
                }
            }

            override fun onCancel() {
                panel.setBusy(false)
            }

            override fun onThrowable(error: Throwable) {
                panel.setBusy(false)
                panel.showError("Apply failed", error.message ?: "Unknown error")
            }
        }.queue()
    }

    private fun applyForReal(
        project: com.intellij.openapi.project.Project,
        panel: io.locklane.ui.LocklanePanel,
        manifest: java.nio.file.Path,
        planJson: java.nio.file.Path,
    ) {
        panel.setBusy(true)

        object : Task.Backgroundable(project, "Locklane: Applying plan...", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Applying updates to manifest..."
                val service = ResolverService.getInstance(project)
                val result = service.runApply(manifest, planJson, dryRun = false, indicator = indicator)
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    panel.setBusy(false)
                    panel.showApply(result) {}
                    panel.notifySuccess(
                        "Locklane: Updates applied",
                        "${result.apply?.updatesApplied?.size ?: 0} package(s) updated",
                    )
                    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(manifest)
                }
            }

            override fun onCancel() {
                panel.setBusy(false)
            }

            override fun onThrowable(error: Throwable) {
                panel.setBusy(false)
                panel.showError("Apply failed", error.message ?: "Unknown error")
            }
        }.queue()
    }

    override fun update(e: AnActionEvent) {
        val panel = e.project?.let { findLocklanePanel(it) }
        e.presentation.isEnabled = panel?.state?.canApply == true
    }
}
