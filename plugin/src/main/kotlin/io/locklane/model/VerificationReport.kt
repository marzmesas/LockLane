package io.locklane.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class VerificationStep(
    @JsonProperty("name") val name: String = "",
    @JsonProperty("command") val command: String = "",
    @JsonProperty("passed") val passed: Boolean = false,
    @JsonProperty("exit_code") val exitCode: Int = 0,
    @JsonProperty("stdout") val stdout: String = "",
    @JsonProperty("stderr") val stderr: String = "",
    @JsonProperty("duration_seconds") val durationSeconds: Double = 0.0,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Verification(
    @JsonProperty("passed") val passed: Boolean = false,
    @JsonProperty("steps") val steps: List<VerificationStep> = emptyList(),
    @JsonProperty("summary") val summary: String = "",
    @JsonProperty("venv_path") val venvPath: String = "",
    @JsonProperty("modified_manifest_path") val modifiedManifestPath: String = "",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VerificationReport(
    @JsonProperty("schema_version") val schemaVersion: String = "",
    @JsonProperty("timestamp_utc") val timestampUtc: String = "",
    @JsonProperty("status") val status: String = "",
    @JsonProperty("manifest_path") val manifestPath: String = "",
    @JsonProperty("plan_path") val planPath: String = "",
    @JsonProperty("resolver") val resolver: String = "",
    @JsonProperty("verification") val verification: Verification? = null,
)
