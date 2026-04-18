package io.locklane.util

import io.locklane.model.UpgradePlan
import io.locklane.ui.PlanResultPanel

object PlanMarkdownExporter {

    fun export(plan: UpgradePlan): String = buildString {
        appendLine("## LockLane Upgrade Plan")
        appendLine()
        appendLine("**Manifest**: `${plan.manifestPath.substringAfterLast("/")}`  ")
        appendLine("**Generated**: ${plan.timestampUtc}  ")
        appendLine("**Resolver**: ${plan.resolver}")
        appendLine()

        if (plan.safeUpdates.isNotEmpty()) {
            appendLine("### Safe Updates (${plan.safeUpdates.size})")
            appendLine()
            appendLine("| Package | From | To | Bump |")
            appendLine("|---------|------|----|------|")
            for (u in plan.safeUpdates) {
                val bump = PlanResultPanel.bumpSeverity(u.fromVersion, u.toVersion)
                appendLine("| ${u.packageName} | ${u.fromVersion} | ${u.toVersion} | $bump |")
            }
            appendLine()
        }

        if (plan.blockedUpdates.isNotEmpty()) {
            appendLine("### Blocked Updates (${plan.blockedUpdates.size})")
            appendLine()
            appendLine("| Package | Target | Reason | Suggestion |")
            appendLine("|---------|--------|--------|------------|")
            for (b in plan.blockedUpdates) {
                val reason = b.reason.take(80).replace("|", "\\|")
                val suggestion = b.suggestion?.let { "try $it" } ?: ""
                appendLine("| ${b.packageName} | ${b.targetVersion} | $reason | $suggestion |")
            }
            appendLine()
        }

        if (plan.inconclusiveUpdates.isNotEmpty()) {
            appendLine("### Inconclusive Updates (${plan.inconclusiveUpdates.size})")
            appendLine()
            appendLine("| Package | Target | Reason |")
            appendLine("|---------|--------|--------|")
            for (i in plan.inconclusiveUpdates) {
                val reason = i.reason.take(80).replace("|", "\\|")
                appendLine("| ${i.packageName} | ${i.targetVersion} | $reason |")
            }
            appendLine()
        }

        if (plan.orderedSteps.isNotEmpty()) {
            appendLine("### Ordered Steps")
            appendLine()
            for (s in plan.orderedSteps) {
                appendLine("${s.step}. ${s.description}")
            }
            appendLine()
        }
    }
}
