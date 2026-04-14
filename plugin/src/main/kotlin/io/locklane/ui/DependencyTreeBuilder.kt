package io.locklane.ui

import io.locklane.model.ConflictChain
import io.locklane.model.ResolvedPackage
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

data class DependencyNode(
    val packageName: String,
    val version: String,
    val detail: String,
    val isRoot: Boolean = false,
    val isSummary: Boolean = false,
    val isDirect: Boolean = false,
) {
    override fun toString(): String = when {
        isSummary -> detail
        version.isNotBlank() -> "$packageName $version"
        else -> packageName
    }
}

object DependencyTreeBuilder {

    /**
     * Build a tree from a ConflictChain for a blocked package.
     */
    fun buildConflictChainTree(
        blockedPackage: String,
        targetVersion: String,
        chain: ConflictChain,
    ): DefaultTreeModel {
        val root = DefaultMutableTreeNode(
            DependencyNode(blockedPackage, targetVersion, "Blocked", isRoot = true)
        )
        root.add(
            DefaultMutableTreeNode(
                DependencyNode("Summary", "", chain.summary, isSummary = true)
            )
        )
        for (link in chain.links) {
            root.add(
                DefaultMutableTreeNode(
                    DependencyNode(
                        link.packageName,
                        "",
                        "${link.constraint} (required by ${link.requiredBy})",
                    )
                )
            )
        }
        return DefaultTreeModel(root)
    }

    /**
     * Build a full dependency graph tree from resolution packages.
     * Root nodes = direct dependencies. Children = transitive deps.
     */
    fun buildResolutionTree(packages: List<ResolvedPackage>): DefaultTreeModel {
        val root = DefaultMutableTreeNode("Dependencies (${packages.size})")

        // Build forward edges: parent -> children
        val children = mutableMapOf<String, MutableList<ResolvedPackage>>()
        for (pkg in packages) {
            for (parent in pkg.requiredBy) {
                children.getOrPut(parent.lowercase()) { mutableListOf() }.add(pkg)
            }
        }

        // Direct dependencies as top-level nodes
        val directDeps = packages.filter { it.isDirect }.sortedBy { it.name.lowercase() }
        for (dep in directDeps) {
            root.add(buildPackageSubtree(dep, children, mutableSetOf()))
        }

        // Orphan transitive deps
        val orphans = packages.filter { !it.isDirect && it.requiredBy.isEmpty() }
        if (orphans.isNotEmpty()) {
            val orphanRoot = DefaultMutableTreeNode(
                DependencyNode("(unlinked)", "", "${orphans.size} packages")
            )
            for (pkg in orphans.sortedBy { it.name }) {
                orphanRoot.add(
                    DefaultMutableTreeNode(DependencyNode(pkg.name, pkg.version, ""))
                )
            }
            root.add(orphanRoot)
        }

        return DefaultTreeModel(root)
    }

    private fun buildPackageSubtree(
        pkg: ResolvedPackage,
        children: Map<String, MutableList<ResolvedPackage>>,
        visited: MutableSet<String>,
    ): DefaultMutableTreeNode {
        val node = DefaultMutableTreeNode(
            DependencyNode(pkg.name, pkg.version, "", isDirect = pkg.isDirect)
        )
        if (!visited.add(pkg.name.lowercase())) {
            node.add(DefaultMutableTreeNode(DependencyNode("(circular)", "", "")))
            return node
        }
        val deps = children[pkg.name.lowercase()] ?: emptyList()
        for (child in deps.sortedBy { it.name.lowercase() }) {
            node.add(buildPackageSubtree(child, children, visited))
        }
        visited.remove(pkg.name.lowercase())
        return node
    }
}
