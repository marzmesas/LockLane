package io.locklane.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import io.locklane.model.ApplyResult
import io.locklane.model.AuditResult
import io.locklane.model.BaselineResult
import io.locklane.model.EnrichResult
import io.locklane.model.UpgradePlan
import io.locklane.model.VerificationReport
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.GridBagLayout
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JProgressBar

class LocklanePanel(private val project: Project) : JPanel(BorderLayout()) {

    val state = PanelState()

    private val manifestLabel = JBLabel("No manifest selected")
    private val statusLabel = JBLabel("Ready")
    private val progressBar = JProgressBar().apply {
        isIndeterminate = true
        isStringPainted = true
        string = "Working..."
        isVisible = false
    }

    private lateinit var statusCardPanel: JPanel
    private val statusCardLayout get() = statusCardPanel.layout as CardLayout

    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)

    private val emptyPanel = JPanel(GridBagLayout()).apply {
        add(JBLabel("Select a requirements file to get started").apply {
            foreground = JBColor.GRAY
        })
    }
    private val baselineResultPanel = BaselineResultPanel()
    private val planResultPanel = PlanResultPanel()
    private val verifyResultPanel = VerifyResultPanel()
    private val applyResultPanel = ApplyResultPanel()

    private val footerLabel = JBLabel("").apply {
        border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
        foreground = JBColor.GRAY
    }

    init {
        name = "LocklanePanel"

        val statusRow = JPanel(CardLayout()).also { statusCardPanel = it }
        statusRow.add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(statusLabel)
        }, STATUS_LABEL)
        statusRow.add(progressBar, STATUS_PROGRESS)

        val headerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                add(JBLabel("Manifest: "))
                add(manifestLabel)
            })
            add(statusRow)
        }

        cardPanel.add(emptyPanel, CARD_EMPTY)
        cardPanel.add(JBScrollPane(baselineResultPanel), CARD_BASELINE)
        cardPanel.add(JBScrollPane(planResultPanel), CARD_PLAN)
        cardPanel.add(JBScrollPane(verifyResultPanel), CARD_VERIFY)
        cardPanel.add(JBScrollPane(applyResultPanel), CARD_APPLY)

        add(headerPanel, BorderLayout.NORTH)
        add(cardPanel, BorderLayout.CENTER)
        add(footerLabel, BorderLayout.SOUTH)
    }

    fun setManifest(path: Path) {
        state.onManifestSelected(path)
        manifestLabel.text = path.fileName.toString()
        manifestLabel.toolTipText = path.toString()
        statusLabel.text = "Ready"
        statusLabel.foreground = JBColor.foreground()
        footerLabel.text = ""
        baselineResultPanel.clear()
        planResultPanel.clear()
        verifyResultPanel.clear()
        applyResultPanel.clear()
        cardLayout.show(cardPanel, CARD_EMPTY)
        io.locklane.settings.LocklaneSettings.getInstance(project).state.lastManifestPath = path.toString()
    }

    fun showBaseline(baseline: BaselineResult) {
        baselineResultPanel.update(baseline)
        statusLabel.text = "Baseline — ${baseline.dependencies.size} dependencies"
        statusLabel.foreground = JBColor.foreground()
        footerLabel.text = "Baseline: ${baseline.dependencies.size} pinned dependencies"
        cardLayout.show(cardPanel, CARD_BASELINE)
    }

    fun showPlan(plan: UpgradePlan, planJsonPath: Path) {
        state.onPlanCompleted(plan, planJsonPath)
        planResultPanel.update(plan)
        statusLabel.text = "Plan generated — ${plan.safeUpdates.size} safe, ${plan.blockedUpdates.size} blocked"
        statusLabel.foreground = JBColor.foreground()
        footerLabel.text = "Plan: ${plan.safeUpdates.size} safe, ${plan.blockedUpdates.size} blocked, ${plan.inconclusiveUpdates.size} inconclusive"
        cardLayout.show(cardPanel, CARD_PLAN)
    }

    fun showVerification(report: VerificationReport) {
        state.onVerifyCompleted(report)
        verifyResultPanel.update(report)
        val passed = report.verification?.passed == true
        statusLabel.text = if (passed) "Verification passed" else "Verification failed"
        statusLabel.foreground = if (passed) JBColor.GREEN else JBColor.RED
        val steps = report.verification?.steps ?: emptyList()
        val passedCount = steps.count { it.passed }
        footerLabel.text = "Verification: $passedCount/${steps.size} steps passed"
        cardLayout.show(cardPanel, CARD_VERIFY)
    }

    fun showApply(result: ApplyResult, onConfirmApply: () -> Unit) {
        state.onApplyCompleted(result)
        applyResultPanel.update(result, onConfirmApply)
        statusLabel.text = if (result.dryRun) "Dry-run complete — review and confirm" else "Updates applied"
        statusLabel.foreground = JBColor.foreground()
        val count = result.apply?.updatesApplied?.size ?: 0
        footerLabel.text = if (result.dryRun) {
            "Dry-run: $count update(s) previewed"
        } else {
            "Applied: $count update(s)"
        }
        cardLayout.show(cardPanel, CARD_APPLY)
    }

    fun getSelectedSafeUpdates(): List<io.locklane.model.SafeUpdate> {
        return planResultPanel.getSelectedSafeUpdates()
    }

    fun updateVulnerabilities(audit: AuditResult) {
        planResultPanel.updateVulnerabilities(audit)
    }

    fun updateLinks(enrich: EnrichResult) {
        planResultPanel.updateLinks(enrich)
    }

    fun showError(title: String, message: String) {
        statusLabel.text = "$title: $message"
        statusLabel.foreground = JBColor.RED
        footerLabel.text = ""
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Locklane")
                .createNotification(title, message, NotificationType.ERROR)
                .notify(project)
        } catch (_: Exception) {
            // Notification group may not be available in tests
        }
    }

    fun setBusy(running: Boolean) {
        state.busy = running
        if (running) {
            statusCardLayout.show(statusCardPanel, STATUS_PROGRESS)
        } else {
            statusCardLayout.show(statusCardPanel, STATUS_LABEL)
        }
    }

    fun notifySuccess(title: String, message: String) {
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Locklane")
                .createNotification(title, message, NotificationType.INFORMATION)
                .notify(project)
        } catch (_: Exception) {
            // Notification group may not be available in tests
        }
    }

    companion object {
        const val CARD_EMPTY = "EMPTY"
        const val CARD_BASELINE = "BASELINE"
        const val CARD_PLAN = "PLAN"
        const val CARD_VERIFY = "VERIFY"
        const val CARD_APPLY = "APPLY"
        private const val STATUS_LABEL = "STATUS_LABEL"
        private const val STATUS_PROGRESS = "STATUS_PROGRESS"
    }
}
