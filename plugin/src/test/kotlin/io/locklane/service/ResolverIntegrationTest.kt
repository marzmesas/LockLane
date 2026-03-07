package io.locklane.service

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import io.locklane.model.BaselineResult
import io.locklane.model.UpgradePlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

@Tag("integration")
@DisabledOnOs(OS.WINDOWS)
class ResolverIntegrationTest {

    private lateinit var mapper: ObjectMapper
    private lateinit var runner: ProcessRunner

    @BeforeEach
    fun setUp() {
        mapper = ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        runner = ProcessRunner()
    }

    private fun findPython(): String? {
        return try {
            val result = ProcessBuilder("which", "python3")
                .redirectErrorStream(true)
                .start()
            val output = result.inputStream.bufferedReader().readText().trim()
            if (result.waitFor() == 0 && output.isNotBlank()) output else null
        } catch (_: Exception) {
            null
        }
    }

    private fun resolverSrcPath(): File? {
        val fromProperty = System.getProperty("locklane.resolver.src")
        if (fromProperty != null) {
            val dir = File(fromProperty)
            if (dir.isDirectory) return dir
        }
        // Fallback: relative to working directory
        val candidates = listOf(
            File("../resolver/src"),
            File("../../resolver/src"),
        )
        return candidates.firstOrNull { it.isDirectory }
    }

    @Test
    fun `baseline parse-only round-trip through ObjectMapper`(@TempDir tempDir: Path) {
        val python = findPython()
        assumeTrue(python != null, "python3 not found on PATH")
        val resolverSrc = resolverSrcPath()
        assumeTrue(resolverSrc != null, "resolver/src not found")

        val manifest = tempDir.resolve("requirements.txt").toFile()
        manifest.writeText("requests==2.31.0\n")

        val env = mapOf("PYTHONPATH" to resolverSrc!!.absolutePath)
        val result = runner.run(
            command = listOf(
                python!!, "-m", "locklane_resolver",
                "baseline",
                "--manifest", manifest.absolutePath,
                "--resolver", "uv",
                "--no-resolve",
            ),
            environment = env,
            timeoutSeconds = 30,
        )

        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")

        val baseline = mapper.readValue(result.stdout, BaselineResult::class.java)
        assertEquals("ok", baseline.status)
        assertTrue(baseline.dependencies.isNotEmpty())
        assertEquals("requests", baseline.dependencies[0].name)
    }

    @Test
    fun `plan round-trip through ObjectMapper`(@TempDir tempDir: Path) {
        val python = findPython()
        assumeTrue(python != null, "python3 not found on PATH")
        val resolverSrc = resolverSrcPath()
        assumeTrue(resolverSrc != null, "resolver/src not found")

        val manifest = tempDir.resolve("requirements.txt").toFile()
        manifest.writeText("requests==2.31.0\n")

        val env = mapOf("PYTHONPATH" to resolverSrc!!.absolutePath)
        val result = runner.run(
            command = listOf(
                python!!, "-m", "locklane_resolver",
                "plan",
                "--manifest", manifest.absolutePath,
                "--resolver", "uv",
                "--python", python,
                "--timeout", "120",
            ),
            environment = env,
            timeoutSeconds = 120,
        )

        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")

        val plan = mapper.readValue(result.stdout, UpgradePlan::class.java)
        assertEquals("ok", plan.status)
        assertNotNull(plan.safeUpdates)
        assertNotNull(plan.blockedUpdates)
        assertNotNull(plan.orderedSteps)
    }
}
