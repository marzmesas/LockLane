package io.locklane.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import io.locklane.model.ApplyResult
import io.locklane.model.SafeUpdate
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.table.AbstractTableModel

class ApplyResultPanel : JPanel() {

    private val modeBadge = JBLabel("").apply {
        font = font.deriveFont(Font.BOLD, 14f)
    }
    private val patchArea = JTextArea().apply {
        isEditable = false
        font = Font("Monospaced", Font.PLAIN, 12)
    }
    private val updatesModel = UpdatesTableModel()
    private val updatesTable = JBTable(updatesModel).apply {
        emptyText.text = "(no updates applied)"
    }
    private val confirmButton = JButton("Apply for Real").apply {
        isVisible = false
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        val badgePanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(modeBadge)
            alignmentX = LEFT_ALIGNMENT
        }

        val patchScroll = JBScrollPane(patchArea).apply {
            border = BorderFactory.createTitledBorder("Patch Preview")
            minimumSize = Dimension(0, 200)
            preferredSize = Dimension(100, 200)
            maximumSize = Dimension(Int.MAX_VALUE, 200)
            alignmentX = LEFT_ALIGNMENT
        }

        val updatesScroll = JBScrollPane(updatesTable).apply {
            border = BorderFactory.createTitledBorder("Updates Applied")
            minimumSize = Dimension(0, 150)
            preferredSize = Dimension(100, 150)
            maximumSize = Dimension(Int.MAX_VALUE, 150)
            alignmentX = LEFT_ALIGNMENT
        }

        val buttonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(confirmButton)
            alignmentX = LEFT_ALIGNMENT
        }

        add(badgePanel)
        add(patchScroll)
        add(updatesScroll)
        add(buttonPanel)
    }

    fun update(result: ApplyResult, onConfirmApply: () -> Unit) {
        if (result.dryRun) {
            modeBadge.text = "DRY RUN"
            modeBadge.foreground = JBColor.ORANGE
            confirmButton.isVisible = true
            // Remove previous listeners before adding new one
            confirmButton.actionListeners.forEach { confirmButton.removeActionListener(it) }
            confirmButton.addActionListener { onConfirmApply() }
        } else {
            modeBadge.text = "APPLIED"
            modeBadge.foreground = JBColor.GREEN
            confirmButton.isVisible = false
        }

        val applyData = result.apply
        if (applyData != null) {
            patchArea.text = applyData.patchPreview
            updatesModel.data = applyData.updatesApplied
            autoSizeColumns(updatesTable)
        } else {
            patchArea.text = ""
            updatesModel.data = emptyList()
        }

        revalidate()
        repaint()
    }

    fun clear() {
        modeBadge.text = ""
        patchArea.text = ""
        updatesModel.data = emptyList()
        confirmButton.isVisible = false
        confirmButton.actionListeners.forEach { confirmButton.removeActionListener(it) }
        revalidate()
        repaint()
    }

    private fun autoSizeColumns(table: JBTable) {
        val columnModel = table.columnModel
        for (col in 0 until columnModel.columnCount) {
            var maxWidth = table.tableHeader
                ?.defaultRenderer
                ?.getTableCellRendererComponent(table, table.getColumnName(col), false, false, -1, col)
                ?.preferredSize?.width ?: 50
            for (row in 0 until table.rowCount) {
                val renderer = table.getCellRenderer(row, col)
                val comp = table.prepareRenderer(renderer, row, col)
                maxWidth = maxOf(maxWidth, comp.preferredSize.width)
            }
            columnModel.getColumn(col).preferredWidth = maxWidth + 16
        }
        table.autoResizeMode = JBTable.AUTO_RESIZE_LAST_COLUMN
    }

    private class UpdatesTableModel : AbstractTableModel() {
        var data: List<SafeUpdate> = emptyList()
            set(value) { field = value; fireTableDataChanged() }

        override fun getRowCount() = data.size
        override fun getColumnCount() = 3
        override fun getColumnName(col: Int) = when (col) {
            0 -> "Package"
            1 -> "From"
            2 -> "To"
            else -> ""
        }
        override fun getValueAt(row: Int, col: Int): Any = when (col) {
            0 -> data[row].packageName
            1 -> data[row].fromVersion
            2 -> data[row].toVersion
            else -> ""
        }
    }
}
