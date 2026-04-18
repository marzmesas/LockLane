package io.locklane.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class PackageLinks(
    @JsonProperty("changelog_url") val changelogUrl: String? = null,
    @JsonProperty("home_page") val homePage: String? = null,
    @JsonProperty("current_version_date") val currentVersionDate: String? = null,
    @JsonProperty("latest_version") val latestVersion: String? = null,
    @JsonProperty("latest_version_date") val latestVersionDate: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EnrichResult(
    @JsonProperty("schema_version") val schemaVersion: String = "",
    @JsonProperty("status") val status: String = "",
    @JsonProperty("manifest_path") val manifestPath: String = "",
    @JsonProperty("packages") val packages: Map<String, PackageLinks> = emptyMap(),
)
