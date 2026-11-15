package io.hookguard.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import io.hookguard.delivery.DeliveryCreated;

/**
 * Publishes delivery work to RabbitMQ after the creating transaction commits.
 *
 * <p>Publishing after commit (not inside the transaction) guarantees a worker never receives
 * an ID for a row that is not yet visible or was rolled back.
 *
 * <p><b>Known Phase 1 gap (dual write):</b> the database commit and the broker publish are two
 * separate operations. If the process crashes between them, or the broker is unreachable, the
 * delivery is stored as PENDING but no message exists, so nothing will ever process it. Phase 2
 * closes this gap with a relay that re-publishes PENDING deliveries from the database.
 */
@Component
public class DeliveryPublisher {

    private static final Logger log = LoggerFactory.getLogger(DeliveryPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public DeliveryPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeliveryCreated(DeliveryCreated event) {
        try {
            rabbitTemplate.convertAndSend(RabbitTopology.EXCHANGE, RabbitTopology.WORK_ROUTING_KEY,
                    event.deliveryId().toString());
        } catch (AmqpException e) {
            // The event is durably stored, so the client still gets 202 (failing the request would
            // invite a client retry that creates a duplicate event). The delivery stays PENDING.
            log.error("Delivery {} was stored but could not be published to RabbitMQ; it remains PENDING "
                    + "until re-published", event.deliveryId(), e);
        }
    }
}
