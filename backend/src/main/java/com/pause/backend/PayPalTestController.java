package com.pause.backend;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * PayPalTestController is a TEMPORARY endpoint used only to verify that
 * the PayPal sandbox connection works. It will be removed in a later step.
 */
@RestController
public class PayPalTestController {

    // The PayPalClient instance is provided by Spring via constructor injection.
    // Spring concept - Constructor injection (recap): Spring sees that this
    // constructor needs a PayPalClient, finds the one it already created
    // (because of @Component), and passes it in automatically.
    private final PayPalClient payPalClient;

    public PayPalTestController(PayPalClient payPalClient) {
        this.payPalClient = payPalClient;
    }

    /**
     * GET /paypal/test
     * Creates a $10.00 AUTHORIZE order and returns its id and status as JSON.
     * On error, returns a JSON object with the HTTP status and PayPal's error message.
     * The secret, token, and Authorization header are never included in any response.
     */
    @GetMapping("/paypal/test")
    public ResponseEntity<Map<String, Object>> test() {
        try {
            PayPalClient.OrderResult result = payPalClient.createAuthorizeOrder("10.00");
            // ResponseEntity lets us control the HTTP status code we send back.
            // Map.of(...) builds a simple JSON object: {"id": "...", "status": "..."}
            return ResponseEntity.ok(Map.of(
                    "id",     result.id(),
                    "status", result.status()
            ));
        } catch (PayPalClient.PayPalClientException ex) {
            // Return the HTTP status PayPal gave us, plus a safe error message.
            return ResponseEntity
                    .status(ex.httpStatus)
                    .body(Map.of(
                            "error",      "PayPal request failed",
                            "httpStatus", ex.httpStatus,
                            "detail",     ex.getMessage()
                    ));
        } catch (Exception ex) {
            // Catch anything unexpected (network timeout, missing property, etc.)
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error",  "Unexpected error",
                            "detail", ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage()
                    ));
        }
    }
}
