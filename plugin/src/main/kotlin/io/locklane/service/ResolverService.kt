package io.locklane.service

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import io.locklane.model.ApplyResult
import io.locklane.model.AuditResult
import io.locklane.model.BaselineResult
import io.locklane.model.EnrichResult
import io.locklane.model.UpgradePlan
import io.locklane.model.VerificationReport
import io.locklane.settings.LockLaneSettings
import java.io.File
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class ResolverService(private val project: Project) {

    private val log = Logger.getInstance(ResolverService::class.java)

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
        val settings = LockLaneSettings.getInstance(project)
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

    fun runAudit(manifest: Path, indicator: ProgressIndicator? = null): AuditResult {
        val result = executeResolver("audit", manifest, indicator = indicator)
        return objectMapper.readValue(result.stdout, AuditResult::class.java)
    }

    fun runEnrich(manifest: Path, indicator: ProgressIndicator? = null): EnrichResult {
        val result = executeResolver("enrich", manifest, indicator = indicator)
        return objectMapper.readValue(result.stdout, EnrichResult::class.java)
    }

    private fun executeResolver(
        command: String,
        manifest: Path,
        extraArgs: List<String> = emptyList(),
        indicator: ProgressIndicator? = null,
    ): ProcessResult {
        val settings = LockLaneSettings.getInstance(project)

        val pythonPath = PythonDiscovery.findPython(
            configuredPath = settings.state.pythonPath.ifBlank { null },
            projectBasePath = project.basePath,
        ) ?: throw ResolverException("No Python interpreter found. Configure one in Settings > Tools > LockLane.")

        checkResolverToolAvailable(settings.state.resolverPreference, pythonPath)

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
        if (command in listOf("baseline", "plan", "simulate") && settings.state.excludeNewer.isNotBlank()) {
            cmd += listOf("--exclude-newer", settings.state.excludeNewer)
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
            val detail = result.stderr.lines().take(10).joinToString("\n").ifBlank { "(no stderr)" }
            throw ResolverException(
                "Resolver command '$command' failed (exit code ${result.exitCode}):\n$detail",
                exitCode = result.exitCode,
                stderr = result.stderr,
            )
        }

        return result
    }

    private fun buildEnvironment(settings: LockLaneSettings, pythonPath: String): Map<String, String> {
        val env = mutableMapOf<String, String>()

        // Set PYTHONPATH to resolver source
        val resolverSrc = resolveResolverSourcePath(settings)
        if (resolverSrc != null) {
            val existing = System.getenv("PYTHONPATH") ?: ""
            env["PYTHONPATH"] = if (existing.isBlank()) resolverSrc else "$resolverSrc${File.pathSeparator}$existing"
        }

        // Augment PATH so the subprocess can find uv/pip-compile even when IDE PATH is minimal
        val pythonDir = File(pythonPath).parent
        val currentPath = System.getenv("PATH") ?: ""
        val extraDirs = mutableListOf<String>()
        if (pythonDir != null && pythonDir !in currentPath.split(File.pathSeparator)) {
            extraDirs += pythonDir  // e.g. .venv/bin where uv may live alongside python
        }
        val home = System.getProperty("user.home")
        if (home != null) {
            for (dir in listOf("$home/.local/bin", "$home/.cargo/bin")) {
                if (dir !in currentPath.split(File.pathSeparator) && File(dir).isDirectory) {
                    extraDirs += dir
                }
            }
        }
        if (System.getProperty("os.name").lowercase().contains("mac")) {
            for (dir in listOf("/opt/homebrew/bin", "/usr/local/bin")) {
                if (dir !in currentPath.split(File.pathSeparator) && File(dir).isDirectory) {
                    extraDirs += dir
                }
            }
        }
        if (extraDirs.isNotEmpty()) {
            env["PATH"] = (extraDirs + currentPath).joinToString(File.pathSeparator)
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

    private fun resolveResolverSourcePath(settings: LockLaneSettings): String? {
        // 1. Explicit user override
        if (settings.state.resolverSourcePath.isNotBlank()) {
            log.info("Using configured resolver source path: ${settings.state.resolverSourcePath}")
            return settings.state.resolverSourcePath
        }
        // 2. Bundled resolver (primary for end users)
        ResolverBundleExtractor.extractBundledResolver()?.let {
            log.debug("Using bundled resolver at: $it")
            return it.toString()
        }
        // 3. Dev fallback: resolver/src next to project (only useful during plugin development)
        val basePath = project.basePath ?: return null
        val candidate = File(basePath, "resolver/src")
        if (candidate.isDirectory) {
            log.info("Using dev fallback resolver at: ${candidate.absolutePath}")
            return candidate.absolutePath
        }
        return null
    }

    private fun checkResolverToolAvailable(preference: String, pythonPath: String? = null) {
        val binaries = if (preference == "pip-tools") {
            listOf("pip-compile" to "pip-tools", "uv" to "uv")
        } else {
            listOf("uv" to "uv", "pip-compile" to "pip-tools")
        }
        for ((binary, _) in binaries) {
            if (PythonDiscovery.findOnPathOrNear(binary, pythonPath) != null) return
        }
        val names = binaries.joinToString(" or ") { it.second }
        throw ResolverException(
            "No resolver tool found. Install $names and make sure it is on your PATH.\n" +
                "Install uv: curl -LsSf https://astral.sh/uv/install.sh | sh\n" +
                "Install pip-tools: pip install pip-tools",
        )
    }

    companion object {
        fun getInstance(project: Project): ResolverService =
            project.getService(ResolverService::class.java)
    }
}
