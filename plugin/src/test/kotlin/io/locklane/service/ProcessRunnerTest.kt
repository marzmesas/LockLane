package io.locklane.service

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
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

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `cancellation destroys the subprocess`() {
        val startTime = System.currentTimeMillis()
        val indicator = object : ProgressIndicator {
            override fun start() {}
            override fun stop() {}
            override fun isRunning(): Boolean = true
            override fun cancel() {}
            override fun isCanceled(): Boolean = System.currentTimeMillis() - startTime > 500
            override fun setText(text: String?) {}
            override fun getText(): String = ""
            override fun setText2(text: String?) {}
            override fun getText2(): String = ""
            override fun getFraction(): Double = 0.0
            override fun setFraction(fraction: Double) {}
            override fun pushState() {}
            override fun popState() {}
            override fun isModal(): Boolean = false
            override fun getModalityState() = com.intellij.openapi.application.ModalityState.nonModal()
            override fun setModalityProgress(modalityProgress: ProgressIndicator?) {}
            override fun isIndeterminate(): Boolean = true
            override fun setIndeterminate(indeterminate: Boolean) {}
            override fun checkCanceled() {}
            override fun isPopupWasShown(): Boolean = false
            override fun isShowing(): Boolean = false
        }

        assertThrows(ProcessCanceledException::class.java) {
            runner.runCancellable(
                command = listOf("sleep", "60"),
                timeoutSeconds = 30,
                indicator = indicator,
            )
        }
    }
}
