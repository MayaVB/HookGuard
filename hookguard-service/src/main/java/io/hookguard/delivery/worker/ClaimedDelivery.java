package io.hookguard.delivery.worker;

import java.util.UUID;

/**
 * A delivery this worker has exclusively claimed for one attempt.
 *
 * @param claimToken    unique per claim; every later write must present it (fencing token)
 * @param attemptNumber 1-based number of the attempt this claim is for
 */
public record ClaimedDelivery(
        UUID deliveryId,
        UUID claimToken,
        int attemptNumber,
        UUID eventId,
        String eventType,
        String payload,
        String targetUrl) {
}
