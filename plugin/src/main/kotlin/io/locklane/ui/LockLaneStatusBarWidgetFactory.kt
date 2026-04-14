package io.locklane.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

class LockLaneStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = LockLaneStatusBarWidget.ID

    override fun getDisplayName(): String = "LockLane Status"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget =
        LockLaneStatusBarWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {}

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
