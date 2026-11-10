package com.pause.backend;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * PayPalTestController provides TEMPORARY endpoints used to manually walk
 * through the full PayPal authorize/capture/void flow in the browser.
 * These will be replaced by the real payment flow in a later step.
 */
@RestController
public class PayPalTestController {

    private final PayPalClient payPalClient;

    // Constructor injection: Spring provides the PayPalClient it already created.
    public PayPalTestController(PayPalClient payPalClient) {
        this.payPalClient = payPalClient;
    }

    // -----------------------------------------------------------------------
    // Original test endpoint (kept until new flow is verified)
    // -----------------------------------------------------------------------

    /**
     * GET /paypal/test
     * Original smoke-test: creates a $10.00 order and returns id + status.
     * NOTE: OrderResult now has three fields; approveUrl is included too.
     */
    @GetMapping("/paypal/test")
    public ResponseEntity<Map<String, Object>> test() {
        try {
            PayPalClient.OrderResult result = payPalClient.createAuthorizeOrder("10.00");
            return ResponseEntity.ok(Map.of(
                    "id",         result.id(),
                    "status",     result.status(),
                    "approveUrl", result.approveUrl() != null ? result.approveUrl() : "not found"
            ));
        } catch (PayPalClient.PayPalClientException ex) {
            return errorResponse(ex.httpStatus, ex.getMessage());
        } catch (Exception ex) {
            return unexpectedError(ex);
        }
    }

    // -----------------------------------------------------------------------
    // Step 1: Create an order
    // -----------------------------------------------------------------------

    /**
     * GET /paypal/orders/new?amount=10.00
     * Creates a PayPal AUTHORIZE order and returns the id, status, and
     * the approveUrl the buyer must open to approve the payment.
     *
     * Spring concept - @RequestParam: reads a query string parameter from the URL,
     * e.g. ?amount=10.00. The defaultValue means it's optional in the browser.
     */
    @GetMapping("/paypal/orders/new")
    public ResponseEntity<Map<String, Object>> newOrder(
            @RequestParam(defaultValue = "10.00") String amount
    ) {
        try {
            PayPalClient.OrderResult result = payPalClient.createAuthorizeOrder(amount);
            return ResponseEntity.ok(Map.of(
                    "id",         result.id(),
                    "status",     result.status(),
                    "approveUrl", result.approveUrl() != null ? result.approveUrl() : "not found"
            ));
        } catch (PayPalClient.PayPalClientException ex) {
            return errorResponse(ex.httpStatus, ex.getMessage());
        } catch (Exception ex) {
            return unexpectedError(ex);
        }
    }

    // -----------------------------------------------------------------------
    // Step 2: Buyer redirect landing pages
    // -----------------------------------------------------------------------

    /**
     * GET /paypal/return?token=ORDER_ID
     * PayPal redirects the buyer here after they approve.
     * "token" is PayPal's query parameter name for the order id.
     *
     * Spring concept - @RequestParam: same as above, reads ?token=... from the URL.
     */
    @GetMapping("/paypal/return")
    public String paypalReturn(@RequestParam(required = false) String token) {
        // Plain text response — no JSON needed for a landing page.
        return "Payment approved! Order ID: " + token
                + "\n\nNext step: open /paypal/orders/" + token + "/authorize";
    }

    /**
     * GET /paypal/cancel?token=ORDER_ID
     * PayPal redirects the buyer here if they cancel.
     */
    @GetMapping("/paypal/cancel")
    public String paypalCancel(@RequestParam(required = false) String token) {
        return "Payment cancelled. Order ID: " + token;
    }

    // -----------------------------------------------------------------------
    // Step 3: Authorize (place the hold)
    // -----------------------------------------------------------------------

    /**
     * GET /paypal/orders/{id}/authorize
     * Authorizes a buyer-approved order, placing a hold on the buyer's account.
     * Returns the authorization id and status (CREATED means the hold is active).
     *
     * Spring concept - @PathVariable: reads a segment from the URL path itself,
     * e.g. /paypal/orders/ABC123/authorize -> id = "ABC123".
     */
    @GetMapping("/paypal/orders/{id}/authorize")
    public ResponseEntity<Map<String, Object>> authorizeOrder(@PathVariable String id) {
        try {
            PayPalClient.AuthorizationResult result = payPalClient.authorizeOrder(id);
            return ResponseEntity.ok(Map.of(
                    "authorizationId", result.authorizationId(),
                    "status",          result.status()
            ));
        } catch (PayPalClient.PayPalClientException ex) {
            return errorResponse(ex.httpStatus, ex.getMessage());
        } catch (Exception ex) {
            return unexpectedError(ex);
        }
    }

    // -----------------------------------------------------------------------
    // Step 4a: Capture (charge the buyer)
    // -----------------------------------------------------------------------

    /**
     * GET /paypal/authorizations/{id}/capture
     * Captures (charges) the held authorization. Returns the resulting status.
     */
    @GetMapping("/paypal/authorizations/{id}/capture")
    public ResponseEntity<Map<String, Object>> captureAuth(@PathVariable String id) {
        try {
            String status = payPalClient.captureAuthorization(id);
            return ResponseEntity.ok(Map.of("status", status));
        } catch (PayPalClient.PayPalClientException ex) {
            return errorResponse(ex.httpStatus, ex.getMessage());
        } catch (Exception ex) {
            return unexpectedError(ex);
        }
    }

    // -----------------------------------------------------------------------
    // Step 4b: Void (release the hold without charging)
    // -----------------------------------------------------------------------

    /**
     * GET /paypal/authorizations/{id}/void
     * Voids (releases) the held authorization without charging the buyer.
     */
    @GetMapping("/paypal/authorizations/{id}/void")
    public ResponseEntity<Map<String, Object>> voidAuth(@PathVariable String id) {
        try {
            String status = payPalClient.voidAuthorization(id);
            return ResponseEntity.ok(Map.of("status", status));
        } catch (PayPalClient.PayPalClientException ex) {
            return errorResponse(ex.httpStatus, ex.getMessage());
        } catch (Exception ex) {
            return unexpectedError(ex);
        }
    }

    // -----------------------------------------------------------------------
    // Shared error helpers
    // -----------------------------------------------------------------------

    /** Builds a JSON error response with the given HTTP status and message. */
    private ResponseEntity<Map<String, Object>> errorResponse(int status, String detail) {
        return ResponseEntity
                .status(status)
                .body(Map.of(
                        "error",      "PayPal request failed",
                        "httpStatus", status,
                        "detail",     detail
                ));
    }

    /** Builds a 500 JSON response for unexpected (non-PayPal) errors. */
    private ResponseEntity<Map<String, Object>> unexpectedError(Exception ex) {
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                        "error",  "Unexpected error",
                        "detail", ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage()
                ));
    }
}
