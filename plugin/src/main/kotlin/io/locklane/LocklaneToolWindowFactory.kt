package io.locklane

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import io.locklane.action.ApplyPlanAction
import io.locklane.action.RunPlanAction
import io.locklane.action.SelectManifestAction
import io.locklane.action.VerifyPlanAction
import io.locklane.ui.LocklanePanel
import java.awt.BorderLayout
import javax.swing.JPanel

class LocklaneToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = LocklanePanel(project)

        val actionGroup = DefaultActionGroup().apply {
            add(SelectManifestAction())
            add(Separator())
            add(RunPlanAction())
            add(VerifyPlanAction())
            add(ApplyPlanAction())
        }

        val toolbar = ActionManager.getInstance()
            .createActionToolbar("LocklaneToolbar", actionGroup, true)
        toolbar.targetComponent = panel

        val wrapper = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(panel, BorderLayout.CENTER)
        }

        val content = ContentFactory.getInstance().createContent(wrapper, "", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
