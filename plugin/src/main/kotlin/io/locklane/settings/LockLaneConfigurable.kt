package io.locklane.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import io.locklane.service.PythonDiscovery
import javax.swing.JLabel

class LockLaneConfigurable(private val project: Project) : BoundConfigurable("LockLane") {

    private val settings get() = LockLaneSettings.getInstance(project)
    private val validationLabel = JLabel("")

    override fun createPanel() = panel {
        group("Python") {
            row("Interpreter path:") {
                textField()
                    .bindText(settings.state::pythonPath)
                    .comment("Leave empty for auto-detection (venv, then PATH)")
                    .align(AlignX.FILL)
            }
        }

        group("Resolver") {
            row("Resolver:") {
                comboBox(listOf("uv", "pip-tools"))
                    .bindItem(settings.state::resolverPreference.toNullableProperty())
            }
            row("Timeout (seconds):") {
                intTextField(1..3600)
                    .bindIntText(settings.state::timeoutSeconds)
            }
            row("Resolver source path:") {
                textField()
                    .bindText(settings.state::resolverSourcePath)
                    .comment("Override bundled resolver with a local path (for development). Leave empty to use bundled.")
                    .align(AlignX.FILL)
            }
        }

        group("Indexes") {
            row("Extra index URLs:") {
                textArea()
                    .bindText(
                        { settings.state.extraIndexUrls.joinToString("\n") },
                        { settings.state.extraIndexUrls = it.lines().filter(String::isNotBlank).toMutableList() },
                    )
                    .comment("One URL per line")
                    .align(AlignX.FILL)
            }
        }

        group("Ignored Packages") {
            row("Packages to skip:") {
                textArea()
                    .bindText(
                        { settings.state.ignoredPackages.joinToString("\n") },
                        { settings.state.ignoredPackages = it.lines().filter(String::isNotBlank).toMutableList() },
                    )
                    .comment("One package name per line. These will be excluded from upgrade plans.")
                    .align(AlignX.FILL)
            }
        }

        group("Scanning") {
            row {
                checkBox("Auto-scan dependencies on project open")
                    .bindSelected(settings.state::autoScanEnabled)
            }
        }

        group("Verification") {
            row("Verify command:") {
                textField()
                    .bindText(settings.state::verifyCommand)
                    .comment("Command to run for verification (e.g., pytest)")
                    .align(AlignX.FILL)
            }
        }

        group("Diagnostics") {
            row {
                button("Validate Setup") { runValidation() }
            }
            row {
                cell(validationLabel)
            }
        }
    }

    private fun runValidation() {
        validationLabel.text = "Checking..."

        val messages = mutableListOf<String>()

        // Check Python
        val pythonPath = PythonDiscovery.findPython(
            configuredPath = settings.state.pythonPath.ifBlank { null },
            projectBasePath = project.basePath,
        )
        if (pythonPath == null) {
            messages += "\u2718 No Python interpreter found"
        } else if (!PythonDiscovery.validatePython(pythonPath)) {
            messages += "\u2718 Python found at $pythonPath but not a valid Python 3"
        } else {
            messages += "\u2714 Python: $pythonPath"
        }

        // Check resolver tools
        val uvPath = PythonDiscovery.findOnPath("uv")
        val pipCompilePath = PythonDiscovery.findOnPath("pip-compile")
        if (uvPath != null) {
            messages += "\u2714 uv: $uvPath"
        } else {
            messages += "\u2718 uv not found on PATH"
        }
        if (pipCompilePath != null) {
            messages += "\u2714 pip-compile: $pipCompilePath"
        } else {
            messages += "\u2718 pip-compile not found on PATH"
        }

        if (uvPath == null && pipCompilePath == null) {
            messages += "\u26a0 No resolver tool available — install uv or pip-tools"
        }

        validationLabel.text = "<html>${messages.joinToString("<br>")}</html>"
    }
}
