package io.locklane.service

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import io.locklane.model.ApplyResult
import io.locklane.model.BaselineResult
import io.locklane.model.UpgradePlan
import io.locklane.model.VerificationReport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ResolverServiceTest {

    private lateinit var mapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        mapper = ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/fixtures/$name")!!.bufferedReader().readText()

    @Test
    fun `baseline fixture deserializes through shared ObjectMapper config`() {
        val result = mapper.readValue(fixture("baseline_result.json"), BaselineResult::class.java)
        assertEquals("ok", result.status)
        assertEquals(2, result.dependencies.size)
        assertNotNull(result.resolution)
    }

    @Test
    fun `upgrade plan fixture deserializes through shared ObjectMapper config`() {
        val plan = mapper.readValue(fixture("upgrade_plan.json"), UpgradePlan::class.java)
        assertEquals("ok", plan.status)
        assertEquals(1, plan.safeUpdates.size)
        assertEquals(1, plan.blockedUpdates.size)
    }

    @Test
    fun `verification report fixture deserializes through shared ObjectMapper config`() {
        val report = mapper.readValue(fixture("verification_report.json"), VerificationReport::class.java)
        assertEquals("ok", report.status)
        assertNotNull(report.verification)
        assertTrue(report.verification!!.passed)
    }

    @Test
    fun `apply result fixture deserializes through shared ObjectMapper config`() {
        val result = mapper.readValue(fixture("apply_result.json"), ApplyResult::class.java)
        assertEquals("ok", result.status)
        assertTrue(result.dryRun)
        assertNotNull(result.apply)
        assertNotNull(result.apply!!.rollback)
    }

    @Test
    fun `error baseline fixture deserializes through shared ObjectMapper config`() {
        val result = mapper.readValue(fixture("baseline_error.json"), BaselineResult::class.java)
        assertEquals("error", result.status)
        assertNotNull(result.error)
    }
}
