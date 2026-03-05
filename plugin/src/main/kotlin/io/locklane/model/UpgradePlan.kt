package io.locklane.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class SafeUpdate(
    @JsonProperty("package") val packageName: String = "",
    @JsonProperty("from_version") val fromVersion: String = "",
    @JsonProperty("to_version") val toVersion: String = "",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ConflictLink(
    @JsonProperty("package") val packageName: String = "",
    @JsonProperty("constraint") val constraint: String = "",
    @JsonProperty("required_by") val requiredBy: String = "",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ConflictChain(
    @JsonProperty("summary") val summary: String = "",
    @JsonProperty("links") val links: List<ConflictLink> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BlockedUpdate(
    @JsonProperty("package") val packageName: String = "",
    @JsonProperty("target_version") val targetVersion: String = "",
    @JsonProperty("reason") val reason: String = "",
    @JsonProperty("conflict_chain") val conflictChain: ConflictChain? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class InconclusiveUpdate(
    @JsonProperty("package") val packageName: String = "",
    @JsonProperty("target_version") val targetVersion: String = "",
    @JsonProperty("reason") val reason: String = "",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OrderedStep(
    @JsonProperty("step") val step: Int = 0,
    @JsonProperty("description") val description: String = "",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class UpgradePlan(
    @JsonProperty("schema_version") val schemaVersion: String = "",
    @JsonProperty("timestamp_utc") val timestampUtc: String = "",
    @JsonProperty("status") val status: String = "",
    @JsonProperty("manifest_path") val manifestPath: String = "",
    @JsonProperty("resolver") val resolver: String = "",
    @JsonProperty("safe_updates") val safeUpdates: List<SafeUpdate> = emptyList(),
    @JsonProperty("blocked_updates") val blockedUpdates: List<BlockedUpdate> = emptyList(),
    @JsonProperty("inconclusive_updates") val inconclusiveUpdates: List<InconclusiveUpdate> = emptyList(),
    @JsonProperty("ordered_steps") val orderedSteps: List<OrderedStep> = emptyList(),
    @JsonProperty("error") val error: String? = null,
)
