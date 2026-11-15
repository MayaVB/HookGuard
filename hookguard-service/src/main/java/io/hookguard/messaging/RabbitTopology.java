package io.hookguard.messaging;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Broker topology, declared idempotently by every process on connect.
 *
 * <p>The work queue is a quorum queue: RabbitMQ's recommended durable queue type. Its
 * delivery limit bounds how often one message can be redelivered (for example if it keeps
 * crashing workers) before it is dead-lettered instead of looping forever.
 */
@Configuration(proxyBeanMethods = false)
public class RabbitTopology {

    public static final String EXCHANGE = "hookguard.delivery";
    public static final String WORK_QUEUE = "hookguard.delivery.work";
    public static final String WORK_ROUTING_KEY = "delivery.work";
    public static final String DEAD_LETTER_EXCHANGE = "hookguard.delivery.dlx";
    public static final String DEAD_LETTER_QUEUE = "hookguard.delivery.work.dlq";

    static final int DELIVERY_LIMIT = 10;

    @Bean
    Declarables deliveryTopology() {
        DirectExchange exchange = ExchangeBuilder.directExchange(EXCHANGE).durable(true).build();
        DirectExchange deadLetterExchange = ExchangeBuilder.directExchange(DEAD_LETTER_EXCHANGE).durable(true).build();

        Queue workQueue = QueueBuilder.durable(WORK_QUEUE)
                .quorum()
                .deliveryLimit(DELIVERY_LIMIT)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(WORK_ROUTING_KEY)
                .build();
        Queue deadLetterQueue = QueueBuilder.durable(DEAD_LETTER_QUEUE).quorum().build();

        return new Declarables(
                exchange,
                deadLetterExchange,
                workQueue,
                deadLetterQueue,
                BindingBuilder.bind(workQueue).to(exchange).with(WORK_ROUTING_KEY),
                BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(WORK_ROUTING_KEY));
    }
}
