package com.pause.backend;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Accepts one-time approve/block actions from a trusted contact. */
@RestController
@RequestMapping("/api/trusted-contact")
public class TrustedContactController {

    private final PaymentFlowService paymentFlowService;

    public TrustedContactController(PaymentFlowService paymentFlowService) {
        this.paymentFlowService = paymentFlowService;
    }

    @PostMapping("/approve")
    public ResponseEntity<String> approve(@Valid @RequestBody TrustedContactActionRequest request) {
        return response(paymentFlowService.approveWithTrustedToken(request.getToken()), true);
    }

    @PostMapping("/block")
    public ResponseEntity<String> block(@Valid @RequestBody TrustedContactActionRequest request) {
        return response(paymentFlowService.blockWithTrustedToken(request.getToken()), false);
    }

    private ResponseEntity<String> response(
            PaymentFlowService.TrustedContactOutcome outcome,
            boolean approval
    ) {
        return switch (outcome) {
            case APPROVED -> ResponseEntity.ok("Payment approved and captured.");
            case BLOCKED -> ResponseEntity.ok("Payment blocked and authorization released.");
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("This trusted-contact link is invalid.");
            case EXPIRED -> ResponseEntity.status(HttpStatus.GONE)
                    .body("This trusted-contact link has expired.");
            case ALREADY_PROCESSED -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("This payment has already been decided.");
            case STATE_UNKNOWN -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("PayPal's payment state could not be confirmed. No action was taken.");
            case PAYPAL_ERROR -> ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(approval
                            ? "PayPal could not confirm the capture. The action cannot be repeated with this link."
                            : "PayPal could not confirm the authorization release. The action cannot be repeated with this link.");
        };
    }
}
