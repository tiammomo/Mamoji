package com.mamoji.ai.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Strict schema for model structured-output payload validation.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record StructuredAnswerPayload(
    @NotBlank @Size(max = 4000) String answer,
    @NotNull @Size(max = 50) List<@NotBlank @Size(max = 128) String> warnings,
    @NotNull @Size(max = 50) List<@NotBlank @Size(max = 256) String> sources,
    @NotNull @Size(max = 50) List<@NotBlank @Size(max = 128) String> actions
) {
}
