package io.locklane.service

import java.nio.file.Files
import java.nio.file.Path

data class LockFileInfo(
    val lockFilePath: Path,
    val command: List<String>,
    val toolName: String,
)

object LockFileService {

    fun detectLockFile(manifestPath: Path, resolverPreference: String): LockFileInfo? {
        val name = manifestPath.fileName.toString()
        val parent = manifestPath.parent ?: return null

        // .in -> .txt (pip-compile / uv pip compile)
        if (name.endsWith(".in")) {
            val lockName = name.removeSuffix(".in") + ".txt"
            val lockPath = parent.resolve(lockName)
            return if (resolverPreference == "pip-tools") {
                LockFileInfo(lockPath, listOf("pip-compile", manifestPath.toString(), "--output-file", lockPath.toString()), "pip-compile")
            } else {
                LockFileInfo(lockPath, listOf("uv", "pip", "compile", manifestPath.toString(), "-o", lockPath.toString()), "uv pip compile")
            }
        }

        // pyproject.toml
        if (name == "pyproject.toml") {
            return detectPyprojectLockTool(manifestPath, parent)
        }

        return null
    }

    private fun detectPyprojectLockTool(manifestPath: Path, parent: Path): LockFileInfo? {
        val content = try {
            Files.readString(manifestPath)
        } catch (_: Exception) {
            return null
        }

        // Poetry project
        if (content.contains("[tool.poetry]") || content.contains("[tool.poetry.dependencies]")) {
            val lockPath = parent.resolve("poetry.lock")
            return if (PythonDiscovery.findOnPath("poetry") != null) {
                LockFileInfo(lockPath, listOf("poetry", "lock"), "poetry")
            } else {
                null
            }
        }

        // uv project (has uv.lock or [tool.uv] section)
        val uvLock = parent.resolve("uv.lock")
        if (Files.exists(uvLock) || content.contains("[tool.uv]")) {
            return if (PythonDiscovery.findOnPath("uv") != null) {
                LockFileInfo(uvLock, listOf("uv", "lock"), "uv")
            } else {
                null
            }
        }

        return null
    }
}
