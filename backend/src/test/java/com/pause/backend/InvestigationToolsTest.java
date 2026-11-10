package com.pause.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the investigation tool pipeline.
 * Uses the full Spring context so all three tools are wired automatically.
 */
@SpringBootTest
class InvestigationToolsTest {

    @Autowired
    private InvestigationService investigationService;

    // -----------------------------------------------------------------------
    // Test 1: a completely normal, innocent message.
    // -----------------------------------------------------------------------

    @Test
    void normalMessageShouldProduceNoFindings() {
        InvestigationContext ctx = new InvestigationContext(
                "Your pharmacy order is ready. Please collect it at the counter.",
                "Pharmacy",
                new BigDecimal("30.00")
        );

        InvestigationResult result = investigationService.runAll(ctx);

        // Collect all finding codes across all tools.
        List<String> codes = allCodes(result);

        assertEquals(0, codes.size(),
                "A normal message should produce no findings, but got: " + codes);
        assertEquals(0, result.getTotalPoints(),
                "A normal message should have 0 total points.");
    }

    // -----------------------------------------------------------------------
    // Test 2: a fake bank text with a lookalike link and multiple scam tactics.
    // -----------------------------------------------------------------------

    @Test
    void scamMessageShouldTriggerAtLeastSixDistinctCodes() {
        InvestigationContext ctx = new InvestigationContext(
                "URGENT: your bank account is locked, pay within 10 minutes "
                + "to the safe account at http://paypa1-secure.xyz/verify, "
                + "don't tell anyone.",
                "John K",
                new BigDecimal("800.00")
        );

        InvestigationResult result = investigationService.runAll(ctx);
        List<String> codes = allCodes(result);

        // Must have at least 6 distinct finding codes.
        long distinctCount = codes.stream().distinct().count();
        assertTrue(distinctCount >= 6,
                "Expected at least 6 distinct finding codes, got " + distinctCount + ": " + codes);

        // Specific codes that MUST be present.
        assertHasCode(codes, "LOOKALIKE_DOMAIN");
        assertHasCode(codes, "SUSPICIOUS_TLD");
        assertHasCode(codes, "NON_HTTPS");
        assertHasCode(codes, "URGENCY");
        assertHasCode(codes, "AUTHORITY");
        assertHasCode(codes, "SAFE_ACCOUNT");
        assertHasCode(codes, "SECRECY");
        assertHasCode(codes, "PAYEE_STORY_MISMATCH");
    }

    // -----------------------------------------------------------------------
    // Test 3: receipt message — mentions PayPal but is NOT a scam request.
    // The total points must be below 40 (not enough to trigger HOLD).
    // -----------------------------------------------------------------------

    @Test
    void receiptMessageShouldBeLowRisk() {
        InvestigationContext ctx = new InvestigationContext(
                "Your PayPal receipt for $30 is attached.",
                "Grocery Store",
                new BigDecimal("30.00")
        );

        InvestigationResult result = investigationService.runAll(ctx);

        // AUTHORITY may fire (message mentions "paypal"), but there is no
        // payment-request word, so PAYEE_STORY_MISMATCH must NOT fire.
        List<String> codes = allCodes(result);
        assertFalse(codes.contains("PAYEE_STORY_MISMATCH"),
                "A receipt message should not trigger PAYEE_STORY_MISMATCH.");

        // Total risk score must be below 40 — not enough to cause a HOLD.
        assertTrue(result.getTotalPoints() < 40,
                "Receipt message should be low-risk (< 40 points), got: "
                        + result.getTotalPoints() + " — codes: " + codes);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Collects all Finding codes from all tools in the result. */
    private List<String> allCodes(InvestigationResult result) {
        return result.getToolFindings().stream()
                .flatMap(tf -> tf.getFindings().stream())
                .map(Finding::getCode)
                .collect(Collectors.toList());
    }

    /** Asserts that the given code is present in the list, with a helpful message. */
    private void assertHasCode(List<String> codes, String code) {
        assertTrue(codes.contains(code),
                "Expected finding code '" + code + "' but got: " + codes);
    }
}
