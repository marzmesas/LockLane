package io.locklane.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class RollbackArtifact(
    @JsonProperty("schema_version") val schemaVersion: String = "",
    @JsonProperty("created_utc") val createdUtc: String = "",
    @JsonProperty("manifest_path") val manifestPath: String = "",
    @JsonProperty("original_content") val originalContent: String = "",
    @JsonProperty("reverse_updates") val reverseUpdates: List<SafeUpdate> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ApplyData(
    @JsonProperty("applied") val applied: Boolean = false,
    @JsonProperty("manifest_path") val manifestPath: String = "",
    @JsonProperty("output_path") val outputPath: String? = null,
    @JsonProperty("patch_preview") val patchPreview: String = "",
    @JsonProperty("updates_applied") val updatesApplied: List<SafeUpdate> = emptyList(),
    @JsonProperty("rollback") val rollback: RollbackArtifact? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ApplyResult(
    @JsonProperty("schema_version") val schemaVersion: String = "",
    @JsonProperty("timestamp_utc") val timestampUtc: String = "",
    @JsonProperty("status") val status: String = "",
    @JsonProperty("manifest_path") val manifestPath: String = "",
    @JsonProperty("plan_path") val planPath: String = "",
    @JsonProperty("dry_run") val dryRun: Boolean = false,
    @JsonProperty("apply") val apply: ApplyData? = null,
)
