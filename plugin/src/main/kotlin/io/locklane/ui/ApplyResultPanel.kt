package io.locklane.ui

import com.intellij.ui.JBColor
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import io.locklane.model.ApplyResult
import io.locklane.model.SafeUpdate
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

class ApplyResultPanel : JPanel() {

    private val modeBadge = JBLabel("").apply {
        font = font.deriveFont(Font.BOLD, 14f)
        border = BorderFactory.createEmptyBorder(4, 0, 4, 0)
    }
    private val patchPane = JTextPane().apply {
        isEditable = false
        font = Font("Monospaced", Font.PLAIN, 12)
    }
    private val updatesModel = UpdatesTableModel()
    private val updatesTable = JBTable(updatesModel).apply {
        emptyText.text = "(no updates applied)"
    }
    private val confirmButton = JButton("Apply plan").apply {
        isVisible = false
    }
    private val copyButton = JButton("Copy diff").apply {
        isVisible = false
        addActionListener {
            val text = patchPane.text
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(StringSelection(text), null)
        }
    }

    private var rawPatch: String = ""

    private val patchSeparator = TitledSeparator("Patch Preview")
    private val updatesSeparator = TitledSeparator("Updates Applied")

    private val patchScroll = JBScrollPane(patchPane)
    private val updatesScroll = JBScrollPane(updatesTable)

    private val patchSection = section(patchSeparator, patchScroll)
    private val updatesSection = section(updatesSeparator, updatesScroll)

    private val buttonPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        add(confirmButton)
        add(javax.swing.Box.createHorizontalStrut(8))
        add(copyButton)
        alignmentX = LEFT_ALIGNMENT
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        modeBadge.alignmentX = LEFT_ALIGNMENT

        add(modeBadge)
        add(patchSection)
        add(updatesSection)
        add(buttonPanel)
    }

    fun update(result: ApplyResult, onConfirmApply: () -> Unit) {
        if (result.dryRun) {
            modeBadge.text = "DRY RUN"
            modeBadge.foreground = JBColor.ORANGE
            confirmButton.isVisible = true
            confirmButton.actionListeners.forEach { confirmButton.removeActionListener(it) }
            confirmButton.addActionListener {
                val count = result.apply?.updatesApplied?.size ?: 0
                val answer = JOptionPane.showConfirmDialog(
                    SwingUtilities.getWindowAncestor(this),
                    "Apply $count update(s) to the requirements file?",
                    "Confirm Apply",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE,
                )
                if (answer == JOptionPane.OK_OPTION) {
                    onConfirmApply()
                }
            }
        } else {
            modeBadge.text = "APPLIED"
            modeBadge.foreground = JBColor.GREEN
            confirmButton.isVisible = false
        }

        val applyData = result.apply
        if (applyData != null) {
            rawPatch = applyData.patchPreview
            renderDiff(rawPatch)
            updatesModel.data = applyData.updatesApplied
            autoSizeColumns(updatesTable)

            patchSection.isVisible = rawPatch.isNotBlank()
            copyButton.isVisible = rawPatch.isNotBlank()
            patchPane.caretPosition = 0
            updatesSection.isVisible = applyData.updatesApplied.isNotEmpty()

            // Size updates table to content
            val rows = minOf(applyData.updatesApplied.size, 20)
            val height = rows * updatesTable.rowHeight + TABLE_HEADER_HEIGHT + TABLE_PADDING
            updatesScroll.minimumSize = Dimension(0, TABLE_HEADER_HEIGHT)
            updatesScroll.preferredSize = Dimension(100, height)
            updatesScroll.maximumSize = Dimension(Int.MAX_VALUE, height)
        } else {
            patchPane.text = ""
            updatesModel.data = emptyList()
            patchSection.isVisible = false
            updatesSection.isVisible = false
        }

        revalidate()
        repaint()
    }

    fun clear() {
        modeBadge.text = ""
        patchPane.text = ""
        rawPatch = ""
        updatesModel.data = emptyList()
        confirmButton.isVisible = false
        copyButton.isVisible = false
        confirmButton.actionListeners.forEach { confirmButton.removeActionListener(it) }
        patchSection.isVisible = false
        updatesSection.isVisible = false
        revalidate()
        repaint()
    }

    private fun renderDiff(patch: String) {
        val doc = patchPane.styledDocument
        doc.remove(0, doc.length)

        val addAttrs = SimpleAttributeSet().apply {
            StyleConstants.setForeground(this, Color(80, 200, 80))
        }
        val removeAttrs = SimpleAttributeSet().apply {
            StyleConstants.setForeground(this, Color(220, 80, 80))
        }
        val defaultAttrs = SimpleAttributeSet()

        val lines = patch.lines().filter { line ->
            !line.startsWith("@@") && !line.startsWith("---") && !line.startsWith("+++")
        }

        for ((i, line) in lines.withIndex()) {
            val attrs = when {
                line.startsWith("+") -> addAttrs
                line.startsWith("-") -> removeAttrs
                else -> defaultAttrs
            }
            if (i > 0) doc.insertString(doc.length, "\n", defaultAttrs)
            doc.insertString(doc.length, line, attrs)
        }
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
                content.preferredSize = Dimension(100, 200)
                content.maximumSize = Dimension(Int.MAX_VALUE, 200)
                add(separator)
                add(content)
            }
        }
    }
}
