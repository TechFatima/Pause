package com.pause.backend;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the full payment flow (initiate → return → capture/hold).
 * PayPalClient is replaced with a Mockito mock so no real network calls are made.
 *
 * Spring concept - @MockitoBean: replaces the real PayPalClient Spring bean with a
 * test double for the duration of this test class — no network calls happen.
 */
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = "llm.api-key=")
class PaymentFlowTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PaymentRecordRepository paymentRepo;

    // Replace PayPalClient with a mock — all tests share this mock.
    @MockitoBean
    private PayPalClient payPalClient;

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** POSTs to /api/payments/initiate and returns the parsed JSON response. */
    private JsonNode initiate(String payee, double amount, int hour, String message)
            throws Exception {
        CheckpointRequest req = new CheckpointRequest();
        req.setPayee(payee);
        req.setAmount(BigDecimal.valueOf(amount));
        req.setHour(hour);
        req.setMessage(message);

        MvcResult result = mockMvc.perform(post("/api/payments/initiate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** Stubs PayPalClient to return a fake order with the given id. */
    private void stubOrder(String orderId) {
        when(payPalClient.createAuthorizeOrder(anyString()))
                .thenReturn(new PayPalClient.OrderResult(
                        orderId, "PAYER_ACTION_REQUIRED",
                        "https://sandbox.paypal.com/approve/" + orderId));
    }

    /** Stubs PayPalClient.authorizeOrder to return a fake authorization id. */
    private void stubAuthorize(String orderId, String authId) {
        when(payPalClient.authorizeOrder(orderId))
                .thenReturn(new PayPalClient.AuthorizationResult(authId, "CREATED"));
        when(payPalClient.getOrderDetails(orderId))
                .thenReturn(new PayPalClient.OrderDetails("COMPLETED", authId, "CREATED"));
    }

    private PaymentRecord createHeldPayment(String orderId, String authId) throws Exception {
        stubOrder(orderId);
        stubAuthorize(orderId, authId);
        JsonNode response = initiate("New Trusted Contact Payee " + orderId, 30.00, 12, "");
        assertEquals("HOLD", response.get("decision").asText());
        mockMvc.perform(get("/paypal/return").param("token", orderId))
                .andExpect(status().isOk());
        return paymentRepo.findByPaypalOrderId(orderId).orElseThrow();
    }

    // -----------------------------------------------------------------------
    // Test 1: BLOCK — PayPal is never called
    // -----------------------------------------------------------------------

    @Test
    void blockDecisionNeverCallsPayPal() throws Exception {
        // $800 to a new payee triggers BLOCK
        JsonNode resp = initiate("Bank Safe Account", 800.00, 2,
                "URGENT: your bank account is locked, pay within 10 minutes " +
                "to the safe account at http://paypa1-secure.xyz/verify, don't tell anyone.");

        assertEquals("BLOCK", resp.get("decision").asText());
        assertTrue(resp.get("approveUrl").isNull(),
                "approveUrl must be null for BLOCK");

        // PayPal must never be called.
        verify(payPalClient, never()).createAuthorizeOrder(anyString());

        // Record must be BLOCKED.
        Long recordId = resp.get("paymentRecordId").asLong();
        PaymentRecord record = paymentRepo.findById(recordId).orElseThrow();
        assertEquals("BLOCKED", record.getStatus());
        assertEquals(Decision.BLOCK, record.getDecision());
    }

    // -----------------------------------------------------------------------
    // Test 2: ALLOW → return → capture succeeds → COMPLETED
    // -----------------------------------------------------------------------

    @Test
    void allowFlowLeadsToCaptureAndCompleted() throws Exception {
        String orderId = "ORDER-ALLOW-001";
        String authId  = "AUTH-ALLOW-001";
        stubOrder(orderId);
        stubAuthorize(orderId, authId);
        when(payPalClient.captureAuthorization(authId)).thenReturn("COMPLETED");

        JsonNode resp = initiate("Grocery Store", 30.00, 12, "Your pharmacy order is ready.");
        assertEquals("ALLOW", resp.get("decision").asText());
        assertFalse(resp.get("approveUrl").isNull(), "ALLOW must return an approveUrl");

        // Simulate buyer returning from PayPal.
        mockMvc.perform(get("/paypal/return").param("token", orderId))
                .andExpect(status().isOk())
                .andReturn();

        // Record must be COMPLETED; capture must have been called exactly once.
        PaymentRecord record = paymentRepo.findByPaypalOrderId(orderId).orElseThrow();
        assertEquals("COMPLETED", record.getStatus());
        assertEquals(Decision.ALLOW, record.getDecision());
        verify(payPalClient, times(1)).captureAuthorization(authId);
    }

    // -----------------------------------------------------------------------
    // Test 3: HOLD → return → no capture → HELD with trustedToken set
    // -----------------------------------------------------------------------

    @Test
    void holdFlowLeadsToHeldWithToken() throws Exception {
        String orderId = "ORDER-HOLD-001";
        String authId  = "AUTH-HOLD-001";
        stubOrder(orderId);
        stubAuthorize(orderId, authId);

        // $30 to a brand-new payee at noon — fires NEW_PAYEE (40 pts) → HOLD via standing order.
        // Amount is within normal range so no other rules fire; score stays 40 → exactly HOLD.
        JsonNode resp = initiate("New Unknown Payee XYZ", 30.00, 12, "");
        assertEquals("HOLD", resp.get("decision").asText(),
                "A new payee should trigger HOLD via the NEW_PAYEE standing order");

        mockMvc.perform(get("/paypal/return").param("token", orderId))
                .andExpect(status().isOk())
                .andReturn();

        PaymentRecord record = paymentRepo.findByPaypalOrderId(orderId).orElseThrow();
        assertEquals("HELD", record.getStatus());
        assertEquals(Decision.HOLD, record.getDecision());

        // trustedToken must be set in the DB but never exposed in the response.
        assertNotNull(record.getTrustedToken(), "trustedToken must be set for HELD");
        assertNotNull(record.getTrustedTokenExpiresAt(), "expiry must be set for HELD");

        // Capture must never have been called.
        verify(payPalClient, never()).captureAuthorization(anyString());
    }

    // -----------------------------------------------------------------------
    // Test 4: calling /paypal/return twice is idempotent
    // -----------------------------------------------------------------------

    @Test
    void returnCalledTwiceIsIdempotent() throws Exception {
        String orderId = "ORDER-IDEM-001";
        String authId  = "AUTH-IDEM-001";
        stubOrder(orderId);
        stubAuthorize(orderId, authId);
        when(payPalClient.captureAuthorization(authId)).thenReturn("COMPLETED");

        initiate("Grocery Store", 30.00, 12, "");

        // First return — should authorize and capture.
        mockMvc.perform(get("/paypal/return").param("token", orderId))
                .andExpect(status().isOk());

        // Second return — must be a no-op; authorize must still have been called only once.
        mockMvc.perform(get("/paypal/return").param("token", orderId))
                .andExpect(status().isOk());

        verify(payPalClient, times(1)).authorizeOrder(orderId);
        verify(payPalClient, times(1)).captureAuthorization(authId);
    }

    // -----------------------------------------------------------------------
    // Test 5: unknown order id returns 404
    // -----------------------------------------------------------------------

    @Test
    void unknownOrderIdReturns404() throws Exception {
        mockMvc.perform(get("/paypal/return").param("token", "UNKNOWN-ORDER-XYZ"))
                .andExpect(status().isNotFound());
    }

    // -----------------------------------------------------------------------
    // Test 6: failed capture sets PAYPAL_ERROR, not COMPLETED
    // -----------------------------------------------------------------------

    @Test
    void failedCaptureSetsPaylPalError() throws Exception {
        String orderId = "ORDER-ERR-001";
        String authId  = "AUTH-ERR-001";
        stubOrder(orderId);
        stubAuthorize(orderId, authId);

        // Capture throws a PayPal error.
        when(payPalClient.captureAuthorization(authId))
                .thenThrow(new PayPalClient.PayPalClientException(422, "capture failed"));

        initiate("Grocery Store", 30.00, 12, "");

        mockMvc.perform(get("/paypal/return").param("token", orderId))
                .andExpect(status().isBadGateway());

        mockMvc.perform(get("/paypal/return").param("token", orderId))
                .andExpect(status().isOk());

        PaymentRecord record = paymentRepo.findByPaypalOrderId(orderId).orElseThrow();
        assertEquals("PAYPAL_ERROR", record.getStatus(),
                "Record must be PAYPAL_ERROR after a failed capture, never COMPLETED");
        verify(payPalClient, times(1)).captureAuthorization(authId);
    }

    // -----------------------------------------------------------------------
    // Test 7: GET /api/payments does not expose trustedToken or trustedTokenExpiresAt
    // -----------------------------------------------------------------------

    @Test
    void paymentsApiDoesNotExposeTrustedToken() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/payments"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains("trustedToken"),
                "trustedToken must never appear in GET /api/payments response");
        assertFalse(body.contains("trustedTokenExpiresAt"),
                "trustedTokenExpiresAt must never appear in GET /api/payments response");
    }

    // -----------------------------------------------------------------------
    // Test 8: auth failure when order not yet approved → AUTH_FAILED (retryable)
    // -----------------------------------------------------------------------

    @Test
    void authFailureWhenNotYetApprovedSetsAuthFailed() throws Exception {
        String orderId = "ORDER-AUTH-FAIL-001";
        stubOrder(orderId);

        // authorizeOrder throws; getOrderDetails returns PAYER_ACTION_REQUIRED.
        when(payPalClient.authorizeOrder(orderId))
                .thenThrow(new PayPalClient.PayPalClientException(422, "ORDER_NOT_APPROVED"));
        when(payPalClient.getOrderDetails(orderId))
                .thenReturn(new PayPalClient.OrderDetails("PAYER_ACTION_REQUIRED", null, null));

        initiate("Grocery Store", 30.00, 12, "");

        // First return — buyer hasn't approved yet.
        MvcResult r = mockMvc.perform(get("/paypal/return").param("token", orderId))
                .andExpect(status().isOk())
                .andReturn();

        assertTrue(r.getResponse().getContentAsString().contains("could not confirm the payment state"),
                "An uncertain PayPal state should be explained without claiming payment success");

        PaymentRecord record = paymentRepo.findByPaypalOrderId(orderId).orElseThrow();
        assertEquals("AUTH_FAILED", record.getStatus(),
                "Record must be AUTH_FAILED, not PAYPAL_ERROR, when buyer hasn't approved yet");
        assertNull(record.getAuthorizationId(),
                "authorizationId must not be set when authorization was never confirmed");

        // authorizeOrder and getOrderDetails each called exactly once.
        verify(payPalClient, times(1)).authorizeOrder(orderId);
        verify(payPalClient, times(1)).getOrderDetails(orderId);
        verify(payPalClient, never()).captureAuthorization(anyString());
    }

    // -----------------------------------------------------------------------
    // Test 9: retry after AUTH_FAILED succeeds when order is now APPROVED
    // -----------------------------------------------------------------------

    @Test
    void retryAfterAuthFailedSucceedsWhenOrderApproved() throws Exception {
        String orderId = "ORDER-RETRY-001";
        String authId  = "AUTH-RETRY-001";
        stubOrder(orderId);

        // First call: authorize throws; getOrderDetails says not yet approved.
        when(payPalClient.authorizeOrder(orderId))
                .thenThrow(new PayPalClient.PayPalClientException(422, "ORDER_NOT_APPROVED"))
                .thenReturn(new PayPalClient.AuthorizationResult(authId, "CREATED")); // second call succeeds
        when(payPalClient.getOrderDetails(orderId))
                .thenReturn(
                        new PayPalClient.OrderDetails("PAYER_ACTION_REQUIRED", null, null),
                        new PayPalClient.OrderDetails("APPROVED", null, null),
                        new PayPalClient.OrderDetails("APPROVED", authId, "CREATED"));
        when(payPalClient.captureAuthorization(authId)).thenReturn("COMPLETED");

        initiate("Grocery Store", 30.00, 12, "");

        // First return: order not approved → AUTH_FAILED.
        mockMvc.perform(get("/paypal/return").param("token", orderId))
                .andExpect(status().isOk());
        assertEquals("AUTH_FAILED", paymentRepo.findByPaypalOrderId(orderId).orElseThrow().getStatus());

        // The retry first checks APPROVED/no authorization, then verifies the new authorization.
        mockMvc.perform(get("/paypal/return").param("token", orderId))
                .andExpect(status().isOk());

        PaymentRecord record = paymentRepo.findByPaypalOrderId(orderId).orElseThrow();
        assertEquals("COMPLETED", record.getStatus(),
                "After a successful retry, status must be COMPLETED");

        // authorizeOrder called twice; the retry preflight never authorizes blindly.
        verify(payPalClient, times(2)).authorizeOrder(orderId);
        verify(payPalClient, times(3)).getOrderDetails(orderId);
        verify(payPalClient, times(1)).captureAuthorization(authId);
    }

    // -----------------------------------------------------------------------
    // Test 10: existing authorization recovered — authorize not duplicated
    // -----------------------------------------------------------------------

    @Test
    void existingAuthorizationNotDuplicated() throws Exception {
        String orderId = "ORDER-EXIST-AUTH-001";
        String existingAuthId = "AUTH-EXISTING-001";
        stubOrder(orderId);

        // authorizeOrder throws; getOrderDetails reveals an existing CREATED authorization.
        when(payPalClient.authorizeOrder(orderId))
                .thenThrow(new PayPalClient.PayPalClientException(422, "ORDER_NOT_APPROVED"));
        when(payPalClient.getOrderDetails(orderId))
                .thenReturn(new PayPalClient.OrderDetails(
                        "APPROVED", existingAuthId, "CREATED"));
        when(payPalClient.captureAuthorization(existingAuthId)).thenReturn("COMPLETED");

        initiate("Grocery Store", 30.00, 12, "");

        mockMvc.perform(get("/paypal/return").param("token", orderId))
                .andExpect(status().isOk());

        PaymentRecord record = paymentRepo.findByPaypalOrderId(orderId).orElseThrow();
        assertEquals("COMPLETED", record.getStatus(),
                "Existing active authorization must be used and result in COMPLETED");
        assertEquals(existingAuthId, record.getAuthorizationId(),
                "The recovered authorizationId must be saved on the record");

        // authorizeOrder called exactly once (the original failed attempt); never called again.
        verify(payPalClient, times(1)).authorizeOrder(orderId);
        // Capture called once using the existing auth id, not a new one.
        verify(payPalClient, times(1)).captureAuthorization(existingAuthId);
    }

    // -----------------------------------------------------------------------
    // Test 11: capture returns non-COMPLETED status — never sets COMPLETED
    // -----------------------------------------------------------------------

    @Test
    void captureNonCompletedStatusSetsPayPalError() throws Exception {
        String orderId = "ORDER-CAP-STATUS-001";
        String authId  = "AUTH-CAP-STATUS-001";
        stubOrder(orderId);
        stubAuthorize(orderId, authId);

        // captureAuthorization returns an unexpected status (not COMPLETED).
        when(payPalClient.captureAuthorization(authId)).thenReturn("PENDING");

        initiate("Grocery Store", 30.00, 12, "");

        mockMvc.perform(get("/paypal/return").param("token", orderId))
                .andExpect(status().isBadGateway());

        PaymentRecord record = paymentRepo.findByPaypalOrderId(orderId).orElseThrow();
        assertEquals("PAYPAL_ERROR", record.getStatus(),
                "A non-COMPLETED capture status must result in PAYPAL_ERROR, never COMPLETED");
    }

    // -----------------------------------------------------------------------
    // Test 12: cancel after AUTH_FAILED sets CANCELLED (distinct from ALREADY_PROCESSED)
    // -----------------------------------------------------------------------

    @Test
    void cancelAfterAuthFailedSetsCancelled() throws Exception {
        String orderId = "ORDER-CANCEL-AFTER-FAIL-001";
        stubOrder(orderId);

        // authorizeOrder throws; getOrderDetails says not yet approved.
        when(payPalClient.authorizeOrder(orderId))
                .thenThrow(new PayPalClient.PayPalClientException(422, "ORDER_NOT_APPROVED"));
        when(payPalClient.getOrderDetails(orderId))
                .thenReturn(
                        new PayPalClient.OrderDetails("PAYER_ACTION_REQUIRED", null, null),
                        new PayPalClient.OrderDetails("PAYER_ACTION_REQUIRED", null, null));

        initiate("Grocery Store", 30.00, 12, "");

        // First return sets AUTH_FAILED.
        mockMvc.perform(get("/paypal/return").param("token", orderId))
                .andExpect(status().isOk());
        assertEquals("AUTH_FAILED", paymentRepo.findByPaypalOrderId(orderId).orElseThrow().getStatus());

        // Cancel the AUTH_FAILED record.
        MvcResult cancelResult = mockMvc.perform(get("/paypal/cancel").param("token", orderId))
                .andExpect(status().isOk())
                .andReturn();

        assertTrue(cancelResult.getResponse().getContentAsString().contains("cancelled"),
                "Cancel response must mention cancellation");

        PaymentRecord record = paymentRepo.findByPaypalOrderId(orderId).orElseThrow();
        assertEquals("CANCELLED", record.getStatus(),
                "After cancelling an AUTH_FAILED record, status must be CANCELLED");
    }

    // -----------------------------------------------------------------------
    // Test 13: unknown order cancellation returns 404 without claiming success
    // -----------------------------------------------------------------------

    @Test
    void cancelUnknownOrderReturnsNotFoundWithoutClaimingCancellation() throws Exception {
        MvcResult result = mockMvc.perform(get("/paypal/cancel").param("token", "UNKNOWN-CANCEL-ORDER"))
                .andExpect(status().isNotFound())
                .andReturn();

        String message = result.getResponse().getContentAsString();
        assertTrue(message.contains("Unknown order"));
        assertEquals("Unknown order. No payment was cancelled.", message,
                "Unknown orders must clearly state that no payment was cancelled");
    }

    // -----------------------------------------------------------------------
    // Test 14: processed payment cancellation is reported accurately
    // -----------------------------------------------------------------------

    @Test
    void cancelAlreadyProcessedPaymentDoesNotClaimCancellation() throws Exception {
        String orderId = "ORDER-CANCEL-PROCESSED-001";
        String authId = "AUTH-CANCEL-PROCESSED-001";
        stubOrder(orderId);
        stubAuthorize(orderId, authId);
        when(payPalClient.captureAuthorization(authId)).thenReturn("COMPLETED");

        initiate("Grocery Store", 30.00, 12, "");
        mockMvc.perform(get("/paypal/return").param("token", orderId))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(get("/paypal/cancel").param("token", orderId))
                .andExpect(status().isOk())
                .andReturn();

        String message = result.getResponse().getContentAsString();
        assertTrue(message.contains("already processed"));
        assertTrue(message.contains("not cancelled"));
        assertFalse(message.startsWith("Payment cancelled"),
                "A completed payment must not be reported as cancelled");
        assertEquals("COMPLETED", paymentRepo.findByPaypalOrderId(orderId).orElseThrow().getStatus());
    }

    @Test
    void authRetryChecksPayPalBeforeAuthorizingAgain() throws Exception {
        String orderId = "ORDER-RETRY-UNKNOWN-001";
        stubOrder(orderId);
        when(payPalClient.authorizeOrder(orderId))
                .thenThrow(new PayPalClient.PayPalClientException(502, "safe error"));
        when(payPalClient.getOrderDetails(orderId))
                .thenThrow(new PayPalClient.PayPalClientException(502, "safe error"));

        initiate("Grocery Store", 30.00, 12, "");
        mockMvc.perform(get("/paypal/return").param("token", orderId)).andExpect(status().isOk());
        mockMvc.perform(get("/paypal/return").param("token", orderId)).andExpect(status().isOk());

        verify(payPalClient, times(1)).authorizeOrder(orderId);
        verify(payPalClient, times(2)).getOrderDetails(orderId);
        assertEquals("AUTH_FAILED", paymentRepo.findByPaypalOrderId(orderId).orElseThrow().getStatus());
    }

    @Test
    void authRetryUsesExistingAuthorizationInsteadOfAuthorizingAgain() throws Exception {
        String orderId = "ORDER-RETRY-EXISTING-AUTH-001";
        String authId = "AUTH-RETRY-EXISTING-001";
        stubOrder(orderId);
        when(payPalClient.authorizeOrder(orderId))
                .thenThrow(new PayPalClient.PayPalClientException(502, "safe error"));
        when(payPalClient.getOrderDetails(orderId))
                .thenThrow(new PayPalClient.PayPalClientException(502, "safe error"))
                .thenReturn(new PayPalClient.OrderDetails("COMPLETED", authId, "CREATED"));
        when(payPalClient.captureAuthorization(authId)).thenReturn("COMPLETED");

        initiate("Grocery Store", 30.00, 12, "");
        mockMvc.perform(get("/paypal/return").param("token", orderId)).andExpect(status().isOk());
        assertEquals("AUTH_FAILED", paymentRepo.findByPaypalOrderId(orderId).orElseThrow().getStatus());

        mockMvc.perform(get("/paypal/return").param("token", orderId)).andExpect(status().isOk());

        assertEquals("COMPLETED", paymentRepo.findByPaypalOrderId(orderId).orElseThrow().getStatus());
        verify(payPalClient, times(1)).authorizeOrder(orderId);
        verify(payPalClient, times(1)).captureAuthorization(authId);
    }

    @Test
    void cancellationWithUnknownPayPalStateDoesNotClaimCancelled() throws Exception {
        String orderId = "ORDER-CANCEL-UNKNOWN-001";
        stubOrder(orderId);
        when(payPalClient.authorizeOrder(orderId))
                .thenThrow(new PayPalClient.PayPalClientException(502, "safe error"));
        when(payPalClient.getOrderDetails(orderId))
                .thenThrow(new PayPalClient.PayPalClientException(502, "safe error"));

        initiate("Grocery Store", 30.00, 12, "");
        mockMvc.perform(get("/paypal/return").param("token", orderId)).andExpect(status().isOk());
        MvcResult result = mockMvc.perform(get("/paypal/cancel").param("token", orderId))
                .andExpect(status().isOk()).andReturn();

        assertTrue(result.getResponse().getContentAsString().contains("not cancelled"));
        assertEquals("AUTH_FAILED", paymentRepo.findByPaypalOrderId(orderId).orElseThrow().getStatus());
        verify(payPalClient, never()).voidAuthorization(anyString());
    }

    @Test
    void cancellationVoidsAndVerifiesAnExistingAuthorization() throws Exception {
        String orderId = "ORDER-CANCEL-AUTH-001";
        String authId = "AUTH-CANCEL-AUTH-001";
        stubOrder(orderId);
        initiate("Grocery Store", 30.00, 12, "");
        PaymentRecord record = paymentRepo.findByPaypalOrderId(orderId).orElseThrow();
        record.setStatus("AUTH_FAILED");
        record.setAuthorizationId(authId);
        paymentRepo.save(record);

        when(payPalClient.getOrderDetails(orderId)).thenReturn(
                new PayPalClient.OrderDetails("APPROVED", authId, "CREATED"),
                new PayPalClient.OrderDetails("VOIDED", authId, "VOIDED"));
        when(payPalClient.voidAuthorization(authId)).thenReturn("VOIDED");

        MvcResult result = mockMvc.perform(get("/paypal/cancel").param("token", orderId))
                .andExpect(status().isOk()).andReturn();

        assertTrue(result.getResponse().getContentAsString().contains("cancelled"));
        assertEquals("CANCELLED", paymentRepo.findByPaypalOrderId(orderId).orElseThrow().getStatus());
        verify(payPalClient, times(1)).voidAuthorization(authId);
        verify(payPalClient, times(2)).getOrderDetails(orderId);
    }

    @Test
    void cancellationIsNotReportedUntilVoidIsConfirmed() throws Exception {
        String orderId = "ORDER-CANCEL-NO-CONFIRM-001";
        String authId = "AUTH-CANCEL-NO-CONFIRM-001";
        stubOrder(orderId);
        initiate("Grocery Store", 30.00, 12, "");
        PaymentRecord record = paymentRepo.findByPaypalOrderId(orderId).orElseThrow();
        record.setStatus("AUTH_FAILED");
        record.setAuthorizationId(authId);
        paymentRepo.save(record);
        when(payPalClient.getOrderDetails(orderId)).thenReturn(
                new PayPalClient.OrderDetails("APPROVED", authId, "CREATED"),
                new PayPalClient.OrderDetails("APPROVED", authId, "CREATED"));
        when(payPalClient.voidAuthorization(authId)).thenReturn("VOIDED");

        MvcResult result = mockMvc.perform(get("/paypal/cancel").param("token", orderId))
                .andExpect(status().isOk()).andReturn();

        assertTrue(result.getResponse().getContentAsString().contains("not cancelled"));
        assertEquals("AUTH_FAILED", paymentRepo.findByPaypalOrderId(orderId).orElseThrow().getStatus());
        verify(payPalClient, times(1)).voidAuthorization(authId);
    }

    @Test
    void trustedContactApprovalCapturesOnceAndConsumesToken() throws Exception {
        String orderId = "ORDER-CONTACT-APPROVE-001";
        String authId = "AUTH-CONTACT-APPROVE-001";
        PaymentRecord record = createHeldPayment(orderId, authId);
        String token = record.getTrustedToken();
        when(payPalClient.captureAuthorization(authId)).thenReturn("COMPLETED");

        mockMvc.perform(post("/api/trusted-contact/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(trustedRequest(token))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/trusted-contact/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(trustedRequest(token))))
                .andExpect(status().isNotFound());

        PaymentRecord after = paymentRepo.findById(record.getId()).orElseThrow();
        assertEquals("COMPLETED", after.getStatus());
        assertNull(after.getTrustedToken());
        verify(payPalClient, times(1)).captureAuthorization(authId);
    }

    @Test
    void trustedContactDoesNotCaptureAuthorizationAlreadyCapturedAtPayPal() throws Exception {
        String orderId = "ORDER-CONTACT-ALREADY-CAPTURED-001";
        String authId = "AUTH-CONTACT-ALREADY-CAPTURED-001";
        PaymentRecord record = createHeldPayment(orderId, authId);
        String token = record.getTrustedToken();
        doReturn(new PayPalClient.OrderDetails("COMPLETED", authId, "CAPTURED", "COMPLETED"))
                .when(payPalClient).getOrderDetails(orderId);

        mockMvc.perform(post("/api/trusted-contact/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(trustedRequest(token))))
                .andExpect(status().isConflict());

        PaymentRecord after = paymentRepo.findById(record.getId()).orElseThrow();
        assertEquals("COMPLETED", after.getStatus());
        assertNull(after.getTrustedToken());
        verify(payPalClient, never()).captureAuthorization(anyString());
    }

    @Test
    void trustedContactBlockVoidsAndCannotLaterCapture() throws Exception {
        String orderId = "ORDER-CONTACT-BLOCK-001";
        String authId = "AUTH-CONTACT-BLOCK-001";
        PaymentRecord record = createHeldPayment(orderId, authId);
        String token = record.getTrustedToken();
        doReturn(
                new PayPalClient.OrderDetails("APPROVED", authId, "CREATED"),
                new PayPalClient.OrderDetails("VOIDED", authId, "VOIDED"))
                .when(payPalClient).getOrderDetails(orderId);
        when(payPalClient.voidAuthorization(authId)).thenReturn("VOIDED");

        mockMvc.perform(post("/api/trusted-contact/block")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(trustedRequest(token))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/trusted-contact/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(trustedRequest(token))))
                .andExpect(status().isNotFound());

        PaymentRecord after = paymentRepo.findById(record.getId()).orElseThrow();
        assertEquals("BLOCKED", after.getStatus());
        assertNull(after.getTrustedToken());
        verify(payPalClient, never()).captureAuthorization(anyString());
        verify(payPalClient, times(1)).voidAuthorization(authId);
    }

    @Test
    void expiredTrustedContactTokenCannotAct() throws Exception {
        String orderId = "ORDER-CONTACT-EXPIRED-001";
        String authId = "AUTH-CONTACT-EXPIRED-001";
        PaymentRecord record = createHeldPayment(orderId, authId);
        String token = record.getTrustedToken();
        record.setTrustedTokenExpiresAt(java.time.LocalDateTime.now().minusMinutes(1));
        paymentRepo.save(record);

        mockMvc.perform(post("/api/trusted-contact/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(trustedRequest(token))))
                .andExpect(status().isGone());

        PaymentRecord after = paymentRepo.findById(record.getId()).orElseThrow();
        assertEquals("TOKEN_EXPIRED", after.getStatus());
        assertNull(after.getTrustedToken());
        verify(payPalClient, never()).captureAuthorization(anyString());
        verify(payPalClient, never()).voidAuthorization(anyString());
    }

    @Test
    void trustedContactDoesNothingWhenPayPalStateCannotBeVerified() throws Exception {
        String orderId = "ORDER-CONTACT-UNKNOWN-001";
        String authId = "AUTH-CONTACT-UNKNOWN-001";
        PaymentRecord record = createHeldPayment(orderId, authId);
        String token = record.getTrustedToken();
        when(payPalClient.getOrderDetails(orderId))
                .thenThrow(new PayPalClient.PayPalClientException(502, "safe error"));

        mockMvc.perform(post("/api/trusted-contact/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(trustedRequest(token))))
                .andExpect(status().isServiceUnavailable());

        PaymentRecord after = paymentRepo.findById(record.getId()).orElseThrow();
        assertEquals("HELD", after.getStatus());
        assertEquals(token, after.getTrustedToken());
        verify(payPalClient, never()).captureAuthorization(anyString());
        verify(payPalClient, never()).voidAuthorization(anyString());
    }

    @Test
    void uncertainTrustedCaptureConsumesTokenAndCannotBeRetried() throws Exception {
        String orderId = "ORDER-CONTACT-CAPTURE-UNCERTAIN-001";
        String authId = "AUTH-CONTACT-CAPTURE-UNCERTAIN-001";
        PaymentRecord record = createHeldPayment(orderId, authId);
        String token = record.getTrustedToken();
        doReturn(
                new PayPalClient.OrderDetails("APPROVED", authId, "CREATED"),
                new PayPalClient.OrderDetails("APPROVED", authId, "CREATED"))
                .when(payPalClient).getOrderDetails(orderId);
        when(payPalClient.captureAuthorization(authId))
                .thenThrow(new PayPalClient.PayPalClientException(502, "safe error"));

        mockMvc.perform(post("/api/trusted-contact/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(trustedRequest(token))))
                .andExpect(status().isBadGateway());
        mockMvc.perform(post("/api/trusted-contact/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(trustedRequest(token))))
                .andExpect(status().isNotFound());

        PaymentRecord after = paymentRepo.findById(record.getId()).orElseThrow();
        assertEquals("CONTACT_ACTION_UNCERTAIN", after.getStatus());
        assertNull(after.getTrustedToken());
        verify(payPalClient, times(1)).captureAuthorization(authId);
    }

    @Test
    void nonActiveAuthorizationIsNeverCaptured() throws Exception {
        String orderId = "ORDER-NONACTIVE-AUTH-001";
        String authId = "AUTH-NONACTIVE-001";
        stubOrder(orderId);
        when(payPalClient.authorizeOrder(orderId))
                .thenReturn(new PayPalClient.AuthorizationResult(authId, "CREATED"));
        when(payPalClient.getOrderDetails(orderId))
                .thenReturn(new PayPalClient.OrderDetails("APPROVED", authId, "DENIED"));
        when(payPalClient.captureAuthorization(authId)).thenReturn("COMPLETED");

        initiate("Grocery Store", 30.00, 12, "");
        mockMvc.perform(get("/paypal/return").param("token", orderId)).andExpect(status().isBadGateway());

        assertEquals("PAYPAL_ERROR", paymentRepo.findByPaypalOrderId(orderId).orElseThrow().getStatus());
        verify(payPalClient, never()).captureAuthorization(anyString());
    }

    @Test
    void holdIsNotRecordedWithoutVerifiedActiveAuthorization() throws Exception {
        String orderId = "ORDER-HOLD-UNVERIFIED-001";
        String authId = "AUTH-HOLD-UNVERIFIED-001";
        stubOrder(orderId);
        when(payPalClient.authorizeOrder(orderId))
                .thenReturn(new PayPalClient.AuthorizationResult(authId, "CREATED"));
        when(payPalClient.getOrderDetails(orderId))
                .thenReturn(new PayPalClient.OrderDetails("APPROVED", authId, "DENIED"));

        JsonNode response = initiate("New Hold Payee " + orderId, 30.00, 12, "");
        assertEquals("HOLD", response.get("decision").asText());
        mockMvc.perform(get("/paypal/return").param("token", orderId))
                .andExpect(status().isBadGateway());

        PaymentRecord record = paymentRepo.findByPaypalOrderId(orderId).orElseThrow();
        assertEquals("PAYPAL_ERROR", record.getStatus());
        assertNull(record.getTrustedToken());
        verify(payPalClient, never()).captureAuthorization(anyString());
    }

    private TrustedContactActionRequest trustedRequest(String token) {
        TrustedContactActionRequest request = new TrustedContactActionRequest();
        request.setToken(token);
        return request;
    }
}
