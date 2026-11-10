package com.pause.backend;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * PaymentController handles the real payment initiation endpoint.
 * It replaces the temporary /api/checkpoint flow with a PayPal-connected flow.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentFlowService paymentFlowService;

    public PaymentController(PaymentFlowService paymentFlowService) {
        this.paymentFlowService = paymentFlowService;
    }

    /**
     * POST /api/payments/initiate
     * Body: same JSON as /api/checkpoint — { "payee", "amount", "message", "hour" }
     *
     * Returns decision, message, reasons, paymentRecordId, and approveUrl (null for BLOCK).
     */
    @PostMapping("/initiate")
    public ResponseEntity<?> initiate(@Valid @RequestBody CheckpointRequest request) {
        try {
            InitiatePaymentResponse response = paymentFlowService.initiate(request);
            return ResponseEntity.ok(response);
        } catch (PaymentFlowService.PaymentFlowException ex) {
            // PayPal returned an error — surface it as a 502 Bad Gateway.
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "error",      "PayPal request failed",
                    "httpStatus", ex.httpStatus,
                    "detail",     ex.getMessage()
            ));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error",  "Unexpected error",
                    "detail", "The request could not be completed. Please try again."
            ));
        }
    }

    /** Returns HTTP 400 with a clean JSON error for @Valid failures. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(Map.of("error", errors));
    }
}
