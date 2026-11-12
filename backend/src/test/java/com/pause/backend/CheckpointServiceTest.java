package com.pause.backend;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.TestPropertySource;
import org.springframework.security.test.context.support.WithMockUser;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for POST /api/checkpoint using MockMvc.
 *
 * Spring concept - @AutoConfigureMockMvc (Spring Boot 4 package): sets up
 * MockMvc automatically so we can send HTTP requests through the full
 * request/response pipeline without starting a real network server.
 *
 * Spring concept - MockMvc: lets tests call controller endpoints and assert
 * on the HTTP status and JSON body — no real HTTP port needed.
 */
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = "llm.api-key=")
@WithMockUser(username = "rose@demo.pause.local")
class CheckpointServiceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper; // Spring's JSON serializer/deserializer

    // -----------------------------------------------------------------------
    // Test 1: normal payment — should be ALLOW
    // -----------------------------------------------------------------------

    @Test
    void normalPaymentShouldBeAllow() throws Exception {
        CheckpointRequest req = new CheckpointRequest();
        req.setPayee("Grocery Store");
        req.setAmount(new BigDecimal("30.00"));
        req.setMessage("Your pharmacy order is ready.");
        req.setHour(12);

        MvcResult mvcResult = mockMvc.perform(post("/api/checkpoint").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();

        CheckpointResult result = objectMapper.readValue(
                mvcResult.getResponse().getContentAsString(), CheckpointResult.class);

        assertEquals(Decision.ALLOW, result.getDecision(),
                "A normal $30 payment to a known payee at noon should be ALLOW");
        assertTrue(result.getReasons().isEmpty(),
                "An ALLOW decision should have no reasons");
        assertNotNull(result.getPaymentRecordId(),
                "A PaymentRecord should have been saved");
    }

    // -----------------------------------------------------------------------
    // Test 2: scam scenario — HOLD or BLOCK with at least 5 reasons
    // -----------------------------------------------------------------------

    @Test
    void scamPaymentShouldBeHoldOrBlock() throws Exception {
        CheckpointRequest req = new CheckpointRequest();
        req.setPayee("Bank Safe Account");
        req.setAmount(new BigDecimal("800.00"));
        req.setHour(2);
        req.setMessage("URGENT: your bank account is locked, pay within 10 minutes "
                + "to the safe account at http://paypa1-secure.xyz/verify, "
                + "don't tell anyone.");

        MvcResult mvcResult = mockMvc.perform(post("/api/checkpoint").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();

        CheckpointResult result = objectMapper.readValue(
                mvcResult.getResponse().getContentAsString(), CheckpointResult.class);

        assertNotEquals(Decision.ALLOW, result.getDecision(),
                "An $800 scam payment at 2 AM should not be ALLOW");
        assertTrue(result.getReasons().size() >= 5,
                "Expected at least 5 reasons, got: " + result.getReasons().size());
    }

    // -----------------------------------------------------------------------
    // Test 3: safe account phrase triggers standing order even for small amount
    // -----------------------------------------------------------------------

    @Test
    void safeAccountPhraseAlwaysHolds() throws Exception {
        CheckpointRequest req = new CheckpointRequest();
        req.setPayee("Grocery Store");
        req.setAmount(new BigDecimal("25.00"));
        req.setHour(12);
        req.setMessage("move it to the safe account");

        MvcResult mvcResult = mockMvc.perform(post("/api/checkpoint").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();

        CheckpointResult result = objectMapper.readValue(
                mvcResult.getResponse().getContentAsString(), CheckpointResult.class);

        assertNotEquals(Decision.ALLOW, result.getDecision(),
                "'safe account' in the message must trigger at least HOLD");
    }

    // -----------------------------------------------------------------------
    // Test 4: invalid input returns HTTP 400, no stack trace
    // -----------------------------------------------------------------------

    @Test
    void invalidInputReturnsBadRequest() throws Exception {
        // Negative amount — should fail @DecimalMin validation.
        CheckpointRequest badAmount = new CheckpointRequest();
        badAmount.setPayee("Grocery Store");
        badAmount.setAmount(new BigDecimal("-5.00"));

        MvcResult r1 = mockMvc.perform(post("/api/checkpoint").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badAmount)))
                .andExpect(status().isBadRequest())
                .andReturn();

        String body1 = r1.getResponse().getContentAsString();
        assertTrue(body1.contains("error"),
                "400 response must contain an 'error' key, got: " + body1);

        // Blank payee — should fail @NotBlank validation.
        CheckpointRequest blankPayee = new CheckpointRequest();
        blankPayee.setPayee("   ");
        blankPayee.setAmount(new BigDecimal("30.00"));

        MvcResult r2 = mockMvc.perform(post("/api/checkpoint").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(blankPayee)))
                .andExpect(status().isBadRequest())
                .andReturn();

        String body2 = r2.getResponse().getContentAsString();
        assertTrue(body2.contains("error"),
                "400 response must contain an 'error' key, got: " + body2);
    }
}
