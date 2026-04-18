package io.locklane.action

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import io.locklane.util.PlanMarkdownExporter
import java.awt.datatransfer.StringSelection

class ExportPlanAction : AnAction("Export Plan", "Copy upgrade plan as Markdown", AllIcons.Actions.Copy) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val panel = findLockLanePanel(project) ?: return
        val plan = panel.state.lastPlan ?: return

        val markdown = PlanMarkdownExporter.export(plan)
        CopyPasteManager.getInstance().setContents(StringSelection(markdown))
        panel.notifySuccess("Plan exported", "Upgrade plan copied to clipboard as Markdown")
    }

    override fun update(e: AnActionEvent) {
        val panel = e.project?.let { findLockLanePanel(it) }
        e.presentation.isEnabled = panel?.state?.lastPlan != null && panel?.state?.busy != true
    }
}
