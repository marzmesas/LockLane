package io.locklane.ui

import io.locklane.model.ApplyResult
import io.locklane.model.UpgradePlan
import io.locklane.model.VerificationReport
import java.nio.file.Path

class PanelState {
    var manifestPath: Path? = null
    var lastPlan: UpgradePlan? = null
    var lastPlanJson: Path? = null
    var lastVerification: VerificationReport? = null
    var lastApply: ApplyResult? = null
    var busy: Boolean = false

    fun onManifestSelected(path: Path) {
        manifestPath = path
        lastPlan = null
        lastPlanJson = null
        lastVerification = null
        lastApply = null
    }

    fun onPlanCompleted(plan: UpgradePlan, planJson: Path) {
        lastPlan = plan
        lastPlanJson = planJson
        lastVerification = null
        lastApply = null
    }

    fun onVerifyCompleted(report: VerificationReport) {
        lastVerification = report
    }

    fun onApplyCompleted(result: ApplyResult) {
        lastApply = result
    }

    val canRunPlan get() = !busy && manifestPath != null
    val canVerify get() = !busy && lastPlanJson != null && manifestPath != null
    val canApply get() = !busy && lastPlanJson != null && manifestPath != null
}
