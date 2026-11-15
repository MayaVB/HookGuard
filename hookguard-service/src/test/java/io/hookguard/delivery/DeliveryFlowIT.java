package io.hookguard.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import io.hookguard.delivery.http.WebhookHeaders;
import io.hookguard.messaging.RabbitTopology;
import io.hookguard.support.IntegrationTestBase;
import io.hookguard.support.StubWebhookServer.ReceivedRequest;

/** End to end: REST API -> PostgreSQL -> RabbitMQ -> worker -> HTTP receiver -> recorded result. */
class DeliveryFlowIT extends IntegrationTestBase {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Test
    void deliversEventAndRecordsTheAttempt() throws Exception {
        UUID endpointId = registerEndpoint(stub.url("/hooks/orders"));

        UUID deliveryId = submitEvent(endpointId, "{\"orderId\":42,\"total\":99.5}");
        awaitStatus(deliveryId, DeliveryStatus.DELIVERED);

        assertThat(stub.receivedFor(deliveryId)).hasSize(1);
        ReceivedRequest request = stub.receivedFor(deliveryId).getFirst();
        assertThat(request.path()).isEqualTo("/hooks/orders");
        assertThat(request.header(WebhookHeaders.DELIVERY_ID)).isEqualTo(deliveryId.toString());
        assertThat(request.header(WebhookHeaders.EVENT_TYPE)).isEqualTo("order.created");
        assertThat(request.header(WebhookHeaders.ATTEMPT)).isEqualTo("1");
        assertThat(request.body()).isEqualTo("{\"orderId\":42,\"total\":99.5}");

        mockMvc.perform(get("/api/deliveries/{id}", deliveryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.attemptCount").value(1))
                .andExpect(jsonPath("$.lastHttpStatus").value(200))
                .andExpect(jsonPath("$.claimedBy").doesNotExist())
                .andExpect(jsonPath("$.event.payload.orderId").value(42))
                .andExpect(jsonPath("$.attempts.length()").value(1))
                .andExpect(jsonPath("$.attempts[0].outcome").value("SUCCESS"))
                .andExpect(jsonPath("$.attempts[0].httpStatus").value(200))
                .andExpect(jsonPath("$.attempts[0].workerId").value("it-worker"));
    }

    /** Receivers get the same document the producer submitted: key order and exact decimals preserved. */
    @Test
    void payloadIsDeliveredWithKeyOrderAndNumericPrecisionIntact() throws Exception {
        String payload = "{\"zeta\":1,\"alpha\":{\"y\":true,\"b\":null},\"amount\":10.50,"
                + "\"precise\":0.1000000000000000055511151231257827,\"big\":123456789012345678901234567890}";
        UUID endpointId = registerEndpoint(stub.url("/hooks/fidelity"));

        UUID deliveryId = submitEvent(endpointId, payload);
        awaitStatus(deliveryId, DeliveryStatus.DELIVERED);

        assertThat(stub.receivedFor(deliveryId).getFirst().body()).isEqualTo(payload);
    }

    @Test
    void serverErrorFailsTheDeliveryAndKeepsTheResponseForDiagnosis() throws Exception {
        stub.respondWith(500, "{\"error\":\"database unavailable\"}");
        UUID endpointId = registerEndpoint(stub.url("/hooks/broken"));

        UUID deliveryId = submitEvent(endpointId, "{\"n\":1}");
        awaitStatus(deliveryId, DeliveryStatus.FAILED);

        mockMvc.perform(get("/api/deliveries/{id}", deliveryId))
                .andExpect(jsonPath("$.lastHttpStatus").value(500))
                .andExpect(jsonPath("$.lastError").value("Endpoint responded with HTTP 500"))
                .andExpect(jsonPath("$.attempts[0].outcome").value("HTTP_ERROR"))
                .andExpect(jsonPath("$.attempts[0].responseBody").value("{\"error\":\"database unavailable\"}"));
    }

    @Test
    void slowEndpointTimesOut() throws Exception {
        stub.respondWith(200, "too late", 4_000); // request timeout in tests is 2s
        UUID endpointId = registerEndpoint(stub.url("/hooks/slow"));

        UUID deliveryId = submitEvent(endpointId, "{\"n\":1}");
        awaitStatus(deliveryId, DeliveryStatus.FAILED);

        mockMvc.perform(get("/api/deliveries/{id}", deliveryId))
                .andExpect(jsonPath("$.attempts[0].outcome").value("TIMEOUT"))
                .andExpect(jsonPath("$.attempts[0].httpStatus").doesNotExist());
    }

    @Test
    void unreachableEndpointIsAConnectionError() throws Exception {
        UUID endpointId = registerEndpoint("http://localhost:" + stub.unusedPort() + "/hooks");

        UUID deliveryId = submitEvent(endpointId, "{\"n\":1}");
        awaitStatus(deliveryId, DeliveryStatus.FAILED);

        mockMvc.perform(get("/api/deliveries/{id}", deliveryId))
                .andExpect(jsonPath("$.attempts[0].outcome").value("CONNECTION_ERROR"));
    }

    /**
     * Ten copies of the same message (one from the API, nine injected) are consumed by four
     * concurrent consumers while the receiver is slow. The atomic claim lets exactly one through.
     */
    @Test
    void duplicateMessagesDoNotCauseDuplicateRequests() throws Exception {
        stub.respondWith(200, "ok", 500);
        UUID endpointId = registerEndpoint(stub.url("/hooks/dupes"));

        UUID deliveryId = submitEvent(endpointId, "{\"n\":1}");
        for (int i = 0; i < 9; i++) {
            rabbitTemplate.convertAndSend(RabbitTopology.EXCHANGE, RabbitTopology.WORK_ROUTING_KEY, deliveryId.toString());
        }

        awaitStatus(deliveryId, DeliveryStatus.DELIVERED);
        await().atMost(Duration.ofSeconds(10)).until(() -> messagesIn(RabbitTopology.WORK_QUEUE) == 0);
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3))
                .until(() -> stub.receivedFor(deliveryId).size() == 1);

        assertThat(attemptCount(deliveryId)).isEqualTo(1);
    }

    @Test
    void messageForUnknownDeliveryIsAcknowledgedAndDropped() {
        long deadLettersBefore = messagesIn(RabbitTopology.DEAD_LETTER_QUEUE);

        rabbitTemplate.convertAndSend(RabbitTopology.EXCHANGE, RabbitTopology.WORK_ROUTING_KEY, UUID.randomUUID().toString());

        await().atMost(Duration.ofSeconds(10)).until(() -> messagesIn(RabbitTopology.WORK_QUEUE) == 0);
        assertThat(messagesIn(RabbitTopology.DEAD_LETTER_QUEUE)).isEqualTo(deadLettersBefore);
        assertThat(stub.received()).isEmpty();
    }

    @Test
    void malformedMessageIsDeadLetteredNotRetriedForever() {
        long deadLettersBefore = messagesIn(RabbitTopology.DEAD_LETTER_QUEUE);

        rabbitTemplate.convertAndSend(RabbitTopology.EXCHANGE, RabbitTopology.WORK_ROUTING_KEY, "not-a-uuid");

        await().atMost(Duration.ofSeconds(10))
                .until(() -> messagesIn(RabbitTopology.DEAD_LETTER_QUEUE) == deadLettersBefore + 1);
    }

    private long messagesIn(String queue) {
        return readyMessages(queue);
    }
}
