package io.locklane.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import io.locklane.model.AuditResult
import io.locklane.model.BaselineResult
import io.locklane.model.EnrichResult
import io.locklane.model.UpgradePlan
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class LockLaneProjectState {

    data class ManifestState(
        var plan: UpgradePlan? = null,
        var planJson: Path? = null,
        var audit: AuditResult? = null,
        var enrich: EnrichResult? = null,
        var baseline: BaselineResult? = null,
    )

    val manifests: MutableMap<Path, ManifestState> = mutableMapOf()

    fun getOrCreate(path: Path): ManifestState =
        manifests.getOrPut(path) { ManifestState() }

    // Convenience accessors for single-manifest backward compat
    var lastPlan: UpgradePlan?
        get() = manifests.values.firstOrNull()?.plan
        set(value) { manifests.values.firstOrNull()?.plan = value }

    var lastPlanJson: Path?
        get() = manifests.values.firstOrNull()?.planJson
        set(value) { manifests.values.firstOrNull()?.planJson = value }

    var manifestPath: Path?
        get() = manifests.keys.firstOrNull()
        set(value) {
            if (value != null && value !in manifests) {
                manifests[value] = ManifestState()
            }
        }

    var lastAudit: AuditResult?
        get() = manifests.values.firstOrNull()?.audit
        set(value) { manifests.values.firstOrNull()?.audit = value }

    var lastEnrich: EnrichResult?
        get() = manifests.values.firstOrNull()?.enrich
        set(value) { manifests.values.firstOrNull()?.enrich = value }

    companion object {
        fun getInstance(project: Project): LockLaneProjectState =
            project.getService(LockLaneProjectState::class.java)
    }
}
