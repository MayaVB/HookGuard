package io.hookguard.delivery.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import io.hookguard.delivery.DeliveryStatus;
import io.hookguard.support.IntegrationTestBase;

class OverviewIT extends IntegrationTestBase {

    @Test
    void overviewReportsBrokerAndWorkerActivity() throws Exception {
        UUID endpointId = registerEndpoint(stub.url("/hooks/overview"));
        UUID deliveryId = submitEvent(endpointId, "{\"n\":1}");
        awaitStatus(deliveryId, DeliveryStatus.DELIVERED);

        mockMvc.perform(get("/api/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serverTime").isNotEmpty())
                .andExpect(jsonPath("$.deliveries.byStatus.DELIVERED").value(1))
                .andExpect(jsonPath("$.attemptsRecorded").value(1))
                .andExpect(jsonPath("$.workQueue.reachable").value(true))
                .andExpect(jsonPath("$.workQueue.consumers").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.deadLetterQueue.reachable").value(true))
                .andExpect(jsonPath("$.workers[0].workerId").value("it-worker"))
                .andExpect(jsonPath("$.workers[0].attemptsLast10Minutes").value(1))
                .andExpect(jsonPath("$.workers[0].inFlight").value(0));
    }

    /** A worker that died mid-attempt leaves an IN_PROGRESS delivery whose lease has run out. */
    @Test
    void expiredLeaseIsFlaggedInListDetailAndWorkerActivity() throws Exception {
        UUID endpointId = jdbc.sql("INSERT INTO endpoints (url) VALUES (?) RETURNING id")
                .param(stub.url("/hooks/stuck")).query(UUID.class).single();
        UUID eventId = jdbc.sql("INSERT INTO events (event_type, payload) VALUES ('stuck.test', '{}'::json) RETURNING id")
                .query(UUID.class).single();
        UUID deliveryId = jdbc.sql("""
                        INSERT INTO deliveries (event_id, endpoint_id, status, attempt_count, claim_token, claimed_by, claim_expires_at)
                        VALUES (?, ?, 'IN_PROGRESS', 1, gen_random_uuid(), 'dead-worker', now() - interval '1 minute')
                        RETURNING id
                        """)
                .params(eventId, endpointId).query(UUID.class).single();

        mockMvc.perform(get("/api/deliveries"))
                .andExpect(jsonPath("$.items[0].id").value(deliveryId.toString()))
                .andExpect(jsonPath("$.items[0].claimedBy").value("dead-worker"))
                .andExpect(jsonPath("$.items[0].leaseExpired").value(true));
        mockMvc.perform(get("/api/deliveries/{id}", deliveryId))
                .andExpect(jsonPath("$.leaseExpired").value(true));
        mockMvc.perform(get("/api/overview"))
                .andExpect(jsonPath("$.workers[?(@.workerId == 'dead-worker')].expiredLeases").value(1));

        // Not part of the worker's in-flight tracking; remove it so the next test's idle check is unaffected.
        jdbc.sql("DELETE FROM deliveries WHERE id = ?").param(deliveryId).update();
    }

    @Test
    void demoEndpointPublishesDuplicatesThatTheClaimNeutralizes() throws Exception {
        stub.respondWith(200, "ok", 300);
        UUID endpointId = registerEndpoint(stub.url("/hooks/demo-dupes"));
        UUID deliveryId = submitEvent(endpointId, "{\"n\":1}");

        mockMvc.perform(post("/api/demo/deliveries/{id}/duplicates", deliveryId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"copies\":9}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.copies").value(9));

        awaitStatus(deliveryId, DeliveryStatus.DELIVERED);
        await().atMost(Duration.ofSeconds(10)).until(() -> readyMessages(io.hookguard.messaging.RabbitTopology.WORK_QUEUE) == 0);
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3))
                .until(() -> stub.receivedFor(deliveryId).size() == 1);
        assertThat(attemptCount(deliveryId)).isEqualTo(1);
    }

    @Test
    void demoEndpointValidatesInput() throws Exception {
        mockMvc.perform(get("/api/demo")).andExpect(jsonPath("$.receiverBaseUrl").isNotEmpty());
        mockMvc.perform(post("/api/demo/deliveries/{id}/duplicates", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"copies\":3}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/demo/deliveries/{id}/duplicates", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"copies\":500}"))
                .andExpect(status().isBadRequest());
    }
}
