package io.locklane.action

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.vfs.LocalFileSystem

class SelectManifestAction : AnAction("Select Manifest", "Choose a dependency manifest file", AllIcons.Actions.MenuOpen) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val panel = findLocklanePanel(project) ?: return

        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withTitle("Select Dependency Manifest")
            .withDescription("Choose a requirements.txt, requirements.in, or pyproject.toml file")
            .withFileFilter { it.extension in listOf("txt", "in", "toml") }

        val projectDir = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val file = FileChooser.chooseFile(descriptor, project, projectDir) ?: return
        panel.setManifest(file.toNioPath())
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}
