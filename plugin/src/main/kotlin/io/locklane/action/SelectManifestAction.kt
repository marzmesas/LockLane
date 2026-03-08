package io.locklane.action

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.vfs.LocalFileSystem

class SelectManifestAction : AnAction("Select Manifest", "Choose a requirements manifest file", AllIcons.Actions.MenuOpen) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val panel = findLocklanePanel(project) ?: return

        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("txt")
            .withTitle("Select Requirements Manifest")
            .withDescription("Choose a requirements.txt or similar manifest file")

        val projectDir = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val file = FileChooser.chooseFile(descriptor, project, projectDir) ?: return
        panel.setManifest(file.toNioPath())
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}
