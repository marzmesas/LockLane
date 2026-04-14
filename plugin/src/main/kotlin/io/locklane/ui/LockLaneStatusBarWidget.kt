package io.locklane.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Consumer
import io.locklane.service.LockLaneProjectState
import io.locklane.service.LockLaneStateListener
import java.awt.event.MouseEvent

class LockLaneStatusBarWidget(private val project: Project) :
    StatusBarWidget,
    StatusBarWidget.TextPresentation {

    companion object {
        const val ID = "LockLaneStatusBar"
    }

    private var statusBar: StatusBar? = null

    override fun ID(): String = ID

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        project.messageBus.connect(this).subscribe(
            LockLaneStateListener.TOPIC,
            object : LockLaneStateListener {
                override fun stateChanged() {
                    statusBar.updateWidget(ID)
                }
            },
        )
    }

    override fun dispose() {
        statusBar = null
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String {
        val state = LockLaneProjectState.getInstance(project)
        var totalSafe = 0
        var totalBlocked = 0
        var totalVulns = 0

        for ((_, ms) in state.manifests) {
            ms.plan?.let { plan ->
                totalSafe += plan.safeUpdates.size
                totalBlocked += plan.blockedUpdates.size
            }
            ms.audit?.let { audit ->
                totalVulns += audit.packages.sumOf { it.vulnerabilities.size }
            }
        }

        if (totalSafe == 0 && totalBlocked == 0 && totalVulns == 0) {
            return ""
        }

        val parts = mutableListOf<String>()
        if (totalSafe > 0) parts += "$totalSafe update${if (totalSafe != 1) "s" else ""}"
        if (totalBlocked > 0) parts += "$totalBlocked blocked"
        if (totalVulns > 0) parts += "$totalVulns vuln${if (totalVulns != 1) "s" else ""}"
        return "LockLane: ${parts.joinToString(", ")}"
    }

    override fun getTooltipText(): String {
        val state = LockLaneProjectState.getInstance(project)
        val lines = mutableListOf<String>()
        for ((path, ms) in state.manifests) {
            val fileName = path.fileName?.toString() ?: path.toString()
            val safe = ms.plan?.safeUpdates?.size ?: 0
            val blocked = ms.plan?.blockedUpdates?.size ?: 0
            val vulns = ms.audit?.packages?.sumOf { it.vulnerabilities.size } ?: 0
            if (safe > 0 || blocked > 0 || vulns > 0) {
                lines += "$fileName: $safe safe, $blocked blocked, $vulns vulns"
            }
        }
        return if (lines.isEmpty()) "No updates or vulnerabilities detected" else lines.joinToString("\n")
    }

    override fun getAlignment(): Float = 0f

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        ToolWindowManager.getInstance(project).getToolWindow("LockLane")?.activate(null)
    }
}
