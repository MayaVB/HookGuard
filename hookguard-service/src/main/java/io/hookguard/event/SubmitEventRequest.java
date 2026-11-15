package io.hookguard.event;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import tools.jackson.databind.JsonNode;

public record SubmitEventRequest(
        @NotNull UUID endpointId,
        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$",
                message = "must be 1-100 characters: letters, digits, '.', '_' or '-'")
        String eventType,
        @NotNull JsonNode payload) {
}
