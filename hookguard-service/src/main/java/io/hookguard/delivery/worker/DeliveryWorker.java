package io.hookguard.delivery.worker;

import java.util.UUID;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import io.hookguard.messaging.RabbitTopology;

/**
 * RabbitMQ consumer. The message is only a hint ("delivery X may be ready"); the database row
 * is the source of truth, which is what makes duplicate and stale messages harmless.
 *
 * <p>Acknowledgement (spring.rabbitmq.listener.simple.acknowledge-mode=auto): Spring acks the
 * message only after this method returns, i.e. after the attempt has been committed. If the
 * process dies before that, RabbitMQ redelivers the message. If this method throws, the message
 * is rejected without requeue and dead-lettered (default-requeue-rejected=false), so a
 * persistent failure cannot spin in a hot redelivery loop.
 */
@Component
@ConditionalOnBooleanProperty(name = "hookguard.worker.enabled", matchIfMissing = true)
public class DeliveryWorker {

    private final DeliveryProcessor processor;

    public DeliveryWorker(DeliveryProcessor processor) {
        this.processor = processor;
    }

    @RabbitListener(queues = RabbitTopology.WORK_QUEUE)
    public void onDeliveryMessage(String body) {
        UUID deliveryId;
        try {
            deliveryId = UUID.fromString(body.trim());
        } catch (IllegalArgumentException e) {
            throw new AmqpRejectAndDontRequeueException("Malformed delivery message: '" + abbreviate(body) + "'", e);
        }
        processor.process(deliveryId);
    }

    private static String abbreviate(String body) {
        return body.length() <= 100 ? body : body.substring(0, 100) + "...";
    }
}
