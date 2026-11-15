package io.hookguard.event;

import java.util.UUID;

import io.hookguard.delivery.DeliveryStatus;

public record EventAcceptedResponse(UUID eventId, UUID deliveryId, DeliveryStatus status) {
}
