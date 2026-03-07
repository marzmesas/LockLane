package io.locklane.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty

class LocklaneConfigurable(private val project: Project) : BoundConfigurable("Locklane") {

    private val settings get() = LocklaneSettings.getInstance(project)

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

        group("Verification") {
            row("Verify command:") {
                textField()
                    .bindText(settings.state::verifyCommand)
                    .comment("Command to run for verification (e.g., pytest)")
                    .align(AlignX.FILL)
            }
        }
    }
}
