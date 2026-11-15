package io.hookguard.delivery.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonRawValue;

import io.hookguard.delivery.AttemptOutcome;
import io.hookguard.delivery.DeliveryStatus;

/** Full lifecycle of one delivery, for the dashboard detail view. */
public record DeliveryDetail(
        UUID id,
        DeliveryStatus status,
        int attemptCount,
        Instant nextAttemptAt,
        String claimedBy,
        Instant claimExpiresAt,
        boolean leaseExpired,
        Integer lastHttpStatus,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        EventInfo event,
        EndpointInfo endpoint,
        List<Attempt> attempts) {

    public record EventInfo(UUID id, String eventType, Instant createdAt, @JsonRawValue String payload) {
    }

    public record EndpointInfo(UUID id, String url, String description) {
    }

    public record Attempt(
            int attemptNumber,
            String workerId,
            Instant startedAt,
            Instant finishedAt,
            long durationMs,
            AttemptOutcome outcome,
            Integer httpStatus,
            String responseBody,
            boolean responseTruncated,
            String errorMessage) {
    }
}
