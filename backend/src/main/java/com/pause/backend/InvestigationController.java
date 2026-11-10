package com.pause.backend;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * InvestigationController exposes a TEMPORARY endpoint to test the
 * investigation tools from the browser. It will be replaced by the
 * full payment flow in a later step.
 */
@RestController
@RequestMapping("/api/tools")
public class InvestigationController {

    private final InvestigationService investigationService;

    public InvestigationController(InvestigationService investigationService) {
        this.investigationService = investigationService;
    }

    /**
     * GET /api/tools/check?message=...&payee=...&amount=10.00
     *
     * Runs all investigation tools and returns the findings as JSON.
     * message and amount are optional (default to empty string and 0).
     */
    @GetMapping("/check")
    public ResponseEntity<?> check(
            @RequestParam(defaultValue = "")    String message,
            @RequestParam(defaultValue = "")    String payee,
            @RequestParam(defaultValue = "0.00") String amount
    ) {
        try {
            BigDecimal amountDecimal = new BigDecimal(amount);
            InvestigationContext ctx = new InvestigationContext(message, payee, amountDecimal);
            InvestigationResult result = investigationService.runAll(ctx);
            return ResponseEntity.ok(result);
        } catch (NumberFormatException ex) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error",  "Invalid amount",
                    "detail", "'" + amount + "' is not a valid number."
            ));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error",  "Unexpected error",
                    "detail", ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage()
            ));
        }
    }
}
