package io.locklane.ui

import com.intellij.ui.JBColor
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import io.locklane.model.VerificationReport
import io.locklane.model.VerificationStep
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.table.AbstractTableModel

class VerifyResultPanel : JPanel() {

    private val bannerLabel = JBLabel("").apply {
        font = font.deriveFont(Font.BOLD, 14f)
        border = BorderFactory.createEmptyBorder(4, 0, 4, 0)
    }
    private val stepsModel = StepsTableModel()
    private val stepsTable = JBTable(stepsModel).apply {
        emptyText.text = "(no verification steps)"
    }
    private val stepDetailArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    }
    private val summaryLabel = JBLabel("").apply {
        border = BorderFactory.createEmptyBorder(4, 0, 4, 0)
    }

    private val stepsSeparator = TitledSeparator("Verification Steps")
    private val detailSeparator = TitledSeparator("Step Output")

    private val stepsScroll = JBScrollPane(stepsTable)
    private val detailScroll = JBScrollPane(stepDetailArea)

    private val stepsSection = section(stepsSeparator, stepsScroll)
    private val detailSection = section(detailSeparator, detailScroll)

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        stepsTable.selectionModel.addListSelectionListener { e ->
            if (e.valueIsAdjusting) return@addListSelectionListener
            val row = stepsTable.selectedRow
            if (row < 0 || row >= stepsModel.data.size) {
                stepDetailArea.text = ""
                detailSection.isVisible = false
                revalidate()
                return@addListSelectionListener
            }
            val step = stepsModel.data[row]
            val sb = StringBuilder()
            sb.appendLine("Command: ${step.command}")
            sb.appendLine("Exit code: ${step.exitCode}")
            if (step.stdout.isNotBlank()) {
                sb.appendLine()
                sb.appendLine("--- stdout ---")
                sb.appendLine(step.stdout)
            }
            if (step.stderr.isNotBlank()) {
                sb.appendLine()
                sb.appendLine("--- stderr ---")
                sb.appendLine(step.stderr)
            }
            stepDetailArea.text = sb.toString()
            stepDetailArea.caretPosition = 0
            detailSection.isVisible = true
            revalidate()
        }

        bannerLabel.alignmentX = LEFT_ALIGNMENT
        summaryLabel.alignmentX = LEFT_ALIGNMENT

        add(bannerLabel)
        add(stepsSection)
        add(detailSection)
        add(summaryLabel)
    }

    fun update(report: VerificationReport) {
        val verification = report.verification
        if (verification != null) {
            if (verification.passed) {
                bannerLabel.text = "PASSED"
                bannerLabel.foreground = JBColor.GREEN
            } else {
                bannerLabel.text = "FAILED"
                bannerLabel.foreground = JBColor.RED
            }
            stepsModel.data = verification.steps
            autoSizeColumns(stepsTable)
            summaryLabel.text = verification.summary

            stepsSection.isVisible = verification.steps.isNotEmpty()
            detailSection.isVisible = false // shown on row selection

            val rows = minOf(verification.steps.size, 10)
            val height = rows * stepsTable.rowHeight + TABLE_HEADER_HEIGHT + TABLE_PADDING
            stepsScroll.minimumSize = Dimension(0, TABLE_HEADER_HEIGHT)
            stepsScroll.preferredSize = Dimension(100, height)
            stepsScroll.maximumSize = Dimension(Int.MAX_VALUE, height)
        } else {
            bannerLabel.text = "No verification data"
            bannerLabel.foreground = JBColor.GRAY
            stepsModel.data = emptyList()
            summaryLabel.text = ""
            stepsSection.isVisible = false
            detailSection.isVisible = false
        }
        revalidate()
        repaint()
    }

    fun clear() {
        bannerLabel.text = ""
        stepsModel.data = emptyList()
        stepDetailArea.text = ""
        summaryLabel.text = ""
        stepsSection.isVisible = false
        detailSection.isVisible = false
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

    private class StepsTableModel : AbstractTableModel() {
        var data: List<VerificationStep> = emptyList()
            set(value) { field = value; fireTableDataChanged() }

        override fun getRowCount() = data.size
        override fun getColumnCount() = 3
        override fun getColumnName(col: Int) = when (col) {
            0 -> "Step"
            1 -> "Passed"
            2 -> "Duration"
            else -> ""
        }
        override fun getValueAt(row: Int, col: Int): Any = when (col) {
            0 -> data[row].name
            1 -> if (data[row].passed) "Yes" else "No"
            2 -> "%.1fs".format(data[row].durationSeconds)
            else -> ""
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
                content.minimumSize = Dimension(0, 40)
                content.preferredSize = Dimension(100, 150)
                content.maximumSize = Dimension(Int.MAX_VALUE, 200)
                add(separator)
                add(content)
            }
        }
    }
}
