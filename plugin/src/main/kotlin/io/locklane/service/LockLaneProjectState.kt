package io.locklane.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import io.locklane.model.UpgradePlan
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class LockLaneProjectState {

    var lastPlan: UpgradePlan? = null
    var lastPlanJson: Path? = null
    var manifestPath: Path? = null

    companion object {
        fun getInstance(project: Project): LockLaneProjectState =
            project.getService(LockLaneProjectState::class.java)
    }
}
