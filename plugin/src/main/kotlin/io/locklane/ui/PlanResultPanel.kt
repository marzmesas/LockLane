package io.locklane.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import io.locklane.model.BlockedUpdate
import io.locklane.model.InconclusiveUpdate
import io.locklane.model.SafeUpdate
import io.locklane.model.UpgradePlan
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.table.AbstractTableModel

class PlanResultPanel : JPanel() {

    private val safeModel = SafeTableModel()
    private val blockedModel = BlockedTableModel()
    private val inconclusiveModel = InconclusiveTableModel()
    private val stepsArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }

    private val chainDetailArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12)
    }

    private val safeTable = JBTable(safeModel).apply {
        emptyText.text = "(no safe updates)"
    }
    private val blockedTable = JBTable(blockedModel).apply {
        emptyText.text = "(no blocked updates)"
    }
    private val inconclusiveTable = JBTable(inconclusiveModel).apply {
        emptyText.text = "(no inconclusive updates)"
    }

    private val safeBorder = BorderFactory.createTitledBorder("Safe Updates (0)")
    private val blockedBorder = BorderFactory.createTitledBorder("Blocked Updates (0)")
    private val inconclusiveBorder = BorderFactory.createTitledBorder("Inconclusive Updates (0)")

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        val safeScroll = JBScrollPane(safeTable).apply {
            border = safeBorder
            preferredSize = Dimension(Int.MAX_VALUE, 150)
        }
        val blockedScroll = JBScrollPane(blockedTable).apply {
            border = blockedBorder
            preferredSize = Dimension(Int.MAX_VALUE, 150)
        }
        val inconclusiveScroll = JBScrollPane(inconclusiveTable).apply {
            border = inconclusiveBorder
            preferredSize = Dimension(Int.MAX_VALUE, 150)
        }
        val chainDetailScroll = JBScrollPane(chainDetailArea).apply {
            border = BorderFactory.createTitledBorder("Conflict Chain")
            preferredSize = Dimension(Int.MAX_VALUE, 120)
        }
        val stepsScroll = JBScrollPane(stepsArea).apply {
            border = BorderFactory.createTitledBorder("Ordered Steps")
            preferredSize = Dimension(Int.MAX_VALUE, 120)
        }

        blockedTable.selectionModel.addListSelectionListener { e ->
            if (e.valueIsAdjusting) return@addListSelectionListener
            val row = blockedTable.selectedRow
            if (row < 0 || row >= blockedModel.data.size) {
                chainDetailArea.text = ""
                return@addListSelectionListener
            }
            val chain = blockedModel.data[row].conflictChain
            if (chain == null) {
                chainDetailArea.text = "(no conflict chain data)"
            } else {
                val sb = StringBuilder()
                sb.appendLine("Summary: ${chain.summary}")
                chain.links.forEachIndexed { i, link ->
                    sb.appendLine("  ${i + 1}. ${link.packageName} (${link.constraint}) required by ${link.requiredBy}")
                }
                chainDetailArea.text = sb.toString()
            }
            chainDetailArea.caretPosition = 0
        }

        add(safeScroll)
        add(blockedScroll)
        add(chainDetailScroll)
        add(inconclusiveScroll)
        add(stepsScroll)
    }

    fun update(plan: UpgradePlan) {
        safeModel.data = plan.safeUpdates
        blockedModel.data = plan.blockedUpdates
        inconclusiveModel.data = plan.inconclusiveUpdates

        safeBorder.title = "Safe Updates (${plan.safeUpdates.size})"
        blockedBorder.title = "Blocked Updates (${plan.blockedUpdates.size})"
        inconclusiveBorder.title = "Inconclusive Updates (${plan.inconclusiveUpdates.size})"

        stepsArea.text = plan.orderedSteps.joinToString("\n") { "${it.step}. ${it.description}" }

        autoSizeColumns(safeTable)
        autoSizeColumns(blockedTable)
        autoSizeColumns(inconclusiveTable)

        revalidate()
        repaint()
    }

    fun clear() {
        safeModel.data = emptyList()
        blockedModel.data = emptyList()
        inconclusiveModel.data = emptyList()
        safeBorder.title = "Safe Updates (0)"
        blockedBorder.title = "Blocked Updates (0)"
        inconclusiveBorder.title = "Inconclusive Updates (0)"
        chainDetailArea.text = ""
        stepsArea.text = ""
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

    private class SafeTableModel : AbstractTableModel() {
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

    private class BlockedTableModel : AbstractTableModel() {
        var data: List<BlockedUpdate> = emptyList()
            set(value) { field = value; fireTableDataChanged() }

        override fun getRowCount() = data.size
        override fun getColumnCount() = 3
        override fun getColumnName(col: Int) = when (col) {
            0 -> "Package"
            1 -> "Target"
            2 -> "Reason"
            else -> ""
        }
        override fun getValueAt(row: Int, col: Int): Any = when (col) {
            0 -> data[row].packageName
            1 -> data[row].targetVersion
            2 -> data[row].reason
            else -> ""
        }
    }

    private class InconclusiveTableModel : AbstractTableModel() {
        var data: List<InconclusiveUpdate> = emptyList()
            set(value) { field = value; fireTableDataChanged() }

        override fun getRowCount() = data.size
        override fun getColumnCount() = 3
        override fun getColumnName(col: Int) = when (col) {
            0 -> "Package"
            1 -> "Target"
            2 -> "Reason"
            else -> ""
        }
        override fun getValueAt(row: Int, col: Int): Any = when (col) {
            0 -> data[row].packageName
            1 -> data[row].targetVersion
            2 -> data[row].reason
            else -> ""
        }
    }
}
