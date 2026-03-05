package io.locklane.service

import java.io.File
import java.util.concurrent.TimeUnit

object PythonDiscovery {

    fun findPython(configuredPath: String? = null, projectBasePath: String? = null): String? {
        // 1. Configured path from settings
        if (!configuredPath.isNullOrBlank()) {
            if (File(configuredPath).isFile) {
                return configuredPath
            }
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
                        return path.absolutePath
                    }
                }
            }
        }

        // 3. System PATH
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

    private fun findOnPath(name: String): String? {
        val pathEnv = System.getenv("PATH") ?: return null
        val separator = File.pathSeparator
        for (dir in pathEnv.split(separator)) {
            val candidate = File(dir, name)
            if (candidate.isFile && candidate.canExecute()) {
                return candidate.absolutePath
            }
        }
        return null
    }
}
