package io.locklane.service

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Extracts or locates the locklane-cargo native binary.
 *
 * Resolution order:
 * 1. Bundled binary in JAR resources (for distribution)
 * 2. Dev fallback: resolver-cargo/target/release or target/debug
 */
object CargoResolverBundleExtractor {

    private val LOG = Logger.getInstance(CargoResolverBundleExtractor::class.java)
    private const val BINARY_NAME = "locklane-cargo"

    fun extractBinary(projectBasePath: String? = null): Path? {
        // 1. Try bundled binary from JAR resources
        extractBundledBinary()?.let { return it }

        // 2. Dev fallback: look for compiled binary in the source tree
        if (projectBasePath != null) {
            findDevBinary(projectBasePath)?.let { return it }
        }

        return null
    }

    private fun extractBundledBinary(): Path? {
        val platform = detectPlatform() ?: return null
        val resourcePath = "/bundled_resolver_cargo/$platform/$BINARY_NAME"

        val stream = javaClass.getResourceAsStream(resourcePath) ?: return null

        val targetDir = Path.of(PathManager.getTempPath(), "locklane-cargo")
        Files.createDirectories(targetDir)

        val binaryPath = targetDir.resolve(BINARY_NAME)

        // Always re-extract (could add checksum caching later)
        stream.use { Files.copy(it, binaryPath, StandardCopyOption.REPLACE_EXISTING) }

        // Make executable on Unix
        binaryPath.toFile().setExecutable(true)
        LOG.info("Extracted bundled cargo resolver to $binaryPath")
        return binaryPath
    }

    private fun findDevBinary(projectBasePath: String): Path? {
        // Look for resolver-cargo/target/{release,debug}/locklane-cargo
        val repoRoot = findRepoRoot(projectBasePath) ?: return null
        for (profile in listOf("release", "debug")) {
            val candidate = File(repoRoot, "resolver-cargo/target/$profile/$BINARY_NAME")
            if (candidate.isFile && candidate.canExecute()) {
                LOG.info("Using dev cargo resolver at: ${candidate.absolutePath}")
                return candidate.toPath()
            }
        }
        return null
    }

    private fun findRepoRoot(basePath: String): File? {
        // Walk up from the project base to find the repo root containing resolver-cargo/
        var dir: File? = File(basePath)
        while (dir != null) {
            if (File(dir, "resolver-cargo").isDirectory) return dir
            dir = dir.parentFile
        }
        return null
    }

    private fun detectPlatform(): String? {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        return when {
            os.contains("mac") && arch == "aarch64" -> "macos-aarch64"
            os.contains("mac") -> "macos-x86_64"
            os.contains("linux") && arch == "amd64" -> "linux-x86_64"
            os.contains("linux") && arch == "x86_64" -> "linux-x86_64"
            os.contains("win") -> "windows-x86_64"
            else -> {
                LOG.warn("Unsupported platform: $os/$arch")
                null
            }
        }
    }
}
