package io.locklane.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class ToolAvailability(
    @JsonProperty("available") val available: Boolean = false,
    @JsonProperty("binary") val binary: String = "",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Resolution(
    @JsonProperty("packages") val packages: List<ResolvedPackage> = emptyList(),
    @JsonProperty("resolver_tool") val resolverTool: String = "",
    @JsonProperty("resolver_version") val resolverVersion: String = "",
    @JsonProperty("python_version") val pythonVersion: String = "",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CacheKey(
    @JsonProperty("interpreter_path") val interpreterPath: String = "",
    @JsonProperty("python_version") val pythonVersion: String = "",
    @JsonProperty("manifest_sha256") val manifestSha256: String = "",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BaselineResult(
    @JsonProperty("schema_version") val schemaVersion: String = "",
    @JsonProperty("timestamp_utc") val timestampUtc: String = "",
    @JsonProperty("resolver") val resolver: String = "",
    @JsonProperty("status") val status: String = "",
    @JsonProperty("manifest_path") val manifestPath: String = "",
    @JsonProperty("dependencies") val dependencies: List<ParsedDependency> = emptyList(),
    @JsonProperty("tooling") val tooling: Map<String, ToolAvailability> = emptyMap(),
    @JsonProperty("resolution") val resolution: Resolution? = null,
    @JsonProperty("cache_key") val cacheKey: CacheKey? = null,
    @JsonProperty("error") val error: String? = null,
)
