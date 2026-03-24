package io.locklane.service

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.util.concurrent.TimeUnit

object PythonDiscovery {

    private val log = Logger.getInstance(PythonDiscovery::class.java)

    fun findPython(configuredPath: String? = null, projectBasePath: String? = null): String? {
        // 1. Configured path from settings
        if (!configuredPath.isNullOrBlank()) {
            if (File(configuredPath).isFile) {
                return configuredPath
            }
            log.warn("Configured Python path does not exist: $configuredPath")
        }

        // 2. Project-local venv
        if (projectBasePath != null) {
            val venvCandidates = if (System.getProperty("os.name").lowercase().contains("win")) {
                listOf("Scripts/python.exe")
            } else {
                listOf("bin/python")
            }
            for (venvDir in listOf(".venv", "venv")) {
                for (candidate in venvCandidates) {
                    val path = File(projectBasePath, "$venvDir/$candidate")
                    if (path.isFile && path.canExecute()) {
                        log.info("Found Python in project venv: ${path.absolutePath}")
                        return path.absolutePath
                    }
                }
            }
        }

        // 3. System PATH (includes extra well-known directories)
        for (name in listOf("python3", "python")) {
            val found = findOnPath(name)
            if (found != null) return found
        }

        return null
    }

    fun validatePython(path: String): Boolean {
        return try {
            val process = ProcessBuilder(path, "--version")
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return false
            }
            if (process.exitValue() != 0) return false
            val output = process.inputStream.bufferedReader().readText()
            output.trimStart().startsWith("Python 3")
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Searches PATH plus well-known tool directories for [name].
     *
     * JetBrains IDEs launched from macOS Dock/Spotlight inherit a minimal PATH
     * that excludes directories like ~/.local/bin (uv), ~/.cargo/bin,
     * Homebrew prefixes, etc. We append those as fallback search locations.
     */
    fun findOnPath(name: String): String? {
        val pathEnv = System.getenv("PATH") ?: ""
        val separator = File.pathSeparator
        val searchDirs = pathEnv.split(separator).toMutableList()

        // Append well-known directories that IDEs often miss
        searchDirs += extraSearchDirectories()

        for (dir in searchDirs) {
            if (dir.isBlank()) continue
            val candidate = File(dir, name)
            if (candidate.isFile && candidate.canExecute()) {
                return candidate.absolutePath
            }
        }
        return null
    }

    /**
     * Finds a binary by name, also checking the directory containing [nearPath].
     * This handles the case where e.g. uv is installed in the same venv as Python.
     */
    fun findOnPathOrNear(name: String, nearPath: String?): String? {
        // First check adjacent to the given path (e.g. .venv/bin/uv next to .venv/bin/python)
        if (nearPath != null) {
            val nearDir = File(nearPath).parentFile
            if (nearDir != null) {
                val candidate = File(nearDir, name)
                if (candidate.isFile && candidate.canExecute()) {
                    return candidate.absolutePath
                }
            }
        }
        return findOnPath(name)
    }

    private fun extraSearchDirectories(): List<String> {
        val home = System.getProperty("user.home") ?: return emptyList()
        val extras = mutableListOf<String>()

        // Common tool installation directories
        extras += "$home/.local/bin"      // uv, pipx
        extras += "$home/.cargo/bin"      // rustup/cargo installs (uv)

        if (System.getProperty("os.name").lowercase().contains("mac")) {
            // Homebrew (Apple Silicon and Intel)
            extras += "/opt/homebrew/bin"
            extras += "/usr/local/bin"
        } else {
            // Linux: /usr/local/bin, snap
            extras += "/usr/local/bin"
            extras += "/snap/bin"
        }

        return extras
    }
}
