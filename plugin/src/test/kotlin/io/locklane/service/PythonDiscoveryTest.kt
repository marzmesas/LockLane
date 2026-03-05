package io.locklane.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS

class PythonDiscoveryTest {

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `findPython returns non-null on typical systems`() {
        val python = PythonDiscovery.findPython()
        assertNotNull(python, "Expected to find python3 or python on PATH")
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `validatePython returns true for system python3`() {
        val python = PythonDiscovery.findPython() ?: return
        assertTrue(PythonDiscovery.validatePython(python))
    }

    @Test
    fun `validatePython returns false for nonexistent path`() {
        assertFalse(PythonDiscovery.validatePython("/nonexistent/python"))
    }

    @Test
    fun `configured path takes priority`() {
        val python = PythonDiscovery.findPython() ?: return
        val found = PythonDiscovery.findPython(configuredPath = python)
        assertTrue(found == python, "Expected configured path to be returned")
    }

    @Test
    fun `invalid configured path falls through to other sources`() {
        val found = PythonDiscovery.findPython(configuredPath = "/nonexistent/python")
        // Should still find python via venv or PATH
        assertNotNull(found, "Expected fallback to PATH when configured path is invalid")
    }
}
