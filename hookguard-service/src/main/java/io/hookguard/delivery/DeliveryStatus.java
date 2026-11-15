package io.hookguard.delivery;

/**
 * Lifecycle of a delivery.
 *
 * <pre>
 *   PENDING --claim--> IN_PROGRESS --2xx--------> DELIVERED   (terminal)
 *                                  --otherwise--> FAILED      (terminal)
 * </pre>
 *
 * Retries (Phase 2) will add a path from IN_PROGRESS back to a scheduled state.
 */
public enum DeliveryStatus {
    PENDING,
    IN_PROGRESS,
    DELIVERED,
    FAILED
}
