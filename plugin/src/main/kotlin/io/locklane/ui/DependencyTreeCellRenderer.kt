package io.locklane.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JTree

class DependencyTreeCellRenderer : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        val node = (value as? javax.swing.tree.DefaultMutableTreeNode)?.userObject

        when (node) {
            is DependencyNode -> {
                when {
                    node.isRoot -> {
                        icon = AllIcons.Nodes.PpLibFolder
                        append(node.packageName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                        if (node.version.isNotBlank()) {
                            append(" ${node.version}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                        if (node.detail.isNotBlank()) {
                            append(" — ${node.detail}", SimpleTextAttributes(
                                SimpleTextAttributes.STYLE_PLAIN,
                                JBColor(java.awt.Color(220, 80, 80), java.awt.Color(220, 100, 100)),
                            ))
                        }
                    }
                    node.isSummary -> {
                        icon = AllIcons.General.Information
                        append(node.detail, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    }
                    node.packageName == "(circular)" -> {
                        icon = AllIcons.General.Warning
                        append("(circular reference)", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    }
                    node.isDirect -> {
                        icon = AllIcons.Nodes.PpLib
                        append(node.packageName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                        if (node.version.isNotBlank()) {
                            append(" ${node.version}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                    }
                    else -> {
                        icon = AllIcons.Nodes.PpLib
                        append(node.packageName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        if (node.version.isNotBlank()) {
                            append(" ${node.version}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                        if (node.detail.isNotBlank()) {
                            append(" — ${node.detail}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                    }
                }
            }
            is String -> {
                icon = AllIcons.Nodes.PpLibFolder
                append(node, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            }
        }
    }
}
