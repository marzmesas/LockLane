package io.locklane.service

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import io.locklane.model.ApplyResult
import io.locklane.model.BaselineResult
import io.locklane.model.UpgradePlan
import io.locklane.model.VerificationReport
import io.locklane.settings.LocklaneSettings
import java.io.File
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class ResolverService(private val project: Project) {

    val objectMapper: ObjectMapper = ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val processRunner = ProcessRunner()

    fun runBaseline(manifest: Path, indicator: ProgressIndicator? = null): BaselineResult {
        val result = executeResolver("baseline", manifest, indicator = indicator)
        return objectMapper.readValue(result.stdout, BaselineResult::class.java)
    }

    fun runPlan(manifest: Path, indicator: ProgressIndicator? = null): UpgradePlan {
        val result = executeResolver("plan", manifest, indicator = indicator)
        return objectMapper.readValue(result.stdout, UpgradePlan::class.java)
    }

    fun runPlanRaw(manifest: Path, indicator: ProgressIndicator? = null): Pair<UpgradePlan, String> {
        val result = executeResolver("plan", manifest, indicator = indicator)
        val plan = objectMapper.readValue(result.stdout, UpgradePlan::class.java)
        return Pair(plan, result.stdout)
    }

    fun runVerifyPlan(manifest: Path, planJson: Path, indicator: ProgressIndicator? = null): VerificationReport {
        val extraArgs = listOf("--plan-json", planJson.toString())
        val settings = LocklaneSettings.getInstance(project)
        val args = extraArgs.toMutableList()
        if (settings.state.verifyCommand.isNotBlank()) {
            args += listOf("--command", settings.state.verifyCommand)
        }
        val result = executeResolver("verify-plan", manifest, args, indicator = indicator)
        return objectMapper.readValue(result.stdout, VerificationReport::class.java)
    }

    fun runApply(
        manifest: Path,
        planJson: Path,
        output: Path? = null,
        dryRun: Boolean = false,
        indicator: ProgressIndicator? = null,
    ): ApplyResult {
        val args = mutableListOf("--plan-json", planJson.toString())
        if (output != null) {
            args += listOf("--output", output.toString())
        }
        if (dryRun) {
            args += "--dry-run"
        }
        val result = executeResolver("apply", manifest, args, indicator = indicator)
        return objectMapper.readValue(result.stdout, ApplyResult::class.java)
    }

    private fun executeResolver(
        command: String,
        manifest: Path,
        extraArgs: List<String> = emptyList(),
        indicator: ProgressIndicator? = null,
    ): ProcessResult {
        val settings = LocklaneSettings.getInstance(project)

        val pythonPath = PythonDiscovery.findPython(
            configuredPath = settings.state.pythonPath.ifBlank { null },
            projectBasePath = project.basePath,
        ) ?: throw ResolverException("No Python interpreter found. Configure one in Settings > Tools > Locklane.")

        val cmd = mutableListOf(
            pythonPath, "-m", "locklane_resolver",
            command,
            "--manifest", manifest.toString(),
            "--resolver", settings.state.resolverPreference,
        )

        if (command in listOf("baseline", "plan", "verify-plan", "simulate")) {
            cmd += listOf("--python", pythonPath)
        }
        if (command in listOf("plan", "verify-plan", "simulate")) {
            cmd += listOf("--timeout", settings.state.timeoutSeconds.toString())
        }

        cmd += extraArgs

        val env = buildEnvironment(settings, pythonPath)

        val result = processRunner.runCancellable(
            command = cmd,
            workingDir = manifest.parent?.toFile(),
            environment = env,
            timeoutSeconds = settings.state.timeoutSeconds,
            indicator = indicator,
        )

        if (result.exitCode != 0 && result.stdout.isBlank()) {
            throw ResolverException(
                "Resolver command '$command' failed with exit code ${result.exitCode}",
                exitCode = result.exitCode,
                stderr = result.stderr,
            )
        }

        return result
    }

    private fun buildEnvironment(settings: LocklaneSettings, pythonPath: String): Map<String, String> {
        val env = mutableMapOf<String, String>()

        // Set PYTHONPATH to resolver source
        val resolverSrc = resolveResolverSourcePath(settings)
        if (resolverSrc != null) {
            val existing = System.getenv("PYTHONPATH") ?: ""
            env["PYTHONPATH"] = if (existing.isBlank()) resolverSrc else "$resolverSrc${File.pathSeparator}$existing"
        }

        // Pass through index and auth env vars
        val passthrough = listOf(
            "PIP_INDEX_URL", "PIP_EXTRA_INDEX_URL",
            "UV_INDEX_URL", "UV_EXTRA_INDEX_URL",
            "PIP_TRUSTED_HOST",
        )
        for (key in passthrough) {
            System.getenv(key)?.let { env[key] = it }
        }

        // Build extra index URL env vars from settings
        if (settings.state.extraIndexUrls.isNotEmpty()) {
            val joined = settings.state.extraIndexUrls.joinToString(" ")
            env["PIP_EXTRA_INDEX_URL"] = joined
            env["UV_EXTRA_INDEX_URL"] = joined
        }

        return env
    }

    private fun resolveResolverSourcePath(settings: LocklaneSettings): String? {
        if (settings.state.resolverSourcePath.isNotBlank()) {
            return settings.state.resolverSourcePath
        }
        // Auto-detect: look for resolver/src relative to project base
        val basePath = project.basePath ?: return null
        val candidate = File(basePath, "resolver/src")
        return if (candidate.isDirectory) candidate.absolutePath else null
    }

    companion object {
        fun getInstance(project: Project): ResolverService =
            project.getService(ResolverService::class.java)
    }
}
