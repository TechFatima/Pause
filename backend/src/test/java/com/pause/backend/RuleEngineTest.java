package com.pause.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RuleEngineTest verifies the rule engine against three scenarios.
 *
 * Spring concept - @SpringBootTest: starts the full application context
 * (including the database and seeder) so we can test with real data.
 * This means Rose's history is already in the database when each test runs.
 *
 * Spring concept - @Autowired on a field: asks Spring to inject the
 * RuleEngine instance it already created. In tests we use @Autowired on
 * a field for brevity (constructor injection is preferred in production code,
 * but field injection is fine in test classes).
 */
@SpringBootTest
class RuleEngineTest {

    @Autowired
    private RuleEngine ruleEngine;

    // Use a fixed "normal business hours" timestamp for all tests.
    // hour=12 so the UNUSUAL_HOUR rule never fires in test 1 and 3.
    private static final LocalDateTime NOON = LocalDateTime.now().withHour(12).withMinute(0);
    private static final LocalDateTime TWO_AM = LocalDateTime.now().withHour(2).withMinute(0);

    /**
     * Test 1: a normal $30.00 payment to a known payee at noon.
     * Rose's history has 20 payments of $20–$50 to Grocery Store, Pharmacy,
     * and Electric Company. This is completely normal — should be ALLOW.
     */
    @Test
    void normalPaymentShouldBeAllowed() {
        RiskAssessment result = ruleEngine.evaluate(
                1L,
                "Grocery Store",
                new BigDecimal("30.00"),
                NOON
        );

        // Should be ALLOW with no signals.
        assertEquals(Decision.ALLOW, result.getDecision(),
                "A normal payment to a known payee at noon should be ALLOW");
        assertEquals(0, result.getSignals().size(),
                "A normal payment should trigger no risk signals");
    }

    /**
     * Test 2: a $800.00 payment to a brand-new payee at 2 AM.
     * This should fire at least 3 signals:
     *   OVER_MAX_PER_PAYMENT (800 > 200 limit),
     *   NEW_PAYEE ("Bank Safe Account" is unknown),
     *   UNUSUAL_HOUR (2 AM),
     * and the standing order on OVER_MAX_PER_PAYMENT / NEW_PAYEE means
     * decision must be at least HOLD (likely BLOCK given the score).
     */
    @Test
    void scamPaymentShouldBeHoldOrBlock() {
        RiskAssessment result = ruleEngine.evaluate(
                1L,
                "Bank Safe Account",
                new BigDecimal("800.00"),
                TWO_AM
        );

        // Decision must be HOLD or BLOCK (not ALLOW).
        assertNotEquals(Decision.ALLOW, result.getDecision(),
                "An $800 payment to a new payee at 2 AM should not be ALLOW");

        // At least 3 signals must be present.
        List<RiskSignal> signals = result.getSignals();
        assertTrue(signals.size() >= 3,
                "Expected at least 3 signals, got: " + signalNames(signals));

        // Specific signals must be present.
        assertTrue(hasSignal(signals, "OVER_MAX_PER_PAYMENT"),
                "OVER_MAX_PER_PAYMENT should fire for $800 (limit $200)");
        assertTrue(hasSignal(signals, "NEW_PAYEE"),
                "NEW_PAYEE should fire for 'Bank Safe Account'");
        assertTrue(hasSignal(signals, "UNUSUAL_HOUR"),
                "UNUSUAL_HOUR should fire at 2 AM");
    }

    /**
     * Test 3: $250.00 to a known payee at noon.
     * $250 exceeds Rose's $200 per-payment limit.
     * The standing order means decision must be at least HOLD.
     * OVER_MAX_PER_PAYMENT must be in the signals.
     */
    @Test
    void overLimitToKnownPayeeShouldBeAtLeastHold() {
        RiskAssessment result = ruleEngine.evaluate(
                1L,
                "Grocery Store",
                new BigDecimal("250.00"),
                NOON
        );

        // Must be HOLD or BLOCK due to the standing order.
        assertNotEquals(Decision.ALLOW, result.getDecision(),
                "A payment over the per-payment limit should not be ALLOW");

        // The specific signal must be present.
        assertTrue(hasSignal(result.getSignals(), "OVER_MAX_PER_PAYMENT"),
                "OVER_MAX_PER_PAYMENT should fire for $250 (limit $200)");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Returns true if any signal in the list has the given name. */
    private boolean hasSignal(List<RiskSignal> signals, String name) {
        return signals.stream().anyMatch(s -> name.equals(s.getName()));
    }

    /** Returns a comma-separated list of signal names for assertion messages. */
    private String signalNames(List<RiskSignal> signals) {
        return signals.stream()
                .map(RiskSignal::getName)
                .reduce("", (a, b) -> a.isEmpty() ? b : a + ", " + b);
    }
}
