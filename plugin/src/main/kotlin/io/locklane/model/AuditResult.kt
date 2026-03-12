package io.locklane.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class VulnerabilityReference(
    @JsonProperty("url") val url: String = "",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Vulnerability(
    @JsonProperty("id") val id: String = "",
    @JsonProperty("summary") val summary: String = "",
    @JsonProperty("severity") val severity: String = "",
    @JsonProperty("aliases") val aliases: List<String> = emptyList(),
    @JsonProperty("references") val references: List<VulnerabilityReference> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PackageAudit(
    @JsonProperty("package") val packageName: String = "",
    @JsonProperty("version") val version: String = "",
    @JsonProperty("vulnerabilities") val vulnerabilities: List<Vulnerability> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AuditResult(
    @JsonProperty("schema_version") val schemaVersion: String = "",
    @JsonProperty("timestamp_utc") val timestampUtc: String = "",
    @JsonProperty("status") val status: String = "",
    @JsonProperty("manifest_path") val manifestPath: String = "",
    @JsonProperty("packages") val packages: List<PackageAudit> = emptyList(),
)
