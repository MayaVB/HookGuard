package io.hookguard.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class HookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void reset() throws Exception {
        mockMvc.perform(delete("/received"));
        mockMvc.perform(put("/control/outage").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"));
    }

    @Test
    void flakyFailsTheConfiguredNumberOfTimesPerDeliveryThenSucceeds() throws Exception {
        mockMvc.perform(hook("/hooks/flaky?failTimes=2", "d-1")).andExpect(status().isServiceUnavailable());
        mockMvc.perform(hook("/hooks/flaky?failTimes=2", "d-1")).andExpect(status().isServiceUnavailable());
        mockMvc.perform(hook("/hooks/flaky?failTimes=2", "d-2")).andExpect(status().isServiceUnavailable());
        mockMvc.perform(hook("/hooks/flaky?failTimes=2", "d-1")).andExpect(status().isOk());
    }

    @Test
    void outageMakesEveryHookReturn503UntilSwitchedOff() throws Exception {
        mockMvc.perform(put("/control/outage").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(jsonPath("$.outage").value(true));
        mockMvc.perform(hook("/hooks/ok", "d-1")).andExpect(status().isServiceUnavailable());

        mockMvc.perform(put("/control/outage").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"));
        mockMvc.perform(hook("/hooks/ok", "d-1")).andExpect(status().isOk());
    }

    @Test
    void repeatedSuccessfulDeliveryIsFlaggedAsDuplicate() throws Exception {
        mockMvc.perform(hook("/hooks/ok", "d-1")).andExpect(jsonPath("$.duplicate").value(false));
        mockMvc.perform(hook("/hooks/ok", "d-1")).andExpect(jsonPath("$.duplicate").value(true));

        mockMvc.perform(get("/received"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].duplicate").value(true))
                .andExpect(jsonPath("$[0].deliveryId").value("d-1"));
    }

    @Test
    void arbitraryStatusCodes() throws Exception {
        mockMvc.perform(hook("/hooks/status/429", "d-1")).andExpect(status().isTooManyRequests());
        mockMvc.perform(hook("/hooks/status/700", "d-1")).andExpect(status().isBadRequest());
    }

    private static MockHttpServletRequestBuilder hook(String path, String deliveryId) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content("{\"n\":1}")
                .header("X-HookGuard-Delivery-Id", deliveryId);
    }
}
