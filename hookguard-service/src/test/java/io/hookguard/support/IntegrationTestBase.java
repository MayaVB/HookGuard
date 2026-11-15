package io.hookguard.support;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.jayway.jsonpath.JsonPath;

import io.hookguard.delivery.DeliveryStatus;
import io.hookguard.messaging.RabbitTopology;

/**
 * Full application (API + worker in one process) against real PostgreSQL and RabbitMQ containers.
 * All subclasses share one Spring context and one set of containers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
public abstract class IntegrationTestBase {

    protected static final StubWebhookServer stub = StubWebhookServer.shared();

    @DynamicPropertySource
    static void allowStubTargets(DynamicPropertyRegistry registry) {
        registry.add("hookguard.targets.allowed-hosts",
                () -> "localhost:" + stub.port() + ",localhost:" + stub.unusedPort());
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected AmqpAdmin amqpAdmin;

    /**
     * Wait until the worker has finished whatever a previous test started before wiping state;
     * otherwise an in-flight attempt would be recorded against a truncated delivery.
     */
    @BeforeEach
    void resetState() {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100))
                .until(() -> readyMessages(RabbitTopology.WORK_QUEUE) == 0 && attemptsInFlightByWorker() == 0);
        stub.reset();
        jdbc.sql("TRUNCATE delivery_attempts, deliveries, events, endpoints").update();
    }

    protected long readyMessages(String queue) {
        QueueInformation info = amqpAdmin.getQueueInfo(queue);
        return info == null ? 0 : info.getMessageCount();
    }

    private long attemptsInFlightByWorker() {
        return jdbc.sql("SELECT count(*) FROM deliveries WHERE status = 'IN_PROGRESS' AND claimed_by = 'it-worker'")
                .query(Long.class).single();
    }

    protected UUID registerEndpoint(String url) throws Exception {
        String response = mockMvc.perform(MockMvcRequestBuilders.post("/api/endpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + url + "\",\"description\":\"test\"}"))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    /** Submits an event and returns the created delivery ID. */
    protected UUID submitEvent(UUID endpointId, String payloadJson) throws Exception {
        String response = mockMvc.perform(MockMvcRequestBuilders.post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpointId\":\"" + endpointId + "\",\"eventType\":\"order.created\",\"payload\":"
                                + payloadJson + "}"))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.deliveryId"));
    }

    protected DeliveryStatus statusOf(UUID deliveryId) {
        return DeliveryStatus.valueOf(jdbc.sql("SELECT status FROM deliveries WHERE id = ?")
                .param(deliveryId).query(String.class).single());
    }

    protected void awaitStatus(UUID deliveryId, DeliveryStatus expected) {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(50))
                .until(() -> statusOf(deliveryId) == expected);
    }

    protected int attemptCount(UUID deliveryId) {
        return jdbc.sql("SELECT count(*) FROM delivery_attempts WHERE delivery_id = ?")
                .param(deliveryId).query(Integer.class).single();
    }
}
