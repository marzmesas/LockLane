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
        val manifest = MANIFEST_NAMES.firstNotNullOfOrNull { name ->
            val file = File(basePath, name)
            if (file.isFile) file else null
        } ?: return

        object : Task.Backgroundable(project, "LockLane: Scanning dependencies...", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    val service = ResolverService.getInstance(project)
                    val (plan, rawJson) = service.runPlanRaw(manifest.toPath(), indicator)
                    val tempFile = Files.createTempFile("locklane-autoscan-", ".json")
                    Files.writeString(tempFile, rawJson)

                    val state = LockLaneProjectState.getInstance(project)
                    state.lastPlan = plan
                    state.lastPlanJson = tempFile
                    state.manifestPath = manifest.toPath()

                    val safe = plan.safeUpdates.size
                    val blocked = plan.blockedUpdates.size
                    val inconclusive = plan.inconclusiveUpdates.size

                    if (safe == 0 && blocked == 0 && inconclusive == 0) return

                    val message = buildString {
                        append("$safe safe")
                        if (blocked > 0) append(", $blocked blocked")
                        if (inconclusive > 0) append(", $inconclusive inconclusive")
                        append(" update(s) found in ${manifest.name}")
                    }

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
                        } catch (_: Exception) {
                            // Notification group may not be available
                        }
                    }
                } catch (e: Exception) {
                    LOG.info("LockLane auto-scan failed: ${e.message}")
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        try {
                            NotificationGroupManager.getInstance()
                                .getNotificationGroup("LockLane")
                                .createNotification(
                                    "LockLane",
                                    "Auto-scan failed: ${e.message ?: "unknown error"}. Check Settings > Tools > LockLane.",
                                    NotificationType.WARNING,
                                )
                                .notify(project)
                        } catch (_: Exception) { }
                    }
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
