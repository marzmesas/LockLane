package io.locklane.editor

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.locklane.model.SafeUpdate
import io.locklane.service.LocklaneProjectState
import io.locklane.ui.PlanResultPanel
import javax.swing.Icon

class DependencyLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // Only process leaf elements to avoid duplicates
        if (element.children.isNotEmpty()) return null

        val file = element.containingFile ?: return null
        if (!isManifestFile(file)) return null

        val project = element.project
        val state = LocklaneProjectState.getInstance(project)
        val plan = state.lastPlan ?: return null
        val manifestPath = state.manifestPath ?: return null

        // Check this file matches the scanned manifest
        val vFile = file.virtualFile ?: return null
        if (vFile.path != manifestPath.toString()) return null

        val document = element.containingFile?.viewProvider?.document ?: return null
        val lineNumber = document.getLineNumber(element.textRange.startOffset)
        val lineStart = document.getLineStartOffset(lineNumber)

        // Only process the first element on each line
        if (element.textRange.startOffset != lineStart) {
            // Check if this is the first non-whitespace element on the line
            val lineText = document.getText(com.intellij.openapi.util.TextRange(lineStart, element.textRange.startOffset))
            if (lineText.isNotBlank()) return null
        }

        val fullLineEnd = document.getLineEndOffset(lineNumber)
        val lineText = document.getText(com.intellij.openapi.util.TextRange(lineStart, fullLineEnd)).trim()
        if (lineText.isEmpty() || lineText.startsWith("#") || lineText.startsWith("[")) return null

        // Match against safe updates
        val safeMatch = findSafeUpdate(lineText, plan.safeUpdates)
        if (safeMatch != null) {
            val severity = PlanResultPanel.bumpSeverity(safeMatch.fromVersion, safeMatch.toVersion)
            val icon = when (severity) {
                "major" -> AllIcons.General.Error
                "minor" -> AllIcons.General.Warning
                else -> AllIcons.General.InspectionsOK
            }
            val tooltip = "${safeMatch.packageName}: ${safeMatch.fromVersion} -> ${safeMatch.toVersion} ($severity)"
            return createMarker(element, icon, tooltip)
        }

        // Match against blocked updates
        val blockedMatch = plan.blockedUpdates.firstOrNull { matchesLine(lineText, it.packageName) }
        if (blockedMatch != null) {
            val tooltip = "${blockedMatch.packageName}: blocked — ${blockedMatch.reason}"
            return createMarker(element, AllIcons.General.Error, tooltip)
        }

        return null
    }

    private fun isManifestFile(file: PsiFile): Boolean {
        val name = file.name
        return name.endsWith(".txt") || name.endsWith(".in") || name == "pyproject.toml"
    }

    private fun findSafeUpdate(lineText: String, updates: List<SafeUpdate>): SafeUpdate? {
        return updates.firstOrNull { matchesLine(lineText, it.packageName) }
    }

    private fun matchesLine(lineText: String, packageName: String): Boolean {
        val lower = lineText.lowercase()
        val pkgLower = packageName.lowercase()

        // requirements.txt style: "package==1.0.0" or "package>=1.0"
        for (op in listOf("==", ">=", "<=", "~=", "!=", ">", "<")) {
            if (lower.startsWith("$pkgLower$op") || lower.startsWith("$pkgLower[$") || lower.startsWith("$pkgLower ")) {
                return true
            }
        }

        // pyproject.toml PEP 621 style: '"package>=1.0"'
        if (lower.contains("\"$pkgLower") || lower.contains("'$pkgLower")) {
            return true
        }

        // pyproject.toml Poetry style: 'package = "^1.0"'
        val keyMatch = Regex("""^['"]?${Regex.escape(pkgLower)}['"]?\s*=""", RegexOption.IGNORE_CASE)
        if (keyMatch.containsMatchIn(lower)) {
            return true
        }

        return false
    }

    private fun createMarker(element: PsiElement, icon: Icon, tooltip: String): LineMarkerInfo<PsiElement> {
        return LineMarkerInfo(
            element,
            element.textRange,
            icon,
            { tooltip },
            null,
            GutterIconRenderer.Alignment.LEFT,
            { tooltip },
        )
    }
}
