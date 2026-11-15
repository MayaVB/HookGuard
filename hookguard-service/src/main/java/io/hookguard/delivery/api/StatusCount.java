package io.hookguard.delivery.api;

import io.hookguard.delivery.DeliveryStatus;

public record StatusCount(DeliveryStatus status, long count) {
}
