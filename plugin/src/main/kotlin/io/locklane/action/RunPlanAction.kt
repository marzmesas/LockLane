package io.locklane.action

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import io.locklane.service.ResolverService
import io.locklane.settings.LocklaneSettings
import java.nio.file.Files

class RunPlanAction : AnAction("Run Plan", "Generate an upgrade plan", AllIcons.Actions.Execute) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val panel = findLocklanePanel(project) ?: return
        val manifest = panel.state.manifestPath ?: return

        panel.setBusy(true)

        object : Task.Backgroundable(project, "Locklane: Generating plan...", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Resolving dependencies and simulating candidates..."
                val service = ResolverService.getInstance(project)
                val (plan, rawJson) = service.runPlanRaw(manifest, indicator)
                val tempFile = Files.createTempFile("locklane-plan-", ".json")
                Files.writeString(tempFile, rawJson)

                // Filter out ignored packages
                val ignored = LocklaneSettings.getInstance(project).state.ignoredPackages
                    .map { it.lowercase() }.toSet()
                val filteredPlan = if (ignored.isEmpty()) plan else plan.copy(
                    safeUpdates = plan.safeUpdates.filter { it.packageName.lowercase() !in ignored },
                    blockedUpdates = plan.blockedUpdates.filter { it.packageName.lowercase() !in ignored },
                    inconclusiveUpdates = plan.inconclusiveUpdates.filter { it.packageName.lowercase() !in ignored },
                )

                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    panel.setBusy(false)
                    panel.showPlan(filteredPlan, tempFile)
                }
                // Run audit and enrich in background after plan
                runAuditAndEnrich(project, manifest, panel)
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

    private fun runAuditAndEnrich(
        project: com.intellij.openapi.project.Project,
        manifest: java.nio.file.Path,
        panel: io.locklane.ui.LocklanePanel,
    ) {
        val service = ResolverService.getInstance(project)

        object : Task.Backgroundable(project, "Locklane: Scanning vulnerabilities...", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    val audit = service.runAudit(manifest, indicator)
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        panel.updateVulnerabilities(audit)
                    }
                } catch (_: Exception) {
                    // Audit is best-effort; don't block the user
                }
            }
        }.queue()

        object : Task.Backgroundable(project, "Locklane: Fetching package links...", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    val enrich = service.runEnrich(manifest, indicator)
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        panel.updateLinks(enrich)
                    }
                } catch (_: Exception) {
                    // Enrich is best-effort; don't block the user
                }
            }
        }.queue()
    }

    override fun update(e: AnActionEvent) {
        val panel = e.project?.let { findLocklanePanel(it) }
        e.presentation.isEnabled = panel?.state?.canRunPlan == true
    }
}
