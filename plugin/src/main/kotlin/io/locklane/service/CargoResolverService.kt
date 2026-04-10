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

/**
 * Resolver service for Cargo.toml manifests.
 * Invokes the locklane-cargo native binary directly.
 */
@Service(Service.Level.PROJECT)
class CargoResolverService(private val project: Project) {

    private val log = Logger.getInstance(CargoResolverService::class.java)

    val objectMapper: ObjectMapper = ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val processRunner = ProcessRunner()

    fun runBaseline(manifest: Path, indicator: ProgressIndicator? = null): BaselineResult {
        val result = execute("baseline", manifest, indicator = indicator)
        return objectMapper.readValue(result.stdout, BaselineResult::class.java)
    }

    fun runPlan(manifest: Path, indicator: ProgressIndicator? = null): UpgradePlan {
        val result = execute("plan", manifest, indicator = indicator)
        return objectMapper.readValue(result.stdout, UpgradePlan::class.java)
    }

    fun runPlanRaw(manifest: Path, indicator: ProgressIndicator? = null): Pair<UpgradePlan, String> {
        val result = execute("plan", manifest, indicator = indicator)
        val plan = objectMapper.readValue(result.stdout, UpgradePlan::class.java)
        return Pair(plan, result.stdout)
    }

    fun runVerifyPlan(manifest: Path, planJson: Path, indicator: ProgressIndicator? = null): VerificationReport {
        val settings = LockLaneSettings.getInstance(project)
        val args = mutableListOf("--plan-json", planJson.toString())
        if (settings.state.verifyCommand.isNotBlank()) {
            args += listOf("--command", settings.state.verifyCommand)
        }
        val result = execute("verify-plan", manifest, args, indicator = indicator)
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
        if (output != null) args += listOf("--output", output.toString())
        if (dryRun) args += "--dry-run"
        val result = execute("apply", manifest, args, indicator = indicator)
        return objectMapper.readValue(result.stdout, ApplyResult::class.java)
    }

    fun runAudit(manifest: Path, indicator: ProgressIndicator? = null): AuditResult {
        val result = execute("audit", manifest, indicator = indicator)
        return objectMapper.readValue(result.stdout, AuditResult::class.java)
    }

    fun runEnrich(manifest: Path, indicator: ProgressIndicator? = null): EnrichResult {
        val result = execute("enrich", manifest, indicator = indicator)
        return objectMapper.readValue(result.stdout, EnrichResult::class.java)
    }

    private fun execute(
        command: String,
        manifest: Path,
        extraArgs: List<String> = emptyList(),
        indicator: ProgressIndicator? = null,
    ): ProcessResult {
        val settings = LockLaneSettings.getInstance(project)
        val binaryPath = resolveBinaryPath(settings)
            ?: throw ResolverException(
                "locklane-cargo binary not found. Build it with: cd resolver-cargo && cargo build --release"
            )

        val cmd = mutableListOf(
            binaryPath.toString(),
            command,
            "--manifest", manifest.toString(),
        )

        if (command in listOf("plan", "simulate") && settings.state.excludeNewer.isNotBlank()) {
            cmd += listOf("--exclude-newer", settings.state.excludeNewer)
        }
        if (command in listOf("plan", "simulate", "verify-plan")) {
            cmd += listOf("--timeout", settings.state.timeoutSeconds.toString())
        }

        cmd += extraArgs

        val env = buildEnvironment()

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
                "Cargo resolver command '$command' failed (exit code ${result.exitCode}):\n$detail",
                exitCode = result.exitCode,
                stderr = result.stderr,
            )
        }

        return result
    }

    private fun resolveBinaryPath(settings: LockLaneSettings): Path? {
        // 1. Explicit user override
        if (settings.state.cargoResolverPath.isNotBlank()) {
            val f = File(settings.state.cargoResolverPath)
            if (f.isFile && f.canExecute()) return f.toPath()
        }
        // 2. Bundled or dev binary
        return CargoResolverBundleExtractor.extractBinary(project.basePath)
    }

    private fun buildEnvironment(): Map<String, String> {
        val env = mutableMapOf<String, String>()

        // Augment PATH so cargo can be found
        val currentPath = System.getenv("PATH") ?: ""
        val extraDirs = mutableListOf<String>()
        val home = System.getProperty("user.home")
        if (home != null) {
            val cargoDir = "$home/.cargo/bin"
            if (cargoDir !in currentPath.split(File.pathSeparator) && File(cargoDir).isDirectory) {
                extraDirs += cargoDir
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

        return env
    }

    companion object {
        fun getInstance(project: Project): CargoResolverService =
            project.getService(CargoResolverService::class.java)
    }
}
