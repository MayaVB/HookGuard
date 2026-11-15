package io.hookguard.delivery.api;

import java.time.Instant;
import java.util.UUID;

import io.hookguard.delivery.DeliveryStatus;

/**
 * One row of the delivery list.
 *
 * @param leaseExpired true if the delivery is IN_PROGRESS but its claim lease has run out (evaluated
 *                     with the database clock): the worker holding it most likely died
 */
public record DeliverySummary(
        UUID id,
        UUID eventId,
        String eventType,
        UUID endpointId,
        String endpointUrl,
        DeliveryStatus status,
        int attemptCount,
        Integer lastHttpStatus,
        String lastError,
        String claimedBy,
        Instant claimExpiresAt,
        boolean leaseExpired,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {
}
