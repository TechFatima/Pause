package com.pause.backend;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PayPalReturnController handles the browser redirects PayPal sends
 * after the buyer approves or cancels on the PayPal approval page.
 *
 * These replace the temporary handlers that were in PayPalTestController.
 * Responses are plain text — the buyer sees them directly in the browser.
 */
@RestController
public class PayPalReturnController {

    private final PaymentFlowService paymentFlowService;
    private final PaymentRecordRepository paymentRepo;

    public PayPalReturnController(
            PaymentFlowService paymentFlowService,
            PaymentRecordRepository paymentRepo
    ) {
        this.paymentFlowService = paymentFlowService;
        this.paymentRepo        = paymentRepo;
    }

    /**
     * GET /paypal/return?token=ORDER_ID
     * PayPal redirects the buyer here after they approve the payment.
     * "token" is the PayPal query parameter name for the order id.
     */
    @GetMapping("/paypal/return")
    public ResponseEntity<String> paypalReturn(
            @RequestParam(required = false) String token
    ) {
        PaymentFlowService.ReturnOutcome outcome = paymentFlowService.handleReturn(token);

        return switch (outcome) {
            case COMPLETED -> ResponseEntity.ok(
                    "Payment completed successfully. " + recordIdMsg(token));

            case HELD -> ResponseEntity.ok(
                    "Payment held for trusted-contact review. " + recordIdMsg(token));

            case AUTH_FAILED -> ResponseEntity.ok(
                    "PayPal could not confirm the payment state. No further payment action was taken. "
                            + "Please try again later or contact support. "
                            + recordIdMsg(token));

            case CANCELLED -> ResponseEntity.ok(
                    "This payment has been cancelled. " + recordIdMsg(token));

            case ALREADY_PROCESSED -> ResponseEntity.ok(
                    "This payment has already been processed. " + recordIdMsg(token));

            case PAYPAL_ERROR -> ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
                    "A PayPal error occurred while processing this payment. "
                            + "Please contact support with your record details. "
                            + recordIdMsg(token));

            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    "Unknown order. Nothing was changed.");
        };
    }

    /**
     * GET /paypal/cancel?token=ORDER_ID
     * PayPal redirects the buyer here if they cancel on the approval page.
     */
    @GetMapping("/paypal/cancel")
    public ResponseEntity<String> paypalCancel(
            @RequestParam(required = false) String token
    ) {
        PaymentFlowService.ReturnOutcome outcome = paymentFlowService.handleCancel(token);

        return switch (outcome) {
            case CANCELLED -> ResponseEntity.ok("Payment cancelled. You may close this page.");
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Unknown order. No payment was cancelled.");
            case ALREADY_PROCESSED -> ResponseEntity.ok(
                    "This payment was already processed and was not cancelled.");
            default -> ResponseEntity.ok(
                    "This payment was not cancelled. Its current state was not changed.");
        };
    }

    /**
     * Looks up the paymentRecordId for an order and formats it into a readable string.
     * Used in user-facing messages — never exposes the PayPal token itself.
     */
    private String recordIdMsg(String paypalOrderId) {
        if (paypalOrderId == null) return "";
        return paymentRepo.findByPaypalOrderId(paypalOrderId)
                .map(r -> "(Record ID: " + r.getId() + ")")
                .orElse("");
    }
}
