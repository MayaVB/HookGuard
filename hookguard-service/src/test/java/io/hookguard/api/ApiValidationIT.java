package io.hookguard.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import io.hookguard.support.IntegrationTestBase;

class ApiValidationIT extends IntegrationTestBase {

    @Test
    void registersAllowedEndpoint() throws Exception {
        postJson("/api/endpoints", "{\"url\":\"" + stub.url("/hooks/ok") + "\",\"description\":\"Orders\"}")
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.description").value("Orders"));
    }

    @Test
    void rejectsTargetsOutsideTheAllowlist() throws Exception {
        postJson("/api/endpoints", "{\"url\":\"http://169.254.169.254/latest/meta-data\"}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("url"))
                .andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.containsString("not in the allowed")));
        postJson("/api/endpoints", "{\"url\":\"ftp://localhost:" + stub.port() + "/x\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("URL scheme must be http or https"));
        postJson("/api/endpoints", "{\"url\":\"   \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("url"));
    }

    @Test
    void rejectsInvalidEvents() throws Exception {
        UUID endpointId = registerEndpoint(stub.url("/hooks/ok"));

        postJson("/api/events", event(UUID.randomUUID(), "order.created", "{}"))
                .andExpect(status().isNotFound());
        postJson("/api/events", event(endpointId, "order.created", "[1,2,3]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("payload"));
        postJson("/api/events", event(endpointId, "has spaces!", "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("eventType"));
        postJson("/api/events", "{\"endpointId\":\"" + endpointId + "\",\"eventType\":\"x\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("payload"));
        postJson("/api/events", "{not json")
                .andExpect(status().isBadRequest());
        postJson("/api/events", event(endpointId, "order.created", "{\"blob\":\"" + "x".repeat(3000) + "\"}"))
                .andExpect(status().is(413));
    }

    @Test
    void acceptsValidEventWith202AndLocation() throws Exception {
        UUID endpointId = registerEndpoint(stub.url("/hooks/ok"));

        String body = postJson("/api/events", event(endpointId, "order.created", "{\"orderId\":1}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/deliveries/")))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();

        UUID deliveryId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.deliveryId"));
        awaitStatus(deliveryId, io.hookguard.delivery.DeliveryStatus.DELIVERED);
    }

    @Test
    void queryErrors() throws Exception {
        mockMvc.perform(get("/api/deliveries/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/deliveries/not-a-uuid")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/deliveries").param("size", "1000")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/deliveries").param("status", "NOPE")).andExpect(status().isBadRequest());
    }

    @Test
    void statsIncludeEveryStatus() throws Exception {
        mockMvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.byStatus.PENDING").value(0))
                .andExpect(jsonPath("$.byStatus.IN_PROGRESS").value(0))
                .andExpect(jsonPath("$.byStatus.DELIVERED").value(0))
                .andExpect(jsonPath("$.byStatus.FAILED").value(0));
    }

    private ResultActions postJson(String path, String json) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String event(UUID endpointId, String eventType, String payload) {
        return "{\"endpointId\":\"" + endpointId + "\",\"eventType\":\"" + eventType + "\",\"payload\":" + payload + "}";
    }
}
