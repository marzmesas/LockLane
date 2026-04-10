package io.locklane.action

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.vfs.LocalFileSystem

class SelectManifestAction : AnAction("Select Manifest", "Choose dependency manifest files", AllIcons.Actions.MenuOpen) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tabManager = findManifestTabManager(project) ?: return

        val descriptor = FileChooserDescriptor(true, false, false, false, false, true)
            .withTitle("Select Dependency Manifests")
            .withDescription("Choose dependency manifests (requirements.txt, pyproject.toml, Cargo.toml)")
            .withFileFilter { it.extension in listOf("txt", "in", "toml") }

        val projectDir = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val files = FileChooser.chooseFiles(descriptor, project, projectDir)
        for (file in files) {
            tabManager.addManifest(file.toNioPath())
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}
