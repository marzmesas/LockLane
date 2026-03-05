package io.locklane.service

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

class ProcessRunner {

    fun run(
        command: List<String>,
        workingDir: File? = null,
        environment: Map<String, String>? = null,
        timeoutSeconds: Int = 120,
    ): ProcessResult {
        val builder = ProcessBuilder(command)
        if (workingDir != null) {
            builder.directory(workingDir)
        }
        if (environment != null) {
            builder.environment().putAll(environment)
        }

        val process: Process
        try {
            process = builder.start()
        } catch (e: Exception) {
            throw ResolverException("Failed to start process: ${command.firstOrNull()}", stderr = e.message ?: "")
        }

        val stdoutFuture = CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader().readText()
        }
        val stderrFuture = CompletableFuture.supplyAsync {
            process.errorStream.bufferedReader().readText()
        }

        val finished = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            stdoutFuture.cancel(true)
            stderrFuture.cancel(true)
            throw ResolverException(
                "Process timed out after ${timeoutSeconds}s: ${command.firstOrNull()}",
                exitCode = -1,
                stderr = "Timed out after ${timeoutSeconds}s",
            )
        }

        val stdout = stdoutFuture.get(5, TimeUnit.SECONDS)
        val stderr = stderrFuture.get(5, TimeUnit.SECONDS)

        return ProcessResult(
            exitCode = process.exitValue(),
            stdout = stdout,
            stderr = stderr,
        )
    }
}
