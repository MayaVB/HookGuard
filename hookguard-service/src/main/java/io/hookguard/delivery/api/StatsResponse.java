package io.hookguard.delivery.api;

import java.util.Map;

import io.hookguard.delivery.DeliveryStatus;

/** Delivery counts; {@code byStatus} always contains every status (zero if none). */
public record StatsResponse(long total, Map<DeliveryStatus, Long> byStatus) {
}
