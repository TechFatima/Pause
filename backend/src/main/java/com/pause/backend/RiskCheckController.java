package com.pause.backend;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * RiskCheckController exposes a TEMPORARY read-only endpoint to test the
 * rule engine from the browser. It will be replaced by the real payment
 * flow in a later step.
 */
@RestController
@RequestMapping("/api/risk")
public class RiskCheckController {

    private final RuleEngine ruleEngine;

    // Constructor injection: Spring provides the RuleEngine it already created.
    public RiskCheckController(RuleEngine ruleEngine) {
        this.ruleEngine = ruleEngine;
    }

    /**
     * GET /api/risk/check?payee=Grocery+Store&amount=30.00&hour=12
     *
     * Evaluates the proposed payment for the demo user (id=1) and returns
     * a full RiskAssessment as JSON.
     *
     * @param payee  the payee name (required)
     * @param amount the payment amount in USD (required)
     * @param hour   the hour of day 0–23 (optional; defaults to current server hour)
     */
    @GetMapping("/check")
    public ResponseEntity<?> check(
            @RequestParam String payee,
            @RequestParam String amount,
            @RequestParam(required = false) Integer hour
    ) {
        try {
            BigDecimal amountDecimal = new BigDecimal(amount);

            // Build a timestamp: today's date at the supplied hour (or now if omitted).
            LocalDateTime timestamp = LocalDateTime.now();
            if (hour != null) {
                // withHour() replaces just the hour; minutes/seconds don't matter for the rule.
                timestamp = timestamp.withHour(hour).withMinute(0).withSecond(0).withNano(0);
            }

            // Always evaluate against demo user id=1 for now.
            RiskAssessment assessment = ruleEngine.evaluate(1L, payee, amountDecimal, timestamp);
            return ResponseEntity.ok(assessment);

        } catch (NumberFormatException ex) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error",  "Invalid amount",
                    "detail", "'" + amount + "' is not a valid number."
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error",  "Not found",
                    "detail", ex.getMessage()
            ));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error",  "Unexpected error",
                    "detail", ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage()
            ));
        }
    }
}
