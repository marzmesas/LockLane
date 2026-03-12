package io.locklane.action

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.vfs.LocalFileSystem
import io.locklane.model.SafeUpdate
import io.locklane.service.LockFileService
import io.locklane.service.ResolverService
import io.locklane.service.RollbackHistoryService
import io.locklane.settings.LocklaneSettings
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

class ApplyPlanAction : AnAction("Apply Plan", "Apply the plan (dry-run first)", AllIcons.Diff.ApplyNotConflicts) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val panel = findLocklanePanel(project) ?: return
        val manifest = panel.state.manifestPath ?: return
        val planJson = panel.state.lastPlanJson ?: return

        val selectedUpdates = panel.getSelectedSafeUpdates()
        if (selectedUpdates.isEmpty()) {
            panel.showError("Apply", "No updates selected. Check the updates you want to apply in the plan table.")
            return
        }

        val filteredPlan = createFilteredPlan(planJson, selectedUpdates, ResolverService.getInstance(project))

        panel.setBusy(true)

        object : Task.Backgroundable(project, "Locklane: Applying plan (dry-run)...", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Generating patch preview..."
                val service = ResolverService.getInstance(project)
                val result = service.runApply(manifest, filteredPlan, dryRun = true, indicator = indicator)
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    panel.setBusy(false)
                    panel.showApply(result) {
                        applyForReal(project, panel, manifest, filteredPlan)
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

    private fun createFilteredPlan(
        originalPlanJson: Path,
        selectedUpdates: List<SafeUpdate>,
        service: ResolverService,
    ): Path {
        val tree = service.objectMapper.readTree(Files.readString(originalPlanJson))
        val root = tree as com.fasterxml.jackson.databind.node.ObjectNode

        val selectedNames = selectedUpdates.map { it.packageName }.toSet()
        val filteredArray = service.objectMapper.createArrayNode()
        tree["safe_updates"]?.forEach { node ->
            val pkg = node["package"]?.asText() ?: ""
            if (pkg in selectedNames) {
                filteredArray.add(node)
            }
        }
        root.set<com.fasterxml.jackson.databind.node.ArrayNode>("safe_updates", filteredArray)

        val tempFile = Files.createTempFile("locklane-filtered-plan-", ".json")
        Files.writeString(tempFile, service.objectMapper.writeValueAsString(root))
        return tempFile
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
                // Save rollback before applying
                try {
                    RollbackHistoryService.getInstance(project).saveRollback(
                        manifest, planJson, 0,
                    )
                } catch (_: Exception) { /* best effort */ }

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
                    offerLockFileRegeneration(project, panel, manifest)
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

    private fun offerLockFileRegeneration(
        project: com.intellij.openapi.project.Project,
        panel: io.locklane.ui.LocklanePanel,
        manifest: Path,
    ) {
        val settings = LocklaneSettings.getInstance(project)
        val lockInfo = LockFileService.detectLockFile(manifest, settings.state.resolverPreference) ?: return

        val answer = JOptionPane.showConfirmDialog(
            SwingUtilities.getWindowAncestor(panel),
            "Regenerate ${lockInfo.lockFilePath.fileName} using ${lockInfo.toolName}?",
            "Locklane — Update Lock File",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE,
        )
        if (answer != JOptionPane.YES_OPTION) return

        panel.setBusy(true)

        object : Task.Backgroundable(project, "Locklane: Regenerating ${lockInfo.lockFilePath.fileName}...", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Running ${lockInfo.toolName}..."

                val process = ProcessBuilder(lockInfo.command)
                    .directory(manifest.parent?.toFile())
                    .redirectErrorStream(true)
                    .start()

                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()

                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    panel.setBusy(false)
                    if (exitCode == 0) {
                        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(lockInfo.lockFilePath)
                        panel.notifySuccess(
                            "Lock file updated",
                            "${lockInfo.lockFilePath.fileName} regenerated successfully",
                        )
                    } else {
                        panel.showError(
                            "Lock file update failed",
                            "${lockInfo.toolName} exited with code $exitCode",
                        )
                    }
                }
            }

            override fun onCancel() {
                panel.setBusy(false)
            }

            override fun onThrowable(error: Throwable) {
                panel.setBusy(false)
                panel.showError("Lock file update failed", error.message ?: "Unknown error")
            }
        }.queue()
    }

    override fun update(e: AnActionEvent) {
        val panel = e.project?.let { findLocklanePanel(it) }
        e.presentation.isEnabled = panel?.state?.canApply == true
    }
}
