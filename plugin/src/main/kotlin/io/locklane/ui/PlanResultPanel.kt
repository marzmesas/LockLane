package io.locklane.ui

import com.intellij.ui.JBColor
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.ide.BrowserUtil
import io.locklane.model.AuditResult
import io.locklane.model.BlockedUpdate
import io.locklane.model.EnrichResult
import io.locklane.model.GroupCascade
import io.locklane.model.InconclusiveUpdate
import io.locklane.model.PackageLinks
import io.locklane.model.SafeUpdate
import io.locklane.model.UpgradePlan
import io.locklane.model.Vulnerability
import io.locklane.model.PackageAudit
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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
    private val vulnModel = VulnerabilityTableModel()
    private var packageLinks: Map<String, PackageLinks> = emptyMap()

    private val stepsArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    }

    private val conflictChainTree = com.intellij.ui.treeStructure.Tree().apply {
        isRootVisible = true
        cellRenderer = DependencyTreeCellRenderer()
        model = javax.swing.tree.DefaultTreeModel(javax.swing.tree.DefaultMutableTreeNode("Select a blocked package"))
    }

    private val safeTable = object : JBTable(safeModel) {
        init {
            emptyText.text = "(no safe updates)"
            columnModel.getColumn(0).apply {
                preferredWidth = 30
                maxWidth = 30
                minWidth = 30
            }
            columnModel.getColumn(1).cellRenderer = PackageCellRenderer()
            columnModel.getColumn(4).cellRenderer = BumpCellRenderer()
            columnModel.getColumn(5).cellRenderer = LinkCellRenderer()
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val col = columnAtPoint(e.point)
                    val row = rowAtPoint(e.point)
                    if (col == 5 && row >= 0) {
                        val pkg = safeModel.data[row].packageName
                        val links = packageLinks[pkg]
                        val url = links?.changelogUrl ?: links?.homePage
                        if (url != null) BrowserUtil.browse(url)
                    }
                }
            })
            addMouseMotionListener(object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val col = columnAtPoint(e.point)
                    val row = rowAtPoint(e.point)
                    cursor = if (col == 5 && row >= 0) {
                        val pkg = safeModel.data[row].packageName
                        val links = packageLinks[pkg]
                        if (links?.changelogUrl != null || links?.homePage != null) {
                            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        } else Cursor.getDefaultCursor()
                    } else Cursor.getDefaultCursor()
                }
            })
        }

        override fun getToolTipText(e: MouseEvent): String? {
            val row = rowAtPoint(e.point)
            val col = columnAtPoint(e.point)
            if (row < 0 || row >= safeModel.data.size) return null
            val update = safeModel.data[row]
            if (col == 1 && update.groupId != null) {
                val peers = GroupCascade.peersOf(safeModel.data, row)
                if (peers.isNotEmpty()) {
                    return formatGroupTooltip(peers)
                }
            }
            val links = packageLinks[update.packageName] ?: return null
            return formatStalenessTooltip(update.packageName, links)
        }
    }
    private val blockedTable = JBTable(blockedModel).apply {
        emptyText.text = "(no blocked updates)"
    }
    private val inconclusiveTable = JBTable(inconclusiveModel).apply {
        emptyText.text = "(no inconclusive updates)"
    }
    private val vulnTable = JBTable(vulnModel).apply {
        emptyText.text = "(no vulnerabilities found)"
        columnModel.getColumn(3).cellRenderer = SeverityCellRenderer()
        columnModel.getColumn(4).cellRenderer = RiskCellRenderer()
        autoResizeMode = JTable.AUTO_RESIZE_OFF
    }

    private val safeSeparator = TitledSeparator("Safe Updates")
    private val blockedSeparator = TitledSeparator("Blocked Updates")
    private val chainSeparator = TitledSeparator("Conflict Chain")
    private val inconclusiveSeparator = TitledSeparator("Inconclusive Updates")
    private val vulnSeparator = TitledSeparator("Vulnerabilities")
    private val stepsSeparator = TitledSeparator("Ordered Steps")

    private val batchBar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, 28)
        add(linkButton("All") { safeModel.selectAll(); fixCheckboxColumnWidth() })
        add(linkButton("Patch") { safeModel.selectByBump("patch"); fixCheckboxColumnWidth() })
        add(linkButton("Minor + Patch") { safeModel.selectByBump("minor", "patch"); fixCheckboxColumnWidth() })
        add(linkButton("None") { safeModel.deselectAll(); fixCheckboxColumnWidth() })
    }

    private val cascadeBannerLabel = javax.swing.JLabel("")
    private val cascadeBanner = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
        alignmentX = LEFT_ALIGNMENT
        background = JBColor(Color(234, 246, 255), Color(40, 50, 60))
        isOpaque = true
        maximumSize = Dimension(Int.MAX_VALUE, 28)
        add(cascadeBannerLabel)
        add(linkButton("Dismiss") { isVisible = false })
        isVisible = false
    }
    private var cascadeBannerShownThisSession = false

    private val safeScroll = JBScrollPane(safeTable)
    private val blockedScroll = JBScrollPane(blockedTable)
    private val chainScroll = JBScrollPane(conflictChainTree)
    private val inconclusiveScroll = JBScrollPane(inconclusiveTable)
    private val vulnScroll = JBScrollPane(vulnTable)
    private val stepsScroll = JBScrollPane(stepsArea)

    private val safeSection = section(safeSeparator, batchBar, cascadeBanner, safeScroll)
    private val blockedSection = section(blockedSeparator, blockedScroll)
    private val chainSection = section(chainSeparator, chainScroll)
    private val inconclusiveSection = section(inconclusiveSeparator, inconclusiveScroll)
    private val vulnSection = section(vulnSeparator, vulnScroll)
    private val stepsSection = section(stepsSeparator, stepsScroll)

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        safeModel.onCascadeFired = cb@{ primary, peers ->
            if (cascadeBannerShownThisSession || peers.isEmpty()) return@cb
            val primaryName = safeModel.data.getOrNull(primary)?.packageName ?: return@cb
            val peerNames = peers.mapNotNull { safeModel.data.getOrNull(it)?.packageName }
            if (peerNames.isEmpty()) return@cb
            cascadeBannerShownThisSession = true
            cascadeBannerLabel.text = formatCascadeBanner(primaryName, peerNames)
            cascadeBanner.isVisible = true
            cascadeBanner.revalidate()
        }

        blockedTable.selectionModel.addListSelectionListener { e ->
            if (e.valueIsAdjusting) return@addListSelectionListener
            val row = blockedTable.selectedRow
            if (row < 0 || row >= blockedModel.data.size) {
                conflictChainTree.model = javax.swing.tree.DefaultTreeModel(
                    javax.swing.tree.DefaultMutableTreeNode("Select a blocked package")
                )
                return@addListSelectionListener
            }
            val blocked = blockedModel.data[row]
            val chain = blocked.conflictChain
            if (chain == null) {
                conflictChainTree.model = javax.swing.tree.DefaultTreeModel(
                    javax.swing.tree.DefaultMutableTreeNode("(no conflict chain data)")
                )
            } else {
                conflictChainTree.model = DependencyTreeBuilder.buildConflictChainTree(
                    blocked.packageName, blocked.targetVersion, chain
                )
                for (i in 0 until conflictChainTree.rowCount) {
                    conflictChainTree.expandRow(i)
                }
            }
        }

        add(vulnSection)
        add(safeSection)
        add(blockedSection)
        add(chainSection)
        add(inconclusiveSection)
        add(stepsSection)

        vulnSection.isVisible = false
    }

    fun getSelectedSafeUpdates(): List<SafeUpdate> {
        return safeModel.data.filterIndexed { i, _ -> safeModel.selected[i] }
    }

    fun update(plan: UpgradePlan) {
        cascadeBannerShownThisSession = false
        cascadeBanner.isVisible = false
        safeModel.data = plan.safeUpdates
        blockedModel.data = plan.blockedUpdates
        inconclusiveModel.data = plan.inconclusiveUpdates

        safeSeparator.text = "Safe Updates (${plan.safeUpdates.size})"
        blockedSeparator.text = "Blocked Updates (${plan.blockedUpdates.size})"
        inconclusiveSeparator.text = "Inconclusive Updates (${plan.inconclusiveUpdates.size})"

        stepsArea.text = plan.orderedSteps.joinToString("\n") { "${it.step}. ${it.description}" }

        fixCheckboxColumnWidth()
        autoSizeColumns(safeTable, skipColumns = setOf(0, 5))
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

    fun updateVulnerabilities(audit: AuditResult) {
        val vulnPackages = audit.packages.filter { it.vulnerabilities.isNotEmpty() }
        vulnModel.data = vulnPackages
        val totalVulns = vulnPackages.sumOf { it.vulnerabilities.size }
        vulnSeparator.text = "Vulnerabilities ($totalVulns)"
        vulnSection.isVisible = vulnPackages.isNotEmpty()
        if (vulnPackages.isNotEmpty()) {
            autoSizeColumns(vulnTable)
            sizeToContent(vulnScroll, vulnTable, maxRows = 10)
        }
        revalidate()
        repaint()
    }

    fun updateLinks(enrich: EnrichResult) {
        packageLinks = enrich.packages
        safeModel.fireTableDataChanged()
        fixCheckboxColumnWidth()
        revalidate()
        repaint()
    }

    fun clear() {
        cascadeBannerShownThisSession = false
        cascadeBanner.isVisible = false
        safeModel.data = emptyList()
        blockedModel.data = emptyList()
        inconclusiveModel.data = emptyList()
        vulnModel.data = emptyList()
        packageLinks = emptyMap()
        safeSeparator.text = "Safe Updates"
        blockedSeparator.text = "Blocked Updates"
        inconclusiveSeparator.text = "Inconclusive Updates"
        vulnSeparator.text = "Vulnerabilities"
        conflictChainTree.model = javax.swing.tree.DefaultTreeModel(
            javax.swing.tree.DefaultMutableTreeNode("")
        )
        stepsArea.text = ""
        safeSection.isVisible = false
        blockedSection.isVisible = false
        chainSection.isVisible = false
        inconclusiveSection.isVisible = false
        vulnSection.isVisible = false
        stepsSection.isVisible = false
        revalidate()
        repaint()
    }

    private fun fixCheckboxColumnWidth() {
        safeTable.columnModel.getColumn(0).apply {
            preferredWidth = 30
            maxWidth = 30
            minWidth = 30
        }
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
    }

    private class SafeTableModel : AbstractTableModel() {
        var data: List<SafeUpdate> = emptyList()
            set(value) {
                field = value
                selected = BooleanArray(value.size) { true }
                fireTableDataChanged()
            }
        var selected: BooleanArray = BooleanArray(0)

        /** Invoked when a single-row toggle cascaded to peers. First arg is the
         * clicked row; second is the list of peer row indices that changed. */
        var onCascadeFired: ((Int, List<Int>) -> Unit)? = null

        fun selectAll() {
            selected.fill(true)
            fireTableDataChanged()
        }

        fun deselectAll() {
            selected.fill(false)
            fireTableDataChanged()
        }

        fun selectByBump(vararg levels: String) {
            for (i in data.indices) {
                selected[i] = bumpSeverity(data[i].fromVersion, data[i].toVersion) in levels
            }
            io.locklane.model.GroupCascade.enforceGroupCoherence(data, selected)
            fireTableDataChanged()
        }

        override fun getRowCount() = data.size
        override fun getColumnCount() = 6
        override fun getColumnName(col: Int) = when (col) {
            0 -> ""
            1 -> "Package"
            2 -> "From"
            3 -> "To"
            4 -> "Bump"
            5 -> "Links"
            else -> ""
        }
        override fun getColumnClass(col: Int): Class<*> = when (col) {
            0 -> java.lang.Boolean::class.java
            else -> String::class.java
        }
        override fun isCellEditable(row: Int, col: Int) = col == 0
        override fun setValueAt(value: Any?, row: Int, col: Int) {
            if (col == 0 && value is Boolean) {
                val changed = io.locklane.model.GroupCascade.toggleRow(data, selected, row, value)
                if (changed.size == 1) {
                    fireTableCellUpdated(row, col)
                } else if (changed.isNotEmpty()) {
                    fireTableDataChanged()
                    onCascadeFired?.invoke(row, changed.filter { it != row })
                }
            }
        }
        override fun getValueAt(row: Int, col: Int): Any = when (col) {
            0 -> selected[row]
            1 -> data[row].packageName
            2 -> data[row].fromVersion
            3 -> data[row].toVersion
            4 -> bumpSeverity(data[row].fromVersion, data[row].toVersion)
            5 -> "" // rendered by LinkCellRenderer
            else -> ""
        }
    }

    private class BlockedTableModel : AbstractTableModel() {
        var data: List<BlockedUpdate> = emptyList()
            set(value) { field = value; fireTableDataChanged() }

        override fun getRowCount() = data.size
        override fun getColumnCount() = 4
        override fun getColumnName(col: Int) = when (col) {
            0 -> "Package"
            1 -> "Target"
            2 -> "Reason"
            3 -> "Suggestion"
            else -> ""
        }
        override fun getValueAt(row: Int, col: Int): Any = when (col) {
            0 -> data[row].packageName
            1 -> data[row].targetVersion
            2 -> data[row].reason
            3 -> data[row].suggestion?.let { "try $it" } ?: ""
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

    private class VulnerabilityTableModel : AbstractTableModel() {
        var data: List<PackageAudit> = emptyList()
            set(value) { field = value; fireTableDataChanged() }

        private data class FlatRow(val pkg: String, val version: String, val vulnId: String, val severity: String, val risk: String, val summary: String)

        private val rows: List<FlatRow>
            get() = data.flatMap { pa ->
                pa.vulnerabilities.map { v ->
                    FlatRow(pa.packageName, pa.version, v.id, v.severity, classifySeverity(v.severity), v.summary)
                }
            }

        override fun getRowCount() = rows.size
        override fun getColumnCount() = 6
        override fun getColumnName(col: Int) = when (col) {
            0 -> "Package"
            1 -> "Version"
            2 -> "Vuln ID"
            3 -> "Severity"
            4 -> "Risk"
            5 -> "Summary"
            else -> ""
        }
        override fun getValueAt(row: Int, col: Int): Any {
            val r = rows[row]
            return when (col) {
                0 -> r.pkg
                1 -> r.version
                2 -> r.vulnId
                3 -> r.severity
                4 -> r.risk
                5 -> r.summary
                else -> ""
            }
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

    private class SeverityCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, col: Int,
        ): Component {
            val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col)
            if (!isSelected) {
                foreground = riskColor(classifySeverity(value as? String ?: ""), table.foreground)
            }
            return comp
        }
    }

    private class RiskCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, col: Int,
        ): Component {
            val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col)
            if (!isSelected) {
                foreground = riskColor(value as? String ?: "", table.foreground)
            }
            return comp
        }
    }

    private inner class PackageCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, col: Int,
        ): Component {
            val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col)
            val update = safeModel.data.getOrNull(row)
            text = if (update?.groupId != null) {
                "${value ?: ""}  (linked)"
            } else {
                value?.toString() ?: ""
            }
            font = if (update?.groupId != null) {
                font.deriveFont(Font.ITALIC)
            } else {
                font.deriveFont(Font.PLAIN)
            }
            return comp
        }
    }

    private inner class LinkCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, col: Int,
        ): Component {
            val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col)
            val pkg = safeModel.data.getOrNull(row)?.packageName
            val links = if (pkg != null) packageLinks[pkg] else null
            if (links?.changelogUrl != null) {
                text = "changelog"
                if (!isSelected) foreground = JBColor(Color(70, 130, 220), Color(100, 160, 255))
            } else if (links?.homePage != null) {
                text = "home"
                if (!isSelected) foreground = JBColor(Color(70, 130, 220), Color(100, 160, 255))
            } else {
                text = ""
            }
            return comp
        }
    }

    companion object {
        private const val TABLE_HEADER_HEIGHT = 28
        private const val TABLE_PADDING = 4

        private fun linkButton(text: String, action: () -> Unit): javax.swing.JButton {
            return javax.swing.JButton(text).apply {
                putClientProperty("JButton.buttonType", "roundRect")
                font = font.deriveFont(Font.PLAIN, 11f)
                addActionListener { action() }
            }
        }

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

        private fun section(separator: TitledSeparator, vararg contents: JComponent): JPanel {
            return JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                alignmentX = LEFT_ALIGNMENT
                separator.alignmentX = LEFT_ALIGNMENT
                separator.maximumSize = Dimension(Int.MAX_VALUE, separator.preferredSize.height)
                add(separator)
                for (c in contents) {
                    c.alignmentX = LEFT_ALIGNMENT
                    add(c)
                }
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

        fun classifySeverity(severity: String): String {
            val upper = severity.uppercase()
            if (upper.contains("CRITICAL")) return "Critical"
            if (upper.contains("HIGH")) return "High"
            if (upper.contains("MEDIUM") || upper.contains("MODERATE")) return "Medium"
            if (upper.contains("LOW")) return "Low"

            // Parse CVSS vector for impact indicators
            if (upper.startsWith("CVSS:")) {
                val hasCriticalImpact = upper.contains("/C:H") && upper.contains("/I:H") && upper.contains("/A:H")
                val hasHighImpact = upper.contains("/C:H") || upper.contains("/I:H") || upper.contains("/A:H") ||
                    upper.contains("/VC:H") || upper.contains("/VI:H") || upper.contains("/VA:H")
                val hasMediumImpact = upper.contains("/C:L") || upper.contains("/I:L") || upper.contains("/A:L")

                return when {
                    hasCriticalImpact && upper.contains("/AC:L") && upper.contains("/PR:N") -> "Critical"
                    hasHighImpact -> "High"
                    hasMediumImpact -> "Medium"
                    else -> "Low"
                }
            }

            return "Unknown"
        }

        private fun riskColor(risk: String, defaultColor: java.awt.Color): java.awt.Color = when (risk) {
            "Critical" -> JBColor(Color(220, 40, 40), Color(230, 70, 70))
            "High" -> JBColor(Color(230, 140, 30), Color(240, 160, 50))
            "Medium" -> JBColor(Color(200, 170, 50), Color(220, 190, 70))
            "Low" -> JBColor(Color(80, 180, 80), Color(100, 200, 100))
            else -> defaultColor
        }

        fun formatGroupTooltip(peers: List<String>): String {
            val peerList = peers.joinToString(", ") { "<b>$it</b>" }
            return "<html>Must update together with $peerList.<br>" +
                "<small>The planner verified this set resolves as a unit; applying a subset would break the lockfile.</small></html>"
        }

        fun formatCascadeBanner(primary: String, peers: List<String>): String {
            val also = peers.joinToString(", ") { "`$it`" }
            val verb = if (peers.size == 1) "was" else "were"
            return "<html>`$also` $verb also toggled because it must update together with <b>$primary</b>.</html>"
        }

        fun formatStalenessTooltip(pkg: String, links: PackageLinks): String? {
            val currentDate = links.currentVersionDate ?: return null
            val latestVer = links.latestVersion ?: return null
            val latestDate = links.latestVersionDate ?: return null
            return try {
                val current = java.time.Instant.parse(currentDate)
                val latest = java.time.Instant.parse(latestDate)
                val age = java.time.Duration.between(current, latest)
                val days = age.toDays()
                val ageStr = when {
                    days < 30 -> "${days}d behind"
                    days < 365 -> "${days / 30}mo behind"
                    else -> "${days / 365}y ${(days % 365) / 30}mo behind"
                }
                "<html><b>$pkg</b><br>Latest: $latestVer ($ageStr)</html>"
            } catch (_: Exception) {
                null
            }
        }
    }
}
