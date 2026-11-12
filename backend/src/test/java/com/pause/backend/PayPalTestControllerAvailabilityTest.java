package com.pause.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Confirms development-only PayPal routes are absent in the normal profile. */
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = "llm.api-key=")
class PayPalTestControllerAvailabilityTest {

    @Autowired
    private MockMvc mockMvc;

    // If a test route were accidentally registered, it still cannot reach PayPal.
    @MockitoBean
    private PayPalClient payPalClient;

    @Test
    void manualPayPalSideEffectRoutesAreUnavailableByDefault() throws Exception {
        mockMvc.perform(get("/paypal/test"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/paypal/orders/new"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/paypal/orders/TEST-ORDER/authorize"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/paypal/authorizations/TEST-AUTH/capture"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/paypal/authorizations/TEST-AUTH/void"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(payPalClient);
    }

    @Test
    void realPayPalReturnAndCancelCallbacksRemainAvailable() throws Exception {
        mockMvc.perform(get("/paypal/return").param("token", "UNKNOWN-ORDER"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("Unknown order. Nothing was changed."));
        mockMvc.perform(get("/paypal/cancel").param("token", "UNKNOWN-ORDER"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("Unknown order. No payment was cancelled."));

        verifyNoInteractions(payPalClient);
    }
}
