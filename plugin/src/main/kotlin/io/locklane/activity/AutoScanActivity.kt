package io.locklane.activity

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import io.locklane.service.LockLaneProjectState
import io.locklane.service.ResolverService
import io.locklane.settings.LockLaneSettings
import java.io.File
import java.nio.file.Files

class AutoScanActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val settings = LockLaneSettings.getInstance(project)
        if (!settings.state.autoScanEnabled) return

        val basePath = project.basePath ?: return
        val manifests = MANIFEST_NAMES.mapNotNull { name ->
            val file = File(basePath, name)
            if (file.isFile) file else null
        }
        if (manifests.isEmpty()) return

        object : Task.Backgroundable(project, "LockLane: Scanning dependencies...", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val service = ResolverService.getInstance(project)
                val projectState = LockLaneProjectState.getInstance(project)
                val ignored = settings.state.ignoredPackages.map { it.lowercase() }.toSet()
                val summaryParts = mutableListOf<String>()

                for (manifest in manifests) {
                    try {
                        indicator.text = "Scanning ${manifest.name}..."
                        val (plan, rawJson) = service.runPlanRaw(manifest.toPath(), indicator)
                        val tempFile = Files.createTempFile("locklane-autoscan-", ".json")
                        Files.writeString(tempFile, rawJson)

                        val filteredPlan = if (ignored.isEmpty()) plan else plan.copy(
                            safeUpdates = plan.safeUpdates.filter { it.packageName.lowercase() !in ignored },
                            blockedUpdates = plan.blockedUpdates.filter { it.packageName.lowercase() !in ignored },
                            inconclusiveUpdates = plan.inconclusiveUpdates.filter { it.packageName.lowercase() !in ignored },
                        )

                        val state = projectState.getOrCreate(manifest.toPath())
                        state.plan = filteredPlan
                        state.planJson = tempFile

                        val safe = filteredPlan.safeUpdates.size
                        val blocked = filteredPlan.blockedUpdates.size
                        val inconclusive = filteredPlan.inconclusiveUpdates.size
                        if (safe > 0 || blocked > 0 || inconclusive > 0) {
                            summaryParts += "${manifest.name}: $safe safe, $blocked blocked"
                        }
                    } catch (e: Exception) {
                        LOG.info("Auto-scan failed for ${manifest.name}: ${e.message}")
                    }
                }

                if (summaryParts.isEmpty()) return

                val message = summaryParts.joinToString("\n")
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    try {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("LockLane")
                            .createNotification("LockLane", message, NotificationType.INFORMATION)
                            .addAction(NotificationAction.createSimple("Open LockLane") {
                                ToolWindowManager.getInstance(project)
                                    .getToolWindow("LockLane")?.activate(null)
                            })
                            .notify(project)
                    } catch (_: Exception) { }
                }
            }
        }.queue()
    }

    companion object {
        private val LOG = Logger.getInstance(AutoScanActivity::class.java)

        val MANIFEST_NAMES = listOf(
            "requirements.in",
            "requirements.txt",
            "requirements-dev.in",
            "requirements-dev.txt",
            "pyproject.toml",
        )
    }
}
