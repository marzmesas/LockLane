package io.locklane.ui

import com.intellij.openapi.project.Project
import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

class LocklanePanel(project: Project) : JPanel(BorderLayout()) {

    init {
        name = "LocklanePanel"
        add(
            JLabel(
                "<html><body style='text-align:center;'>" +
                    "<h2>Locklane</h2>" +
                    "<p>Phase 1 bootstrap complete.</p>" +
                    "<p>Project: ${project.name}</p>" +
                    "</body></html>",
                SwingConstants.CENTER,
            ),
            BorderLayout.CENTER,
        )
    }
}

