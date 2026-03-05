package io.locklane.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class ParsedDependency(
    @JsonProperty("name") val name: String = "",
    @JsonProperty("specifier") val specifier: String = "",
    @JsonProperty("raw_line") val rawLine: String = "",
    @JsonProperty("line_number") val lineNumber: Int = 0,
)
