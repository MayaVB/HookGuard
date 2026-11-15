package io.hookguard.delivery.http;

import java.util.UUID;

/** Everything needed to perform one webhook HTTP attempt. */
public record WebhookRequest(
        String url,
        String jsonBody,
        UUID deliveryId,
        UUID eventId,
        String eventType,
        int attemptNumber) {
}
