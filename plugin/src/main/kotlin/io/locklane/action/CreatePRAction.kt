package io.locklane.action

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import io.locklane.util.PlanMarkdownExporter
import java.io.File

class CreatePRAction : AnAction("Create PR", "Create a GitHub pull request with applied updates", AllIcons.Vcs.Branch) {

    private val log = Logger.getInstance(CreatePRAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val panel = findLockLanePanel(project) ?: return
        val plan = panel.state.lastPlan ?: return
        val manifest = panel.state.manifestPath ?: return
        val apply = panel.state.lastApply ?: run {
            panel.showError("Create PR", "Apply the plan first before creating a PR.")
            return
        }
        if (apply.dryRun || apply.apply?.applied != true) {
            panel.showError("Create PR", "Apply the plan first (not dry-run) before creating a PR.")
            return
        }

        val workDir = manifest.parent?.toFile() ?: return

        // Check gh is available
        if (!isGhAvailable(workDir)) {
            panel.showError("Create PR", "GitHub CLI (gh) not found. Install it from https://cli.github.com/")
            return
        }

        panel.setBusy(true)

        object : Task.Backgroundable(project, "LockLane: Creating pull request...", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val safe = plan.safeUpdates.size
                val branchName = "locklane/update-deps-${System.currentTimeMillis() / 1000}"
                val title = "Update $safe dependencies via LockLane"
                val body = PlanMarkdownExporter.export(plan)

                try {
                    // Create branch
                    indicator.text = "Creating branch..."
                    runGit(workDir, "checkout", "-b", branchName)

                    // Stage and commit
                    indicator.text = "Committing changes..."
                    runGit(workDir, "add", manifest.fileName.toString())
                    // Also stage lock file if modified
                    val lockFiles = listOf("Cargo.lock", "requirements.txt", "uv.lock", "poetry.lock")
                    for (lf in lockFiles) {
                        val lockPath = File(workDir, lf)
                        if (lockPath.isFile) {
                            runGit(workDir, "add", lf)
                        }
                    }
                    runGit(workDir, "commit", "-m", "Update $safe dependencies via LockLane")

                    // Push
                    indicator.text = "Pushing branch..."
                    runGit(workDir, "push", "-u", "origin", branchName)

                    // Create PR
                    indicator.text = "Creating pull request..."
                    val prUrl = runGh(workDir, "pr", "create", "--title", title, "--body", body)

                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        panel.setBusy(false)
                        panel.notifySuccess("Pull request created", prUrl.trim())
                    }
                } catch (ex: Exception) {
                    // Try to clean up: go back to previous branch
                    try { runGit(workDir, "checkout", "-") } catch (_: Exception) {}
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        panel.setBusy(false)
                        panel.showError("PR creation failed", ex.message ?: "Unknown error")
                    }
                }
            }

            override fun onCancel() {
                panel.setBusy(false)
            }
        }.queue()
    }

    private fun isGhAvailable(workDir: File): Boolean {
        return try {
            val p = ProcessBuilder("gh", "--version").directory(workDir).start()
            p.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun runGit(workDir: File, vararg args: String): String {
        val cmd = listOf("git") + args.toList()
        val p = ProcessBuilder(cmd).directory(workDir).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().readText()
        val exitCode = p.waitFor()
        if (exitCode != 0) throw RuntimeException("git ${args.first()} failed: $output")
        return output
    }

    private fun runGh(workDir: File, vararg args: String): String {
        val cmd = listOf("gh") + args.toList()
        val p = ProcessBuilder(cmd).directory(workDir).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().readText()
        val exitCode = p.waitFor()
        if (exitCode != 0) throw RuntimeException("gh ${args.first()} failed: $output")
        return output
    }

    override fun update(e: AnActionEvent) {
        val panel = e.project?.let { findLockLanePanel(it) }
        e.presentation.isEnabled = panel?.state?.lastApply?.apply?.applied == true && panel?.state?.busy != true
    }
}
