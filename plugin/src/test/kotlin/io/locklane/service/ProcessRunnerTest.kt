package io.locklane.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS

class ProcessRunnerTest {

    private val runner = ProcessRunner()

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `echo captures stdout`() {
        val result = runner.run(listOf("echo", "hello world"))
        assertEquals(0, result.exitCode)
        assertEquals("hello world\n", result.stdout)
        assertEquals("", result.stderr)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `non-zero exit code is captured`() {
        val result = runner.run(listOf("sh", "-c", "echo err >&2; exit 42"))
        assertEquals(42, result.exitCode)
        assertEquals("err\n", result.stderr)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `timeout throws ResolverException`() {
        val ex = assertThrows(ResolverException::class.java) {
            runner.run(listOf("sleep", "60"), timeoutSeconds = 1)
        }
        assertTrue(ex.message!!.contains("timed out"), "Expected timeout message, got: ${ex.message}")
        assertEquals(-1, ex.exitCode)
    }

    @Test
    fun `nonexistent binary throws ResolverException`() {
        assertThrows(ResolverException::class.java) {
            runner.run(listOf("/nonexistent/binary/xyz"))
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `environment variables are passed through`() {
        val result = runner.run(
            command = listOf("sh", "-c", "echo \$MY_TEST_VAR"),
            environment = mapOf("MY_TEST_VAR" to "test_value"),
        )
        assertEquals(0, result.exitCode)
        assertEquals("test_value\n", result.stdout)
    }
}
