package io.hookguard.endpoint;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateEndpointRequest(
        @NotBlank @Size(max = 2048) String url,
        @Size(max = 500) String description) {
}
