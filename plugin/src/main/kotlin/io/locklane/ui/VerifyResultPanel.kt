package io.locklane.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import io.locklane.model.VerificationReport
import io.locklane.model.VerificationStep
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.table.AbstractTableModel

class VerifyResultPanel : JPanel() {

    private val bannerLabel = JBLabel("").apply {
        font = font.deriveFont(Font.BOLD, 14f)
    }
    private val stepsModel = StepsTableModel()
    private val stepsTable = JBTable(stepsModel)
    private val stepDetailArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    }
    private val summaryLabel = JBLabel("")

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        val bannerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(bannerLabel)
        }

        val stepsScroll = JBScrollPane(stepsTable).apply {
            border = BorderFactory.createTitledBorder("Verification Steps")
            preferredSize = Dimension(Int.MAX_VALUE, 200)
        }

        val stepDetailScroll = JBScrollPane(stepDetailArea).apply {
            border = BorderFactory.createTitledBorder("Step Output")
            preferredSize = Dimension(Int.MAX_VALUE, 150)
        }

        stepsTable.selectionModel.addListSelectionListener { e ->
            if (e.valueIsAdjusting) return@addListSelectionListener
            val row = stepsTable.selectedRow
            if (row < 0 || row >= stepsModel.data.size) {
                stepDetailArea.text = ""
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
        }

        add(bannerPanel)
        add(stepsScroll)
        add(stepDetailScroll)
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
            summaryLabel.text = verification.summary
        } else {
            bannerLabel.text = "No verification data"
            bannerLabel.foreground = JBColor.GRAY
            stepsModel.data = emptyList()
            summaryLabel.text = ""
        }
        revalidate()
        repaint()
    }

    fun clear() {
        bannerLabel.text = ""
        stepsModel.data = emptyList()
        stepDetailArea.text = ""
        summaryLabel.text = ""
        revalidate()
        repaint()
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
}
