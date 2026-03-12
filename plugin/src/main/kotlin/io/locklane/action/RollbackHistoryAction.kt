package io.locklane.action

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import io.locklane.service.RollbackHistoryService
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.SwingUtilities

class RollbackHistoryAction : AnAction("History", "View and restore previous manifests", AllIcons.Vcs.History) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val panel = findLocklanePanel(project) ?: return
        val manifest = panel.state.manifestPath ?: return

        val service = RollbackHistoryService.getInstance(project)
        val entries = service.listEntries()

        if (entries.isEmpty()) {
            panel.showError("History", "No rollback history found")
            return
        }

        val dialog = RollbackHistoryDialog(entries)
        if (dialog.showAndGet()) {
            val selected = dialog.selectedEntry ?: return
            when (dialog.selectedAction) {
                DialogAction.RESTORE -> {
                    val confirm = JOptionPane.showConfirmDialog(
                        SwingUtilities.getWindowAncestor(panel),
                        "Restore manifest from ${selected.timestamp}?\nThis will overwrite the current file.",
                        "Locklane — Confirm Restore",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE,
                    )
                    if (confirm == JOptionPane.YES_OPTION) {
                        service.restore(selected, manifest)
                        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(manifest)
                        panel.notifySuccess("Manifest restored", "Rolled back to ${selected.timestamp}")
                    }
                }
                DialogAction.DELETE -> {
                    service.deleteEntry(selected)
                    panel.notifySuccess("Entry deleted", "Removed history entry ${selected.timestamp}")
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val panel = e.project?.let { findLocklanePanel(it) }
        e.presentation.isEnabled = panel?.state?.manifestPath != null
    }

    private enum class DialogAction { RESTORE, DELETE }

    private class RollbackHistoryDialog(
        private val entries: List<RollbackHistoryService.RollbackEntry>,
    ) : DialogWrapper(true) {

        var selectedEntry: RollbackHistoryService.RollbackEntry? = null
        var selectedAction: DialogAction = DialogAction.RESTORE

        private val listModel = DefaultListModel<String>().apply {
            entries.forEach { addElement(it.description) }
        }
        private val list = JBList(listModel)

        init {
            title = "Locklane — Rollback History"
            setOKButtonText("Restore")
            setCancelButtonText("Close")
            init()
            list.selectedIndex = 0
        }

        override fun createCenterPanel(): JComponent {
            return JPanel(BorderLayout()).apply {
                preferredSize = Dimension(450, 300)
                add(JBScrollPane(list), BorderLayout.CENTER)
            }
        }

        override fun createLeftSideActions(): Array<javax.swing.Action> {
            val deleteAction = object : DialogWrapperAction("Delete") {
                override fun doAction(e: java.awt.event.ActionEvent?) {
                    val idx = list.selectedIndex
                    if (idx >= 0) {
                        selectedEntry = entries[idx]
                        selectedAction = DialogAction.DELETE
                        close(OK_EXIT_CODE)
                    }
                }
            }
            return arrayOf(deleteAction)
        }

        override fun doOKAction() {
            val idx = list.selectedIndex
            if (idx >= 0) {
                selectedEntry = entries[idx]
                selectedAction = DialogAction.RESTORE
            }
            super.doOKAction()
        }
    }
}
