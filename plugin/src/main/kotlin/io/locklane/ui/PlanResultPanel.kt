package io.locklane.ui

import com.intellij.ui.JBColor
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import io.locklane.model.BlockedUpdate
import io.locklane.model.InconclusiveUpdate
import io.locklane.model.SafeUpdate
import io.locklane.model.UpgradePlan
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class PlanResultPanel : JPanel() {

    private val safeModel = SafeTableModel()
    private val blockedModel = BlockedTableModel()
    private val inconclusiveModel = InconclusiveTableModel()
    private val stepsArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    }

    private val chainDetailArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    }

    private val safeTable = JBTable(safeModel).apply {
        emptyText.text = "(no safe updates)"
        columnModel.getColumn(0).apply {
            preferredWidth = 30
            maxWidth = 30
            minWidth = 30
        }
        columnModel.getColumn(4).cellRenderer = BumpCellRenderer()
    }
    private val blockedTable = JBTable(blockedModel).apply {
        emptyText.text = "(no blocked updates)"
    }
    private val inconclusiveTable = JBTable(inconclusiveModel).apply {
        emptyText.text = "(no inconclusive updates)"
    }

    private val safeSeparator = TitledSeparator("Safe Updates")
    private val blockedSeparator = TitledSeparator("Blocked Updates")
    private val chainSeparator = TitledSeparator("Conflict Chain")
    private val inconclusiveSeparator = TitledSeparator("Inconclusive Updates")
    private val stepsSeparator = TitledSeparator("Ordered Steps")

    private val safeScroll = JBScrollPane(safeTable)
    private val blockedScroll = JBScrollPane(blockedTable)
    private val chainScroll = JBScrollPane(chainDetailArea)
    private val inconclusiveScroll = JBScrollPane(inconclusiveTable)
    private val stepsScroll = JBScrollPane(stepsArea)

    private val safeSection = section(safeSeparator, safeScroll)
    private val blockedSection = section(blockedSeparator, blockedScroll)
    private val chainSection = section(chainSeparator, chainScroll)
    private val inconclusiveSection = section(inconclusiveSeparator, inconclusiveScroll)
    private val stepsSection = section(stepsSeparator, stepsScroll)

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

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

        add(safeSection)
        add(blockedSection)
        add(chainSection)
        add(inconclusiveSection)
        add(stepsSection)
    }

    fun getSelectedSafeUpdates(): List<SafeUpdate> {
        return safeModel.data.filterIndexed { i, _ -> safeModel.selected[i] }
    }

    fun update(plan: UpgradePlan) {
        safeModel.data = plan.safeUpdates
        blockedModel.data = plan.blockedUpdates
        inconclusiveModel.data = plan.inconclusiveUpdates

        safeSeparator.text = "Safe Updates (${plan.safeUpdates.size})"
        blockedSeparator.text = "Blocked Updates (${plan.blockedUpdates.size})"
        inconclusiveSeparator.text = "Inconclusive Updates (${plan.inconclusiveUpdates.size})"

        stepsArea.text = plan.orderedSteps.joinToString("\n") { "${it.step}. ${it.description}" }

        // Fix checkbox column width after data load
        safeTable.columnModel.getColumn(0).apply {
            preferredWidth = 30
            maxWidth = 30
            minWidth = 30
        }
        autoSizeColumns(safeTable, skipColumns = setOf(0))
        autoSizeColumns(blockedTable)
        autoSizeColumns(inconclusiveTable)

        // Show/hide sections based on content
        safeSection.isVisible = plan.safeUpdates.isNotEmpty()
        blockedSection.isVisible = plan.blockedUpdates.isNotEmpty()
        chainSection.isVisible = plan.blockedUpdates.isNotEmpty()
        inconclusiveSection.isVisible = plan.inconclusiveUpdates.isNotEmpty()
        stepsSection.isVisible = plan.orderedSteps.isNotEmpty()

        // Size tables to content
        sizeToContent(safeScroll, safeTable, maxRows = 20)
        sizeToContent(blockedScroll, blockedTable, maxRows = 10)
        sizeToContent(inconclusiveScroll, inconclusiveTable, maxRows = 10)
        sizeToContent(chainScroll, maxHeight = 120)
        sizeToContent(stepsScroll, maxHeight = 200)

        revalidate()
        repaint()
    }

    fun clear() {
        safeModel.data = emptyList()
        blockedModel.data = emptyList()
        inconclusiveModel.data = emptyList()
        safeSeparator.text = "Safe Updates"
        blockedSeparator.text = "Blocked Updates"
        inconclusiveSeparator.text = "Inconclusive Updates"
        chainDetailArea.text = ""
        stepsArea.text = ""
        safeSection.isVisible = false
        blockedSection.isVisible = false
        chainSection.isVisible = false
        inconclusiveSection.isVisible = false
        stepsSection.isVisible = false
        revalidate()
        repaint()
    }

    private fun autoSizeColumns(table: JBTable, skipColumns: Set<Int> = emptySet()) {
        val columnModel = table.columnModel
        for (col in 0 until columnModel.columnCount) {
            if (col in skipColumns) continue
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
            set(value) {
                field = value
                selected = BooleanArray(value.size) { true }
                fireTableDataChanged()
            }
        var selected: BooleanArray = BooleanArray(0)

        override fun getRowCount() = data.size
        override fun getColumnCount() = 5
        override fun getColumnName(col: Int) = when (col) {
            0 -> ""
            1 -> "Package"
            2 -> "From"
            3 -> "To"
            4 -> "Bump"
            else -> ""
        }
        override fun getColumnClass(col: Int): Class<*> = when (col) {
            0 -> java.lang.Boolean::class.java
            else -> String::class.java
        }
        override fun isCellEditable(row: Int, col: Int) = col == 0
        override fun setValueAt(value: Any?, row: Int, col: Int) {
            if (col == 0 && value is Boolean) {
                selected[row] = value
                fireTableCellUpdated(row, col)
            }
        }
        override fun getValueAt(row: Int, col: Int): Any = when (col) {
            0 -> selected[row]
            1 -> data[row].packageName
            2 -> data[row].fromVersion
            3 -> data[row].toVersion
            4 -> bumpSeverity(data[row].fromVersion, data[row].toVersion)
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

    private class BumpCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, col: Int,
        ): Component {
            val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col)
            if (!isSelected) {
                foreground = when (value) {
                    "major" -> JBColor(Color(220, 80, 80), Color(220, 100, 100))
                    "minor" -> JBColor(Color(200, 170, 50), Color(220, 190, 70))
                    "patch" -> JBColor(Color(80, 180, 80), Color(100, 200, 100))
                    else -> table.foreground
                }
            }
            return comp
        }
    }

    companion object {
        private const val TABLE_HEADER_HEIGHT = 28
        private const val TABLE_PADDING = 4

        fun bumpSeverity(from: String, to: String): String {
            val fromParts = from.split(".").mapNotNull { it.toIntOrNull() }
            val toParts = to.split(".").mapNotNull { it.toIntOrNull() }
            if (fromParts.size < 2 || toParts.size < 2) return "?"
            return when {
                toParts[0] != fromParts[0] -> "major"
                toParts.getOrElse(1) { 0 } != fromParts.getOrElse(1) { 0 } -> "minor"
                else -> "patch"
            }
        }

        private fun section(separator: TitledSeparator, content: JComponent): JPanel {
            return JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                alignmentX = LEFT_ALIGNMENT
                separator.alignmentX = LEFT_ALIGNMENT
                content.alignmentX = LEFT_ALIGNMENT
                separator.maximumSize = Dimension(Int.MAX_VALUE, separator.preferredSize.height)
                add(separator)
                add(content)
            }
        }

        private fun sizeToContent(scroll: JBScrollPane, table: JBTable, maxRows: Int) {
            val rows = minOf(table.rowCount, maxRows)
            val height = rows * table.rowHeight + TABLE_HEADER_HEIGHT + TABLE_PADDING
            scroll.minimumSize = Dimension(0, TABLE_HEADER_HEIGHT)
            scroll.preferredSize = Dimension(100, height)
            scroll.maximumSize = Dimension(Int.MAX_VALUE, height)
        }

        private fun sizeToContent(scroll: JBScrollPane, maxHeight: Int) {
            scroll.minimumSize = Dimension(0, 40)
            scroll.preferredSize = Dimension(100, maxHeight)
            scroll.maximumSize = Dimension(Int.MAX_VALUE, maxHeight)
        }
    }
}
