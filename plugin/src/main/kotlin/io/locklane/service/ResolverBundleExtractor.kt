package io.locklane.service

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

object ResolverBundleExtractor {

    private val LOG = Logger.getInstance(ResolverBundleExtractor::class.java)
    private const val RESOURCE_PREFIX = "/bundled_resolver/locklane_resolver/"
    private const val ENTRY_POINT = "__init__.py"

    fun extractBundledResolver(): Path? {
        val initStream = javaClass.getResourceAsStream("$RESOURCE_PREFIX$ENTRY_POINT") ?: return null

        val targetDir = Path.of(PathManager.getTempPath(), "locklane-resolver")
        val packageDir = targetDir.resolve("locklane_resolver")
        val targetInit = packageDir.resolve(ENTRY_POINT)

        val bundledInitBytes = initStream.use { it.readBytes() }

        if (Files.exists(targetInit) && hashOf(bundledInitBytes) == hashOfFile(targetInit)) {
            LOG.debug("Bundled resolver already extracted and up to date")
            return targetDir
        }

        LOG.info("Extracting bundled resolver to $targetDir")
        Files.createDirectories(packageDir)

        // Write the __init__.py we already read
        Files.write(targetInit, bundledInitBytes)

        // Extract remaining .py files by scanning the resource listing
        for (name in listBundledResources()) {
            if (name == ENTRY_POINT) continue
            val resourceStream = javaClass.getResourceAsStream("$RESOURCE_PREFIX$name") ?: continue
            val dest = packageDir.resolve(name)
            Files.createDirectories(dest.parent)
            resourceStream.use { Files.copy(it, dest, StandardCopyOption.REPLACE_EXISTING) }
        }

        return targetDir
    }

    private fun listBundledResources(): List<String> {
        // Read a manifest file that lists all bundled .py files.
        // If no manifest, fall back to a known list approach: scan by reading the resource directory.
        val manifestStream = javaClass.getResourceAsStream("/bundled_resolver/manifest.txt")
        if (manifestStream != null) {
            return manifestStream.use { it.bufferedReader().readLines().filter { line -> line.isNotBlank() } }
        }

        // Fallback: try known module files
        LOG.warn("Bundled resolver manifest.txt not found, using fallback file list")
        return listOf(
            "__init__.py", "__main__.py", "cli.py", "resolver.py",
            "planner.py", "graph.py", "cache.py", "pypi.py",
            "verifier.py", "simulator.py", "models.py", "applier.py",
            "osv.py", "pyproject_parser.py",
        )
    }

    private fun hashOf(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun hashOfFile(path: Path): String {
        return hashOf(Files.readAllBytes(path))
    }
}
