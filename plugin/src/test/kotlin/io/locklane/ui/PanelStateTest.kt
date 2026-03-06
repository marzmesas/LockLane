package io.locklane.ui

import io.locklane.model.ApplyResult
import io.locklane.model.UpgradePlan
import io.locklane.model.VerificationReport
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PanelStateTest {

    @Test
    fun `initial state has no manifest and is not busy`() {
        val state = PanelState()
        assertNull(state.manifestPath)
        assertNull(state.lastPlan)
        assertNull(state.lastPlanJson)
        assertNull(state.lastVerification)
        assertNull(state.lastApply)
        assertFalse(state.busy)
    }

    @Test
    fun `setManifest clears downstream state`() {
        val state = PanelState()
        val manifest = Path.of("/tmp/requirements.txt")
        val planJson = Path.of("/tmp/plan.json")
        state.onPlanCompleted(UpgradePlan(), planJson)
        state.onVerifyCompleted(VerificationReport())
        state.onApplyCompleted(ApplyResult())

        state.onManifestSelected(manifest)

        assertEquals(manifest, state.manifestPath)
        assertNull(state.lastPlan)
        assertNull(state.lastPlanJson)
        assertNull(state.lastVerification)
        assertNull(state.lastApply)
    }

    @Test
    fun `onPlanCompleted clears verification and apply`() {
        val state = PanelState()
        val manifest = Path.of("/tmp/requirements.txt")
        val planJson = Path.of("/tmp/plan.json")
        state.onManifestSelected(manifest)
        state.onVerifyCompleted(VerificationReport())
        state.onApplyCompleted(ApplyResult())

        val plan = UpgradePlan()
        state.onPlanCompleted(plan, planJson)

        assertEquals(plan, state.lastPlan)
        assertEquals(planJson, state.lastPlanJson)
        assertNull(state.lastVerification)
        assertNull(state.lastApply)
    }

    @Test
    fun `canRunPlan requires manifest and not busy`() {
        val state = PanelState()
        assertFalse(state.canRunPlan)

        state.onManifestSelected(Path.of("/tmp/requirements.txt"))
        assertTrue(state.canRunPlan)

        state.busy = true
        assertFalse(state.canRunPlan)
    }

    @Test
    fun `canVerify requires planJson and not busy`() {
        val state = PanelState()
        assertFalse(state.canVerify)

        state.onManifestSelected(Path.of("/tmp/requirements.txt"))
        assertFalse(state.canVerify)

        state.onPlanCompleted(UpgradePlan(), Path.of("/tmp/plan.json"))
        assertTrue(state.canVerify)

        state.busy = true
        assertFalse(state.canVerify)
    }

    @Test
    fun `canApply requires planJson and not busy`() {
        val state = PanelState()
        assertFalse(state.canApply)

        state.onManifestSelected(Path.of("/tmp/requirements.txt"))
        assertFalse(state.canApply)

        state.onPlanCompleted(UpgradePlan(), Path.of("/tmp/plan.json"))
        assertTrue(state.canApply)

        state.busy = true
        assertFalse(state.canApply)
    }

    @Test
    fun `busy disables all predicates`() {
        val state = PanelState()
        state.onManifestSelected(Path.of("/tmp/requirements.txt"))
        state.onPlanCompleted(UpgradePlan(), Path.of("/tmp/plan.json"))

        assertTrue(state.canRunPlan)
        assertTrue(state.canVerify)
        assertTrue(state.canApply)

        state.busy = true

        assertFalse(state.canRunPlan)
        assertFalse(state.canVerify)
        assertFalse(state.canApply)
    }
}
