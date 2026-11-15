package io.hookguard.endpoint;

import java.time.Instant;
import java.util.UUID;

public record EndpointResponse(UUID id, String url, String description, Instant createdAt) {

    static EndpointResponse from(Endpoint endpoint) {
        return new EndpointResponse(endpoint.getId(), endpoint.getUrl(), endpoint.getDescription(), endpoint.getCreatedAt());
    }
}
