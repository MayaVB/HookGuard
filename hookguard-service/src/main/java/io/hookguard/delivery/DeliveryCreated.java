package io.hookguard.delivery;

import java.util.UUID;

/** Application event raised inside the submitting transaction; published to RabbitMQ after commit. */
public record DeliveryCreated(UUID deliveryId) {
}
