package io.locklane.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class ResolverBundleExtractorTest {

    @Test
    fun `extractBundledResolver returns non-null when bundled resources exist`() {
        // This test runs inside the build where resources are on the classpath
        val result = ResolverBundleExtractor.extractBundledResolver()
        assertNotNull(result, "Expected bundled resolver to be extracted")
    }

    @Test
    fun `extracted directory contains locklane_resolver package`() {
        val result = ResolverBundleExtractor.extractBundledResolver() ?: return
        val packageDir = result.resolve("locklane_resolver")
        assertTrue(Files.isDirectory(packageDir), "Expected locklane_resolver/ directory")
        assertTrue(Files.exists(packageDir.resolve("__init__.py")), "Expected __init__.py")
        assertTrue(Files.exists(packageDir.resolve("__main__.py")), "Expected __main__.py")
        assertTrue(Files.exists(packageDir.resolve("cli.py")), "Expected cli.py")
    }

    @Test
    fun `second extraction is a cache hit and returns same path`() {
        val first = ResolverBundleExtractor.extractBundledResolver() ?: return
        val second = ResolverBundleExtractor.extractBundledResolver()
        assertEquals(first, second, "Expected same path on cache hit")
    }

    @Test
    fun `extraction overwrites stale files`() {
        val result = ResolverBundleExtractor.extractBundledResolver() ?: return
        val initFile = result.resolve("locklane_resolver/__init__.py")

        // Corrupt the file to simulate a stale extraction
        val originalContent = Files.readAllBytes(initFile)
        Files.writeString(initFile, "# corrupted")

        // Re-extract should overwrite the corrupted file
        val result2 = ResolverBundleExtractor.extractBundledResolver()
        assertNotNull(result2)
        val restoredContent = Files.readAllBytes(initFile)
        assertEquals(
            sha256(originalContent),
            sha256(restoredContent),
            "Expected extraction to restore the original file",
        )
    }

    @Test
    fun `manifest txt is readable from classpath`() {
        val stream = javaClass.getResourceAsStream("/bundled_resolver/manifest.txt")
        assertNotNull(stream, "Expected manifest.txt on classpath")
        val lines = stream!!.use { it.bufferedReader().readLines().filter(String::isNotBlank) }
        assertTrue(lines.contains("__init__.py"), "Expected __init__.py in manifest")
        assertTrue(lines.contains("cli.py"), "Expected cli.py in manifest")
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
