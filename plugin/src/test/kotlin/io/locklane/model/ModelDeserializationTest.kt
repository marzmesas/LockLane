package io.locklane.model

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ModelDeserializationTest {

    private lateinit var mapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        mapper = ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/fixtures/$name")!!.bufferedReader().readText()

    @Test
    fun `baseline result deserializes with all fields`() {
        val json = fixture("baseline_result.json")
        val result = mapper.readValue(json, BaselineResult::class.java)

        assertEquals("0.6.0", result.schemaVersion)
        assertEquals("ok", result.status)
        assertEquals("uv", result.resolver)
        assertEquals("/project/requirements.txt", result.manifestPath)
        assertEquals(2, result.dependencies.size)
        assertEquals("requests", result.dependencies[0].name)
        assertEquals("==2.31.0", result.dependencies[0].specifier)
        assertEquals(1, result.dependencies[0].lineNumber)
        assertEquals("requests==2.31.0", result.dependencies[0].rawLine)

        assertNotNull(result.tooling["uv"])
        assertTrue(result.tooling["uv"]!!.available)
        assertEquals("uv", result.tooling["uv"]!!.binary)

        assertNotNull(result.resolution)
        assertEquals(3, result.resolution!!.packages.size)
        assertEquals("uv", result.resolution!!.resolverTool)
        assertEquals("3.12.0", result.resolution!!.pythonVersion)

        val urllib3 = result.resolution!!.packages.first { it.name == "urllib3" }
        assertEquals(false, urllib3.isDirect)
        assertEquals(listOf("requests"), urllib3.requiredBy)

        assertNotNull(result.cacheKey)
        assertEquals("/usr/bin/python3", result.cacheKey!!.interpreterPath)
        assertEquals("abc123def456", result.cacheKey!!.manifestSha256)

        assertNull(result.error)
    }

    @Test
    fun `baseline error deserializes with null resolution`() {
        val json = fixture("baseline_error.json")
        val result = mapper.readValue(json, BaselineResult::class.java)

        assertEquals("error", result.status)
        assertNull(result.resolution)
        assertNull(result.cacheKey)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("All resolvers failed"))
        assertEquals(1, result.dependencies.size)
    }

    @Test
    fun `upgrade plan deserializes with safe, blocked, and inconclusive updates`() {
        val json = fixture("upgrade_plan.json")
        val plan = mapper.readValue(json, UpgradePlan::class.java)

        assertEquals("0.6.0", plan.schemaVersion)
        assertEquals("ok", plan.status)
        assertEquals("uv", plan.resolver)

        assertEquals(1, plan.safeUpdates.size)
        assertEquals("requests", plan.safeUpdates[0].packageName)
        assertEquals("2.31.0", plan.safeUpdates[0].fromVersion)
        assertEquals("2.31.1", plan.safeUpdates[0].toVersion)

        assertEquals(1, plan.blockedUpdates.size)
        assertEquals("urllib3", plan.blockedUpdates[0].packageName)
        assertEquals("3.0.0", plan.blockedUpdates[0].targetVersion)
        assertNotNull(plan.blockedUpdates[0].conflictChain)
        assertEquals(1, plan.blockedUpdates[0].conflictChain!!.links.size)
        assertEquals("urllib3", plan.blockedUpdates[0].conflictChain!!.links[0].packageName)
        assertEquals("requests", plan.blockedUpdates[0].conflictChain!!.links[0].requiredBy)

        assertEquals(1, plan.inconclusiveUpdates.size)
        assertEquals("cryptography", plan.inconclusiveUpdates[0].packageName)

        assertEquals(1, plan.orderedSteps.size)
        assertEquals(1, plan.orderedSteps[0].step)
        assertTrue(plan.orderedSteps[0].description.contains("safe updates"))
    }

    @Test
    fun `verification report deserializes with steps`() {
        val json = fixture("verification_report.json")
        val report = mapper.readValue(json, VerificationReport::class.java)

        assertEquals("0.6.0", report.schemaVersion)
        assertEquals("ok", report.status)
        assertEquals("/project/plan.json", report.planPath)

        assertNotNull(report.verification)
        assertTrue(report.verification!!.passed)
        assertEquals(2, report.verification!!.steps.size)

        val venvStep = report.verification!!.steps[0]
        assertEquals("create_venv", venvStep.name)
        assertTrue(venvStep.passed)
        assertEquals(0, venvStep.exitCode)
        assertEquals(1.234, venvStep.durationSeconds, 0.001)

        val installStep = report.verification!!.steps[1]
        assertEquals("install_dependencies", installStep.name)
        assertTrue(installStep.passed)
        assertTrue(installStep.stdout.contains("Installed"))

        assertEquals("All 2/2 steps passed.", report.verification!!.summary)
    }

    @Test
    fun `apply result deserializes with dry-run data and rollback`() {
        val json = fixture("apply_result.json")
        val result = mapper.readValue(json, ApplyResult::class.java)

        assertEquals("0.6.0", result.schemaVersion)
        assertEquals("ok", result.status)
        assertTrue(result.dryRun)
        assertEquals("/project/plan.json", result.planPath)

        assertNotNull(result.apply)
        assertEquals(false, result.apply!!.applied)
        assertNull(result.apply!!.outputPath)
        assertTrue(result.apply!!.patchPreview.contains("-requests==2.31.0"))
        assertTrue(result.apply!!.patchPreview.contains("+requests==2.31.1"))

        assertEquals(1, result.apply!!.updatesApplied.size)
        assertEquals("requests", result.apply!!.updatesApplied[0].packageName)

        assertNotNull(result.apply!!.rollback)
        assertEquals("0.6.0", result.apply!!.rollback!!.schemaVersion)
        assertEquals("/project/requirements.txt", result.apply!!.rollback!!.manifestPath)
        assertTrue(result.apply!!.rollback!!.originalContent.contains("requests==2.31.0"))
        assertEquals(1, result.apply!!.rollback!!.reverseUpdates.size)
        assertEquals("2.31.1", result.apply!!.rollback!!.reverseUpdates[0].fromVersion)
        assertEquals("2.31.0", result.apply!!.rollback!!.reverseUpdates[0].toVersion)
    }

    @Test
    fun `unknown fields are ignored during deserialization`() {
        val json = """
        {
            "schema_version": "0.6.0",
            "status": "ok",
            "manifest_path": "/test",
            "resolver": "uv",
            "timestamp_utc": "2026-01-01T00:00:00+00:00",
            "future_field": "should be ignored",
            "dependencies": [],
            "tooling": {},
            "resolution": null,
            "cache_key": null
        }
        """.trimIndent()

        val result = mapper.readValue(json, BaselineResult::class.java)
        assertEquals("ok", result.status)
    }
}
