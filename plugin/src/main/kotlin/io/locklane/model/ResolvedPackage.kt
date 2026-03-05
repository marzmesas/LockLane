package io.locklane.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class ResolvedPackage(
    @JsonProperty("name") val name: String = "",
    @JsonProperty("version") val version: String = "",
    @JsonProperty("is_direct") val isDirect: Boolean = false,
    @JsonProperty("required_by") val requiredBy: List<String> = emptyList(),
)
