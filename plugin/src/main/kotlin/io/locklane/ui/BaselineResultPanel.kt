package io.locklane.ui

import com.intellij.ui.JBColor
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import io.locklane.model.BaselineResult
import io.locklane.model.ParsedDependency
import io.locklane.model.ResolvedPackage
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class BaselineResultPanel : JPanel() {

    private val depModel = BaselineTableModel()
    private val depTable = JBTable(depModel).apply {
        emptyText.text = "(no dependencies)"
        columnModel.getColumn(2).cellRenderer = StaleCellRenderer()
    }

    private val separator = TitledSeparator("Current Dependencies")
    private val scroll = JBScrollPane(depTable)
    private val mainSection = section(separator, scroll)

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(mainSection)
        mainSection.isVisible = false
    }

    fun update(baseline: BaselineResult) {
        val resolved = baseline.resolution?.packages?.associateBy { it.name.lowercase() } ?: emptyMap()
        depModel.update(baseline.dependencies, resolved)
        separator.text = "Current Dependencies (${baseline.dependencies.size})"
        mainSection.isVisible = baseline.dependencies.isNotEmpty()
        autoSizeColumns(depTable)
        sizeToContent(scroll, depTable, maxRows = 25)
        revalidate()
        repaint()
    }

    fun clear() {
        depModel.update(emptyList(), emptyMap())
        separator.text = "Current Dependencies"
        mainSection.isVisible = false
        revalidate()
        repaint()
    }

    private fun autoSizeColumns(table: JBTable) {
        val cm = table.columnModel
        for (col in 0 until cm.columnCount) {
            var maxW = table.tableHeader
                ?.defaultRenderer
                ?.getTableCellRendererComponent(table, table.getColumnName(col), false, false, -1, col)
                ?.preferredSize?.width ?: 50
            for (row in 0 until table.rowCount) {
                val renderer = table.getCellRenderer(row, col)
                val comp = table.prepareRenderer(renderer, row, col)
                maxW = maxOf(maxW, comp.preferredSize.width)
            }
            cm.getColumn(col).preferredWidth = maxW + 16
        }
        table.autoResizeMode = JBTable.AUTO_RESIZE_LAST_COLUMN
    }

    private class BaselineTableModel : AbstractTableModel() {
        data class Row(val name: String, val pinned: String, val resolved: String)

        var rows: List<Row> = emptyList()

        fun update(deps: List<ParsedDependency>, resolved: Map<String, ResolvedPackage>) {
            rows = deps.map { dep ->
                val res = resolved[dep.name.lowercase()]
                Row(
                    name = dep.name,
                    pinned = dep.specifier.removePrefix("==").trim(),
                    resolved = res?.version ?: "",
                )
            }
            fireTableDataChanged()
        }

        override fun getRowCount() = rows.size
        override fun getColumnCount() = 3
        override fun getColumnName(col: Int) = when (col) {
            0 -> "Package"
            1 -> "Pinned Version"
            2 -> "Resolved"
            else -> ""
        }
        override fun getValueAt(row: Int, col: Int): Any = when (col) {
            0 -> rows[row].name
            1 -> rows[row].pinned
            2 -> rows[row].resolved.ifBlank { "-" }
            else -> ""
        }
    }

    private class StaleCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, col: Int,
        ): Component {
            val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col)
            if (!isSelected) {
                foreground = if (value == "-") {
                    JBColor(Color(200, 170, 50), Color(220, 190, 70))
                } else {
                    table.foreground
                }
            }
            return comp
        }
    }

    companion object {
        private const val TABLE_HEADER_HEIGHT = 28
        private const val TABLE_PADDING = 4

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
    }
}
