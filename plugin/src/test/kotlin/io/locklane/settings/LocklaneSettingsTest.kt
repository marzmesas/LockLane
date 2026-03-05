package io.locklane.settings

import com.intellij.util.xmlb.XmlSerializerUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocklaneSettingsTest {

    @Test
    fun `default state has expected values`() {
        val state = LocklaneSettings.State()
        assertEquals("", state.pythonPath)
        assertEquals("uv", state.resolverPreference)
        assertTrue(state.extraIndexUrls.isEmpty())
        assertEquals("", state.verifyCommand)
        assertEquals(120, state.timeoutSeconds)
        assertEquals("", state.resolverSourcePath)
    }

    @Test
    fun `state mutation works correctly`() {
        val state = LocklaneSettings.State()
        state.pythonPath = "/usr/bin/python3"
        state.resolverPreference = "pip-tools"
        state.extraIndexUrls.add("https://pypi.example.com/simple/")
        state.verifyCommand = "pytest"
        state.timeoutSeconds = 300
        state.resolverSourcePath = "/path/to/resolver/src"

        assertEquals("/usr/bin/python3", state.pythonPath)
        assertEquals("pip-tools", state.resolverPreference)
        assertEquals(1, state.extraIndexUrls.size)
        assertEquals("pytest", state.verifyCommand)
        assertEquals(300, state.timeoutSeconds)
        assertEquals("/path/to/resolver/src", state.resolverSourcePath)
    }

    @Test
    fun `state copy preserves values`() {
        val source = LocklaneSettings.State()
        source.pythonPath = "/custom/python"
        source.resolverPreference = "pip-tools"
        source.timeoutSeconds = 60

        val target = LocklaneSettings.State()
        XmlSerializerUtil.copyBean(source, target)

        assertEquals("/custom/python", target.pythonPath)
        assertEquals("pip-tools", target.resolverPreference)
        assertEquals(60, target.timeoutSeconds)
    }

    @Test
    fun `PersistentStateComponent getState returns state`() {
        val settings = LocklaneSettings()
        assertNotNull(settings.state)
        assertEquals("uv", settings.state.resolverPreference)
    }

    @Test
    fun `loadState copies values into internal state`() {
        val settings = LocklaneSettings()
        val newState = LocklaneSettings.State()
        newState.pythonPath = "/loaded/python"
        newState.timeoutSeconds = 42

        settings.loadState(newState)

        assertEquals("/loaded/python", settings.state.pythonPath)
        assertEquals(42, settings.state.timeoutSeconds)
    }

    private fun assertNotNull(value: Any?) {
        org.junit.jupiter.api.Assertions.assertNotNull(value)
    }
}
