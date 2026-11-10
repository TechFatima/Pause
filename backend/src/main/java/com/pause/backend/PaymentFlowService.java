package com.pause.backend;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Set;

/** Coordinates the checkpoint and the PayPal payment lifecycle. */
@Component
public class PaymentFlowService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Set<String> NOT_YET_APPROVED = Set.of("PAYER_ACTION_REQUIRED", "CREATED");
    private static final Set<String> AUTH_ACTIVE = Set.of("CREATED");
    private static final Set<String> AUTH_TERMINAL = Set.of("DENIED", "VOIDED", "EXPIRED");
    private static final Set<String> ORDER_NOT_CANCELLED =
            Set.of("PAYER_ACTION_REQUIRED", "CREATED", "APPROVED");

    private final CheckpointService checkpointService;
    private final PayPalClient payPalClient;
    private final PaymentRecordRepository paymentRepo;

    public PaymentFlowService(
            CheckpointService checkpointService,
            PayPalClient payPalClient,
            PaymentRecordRepository paymentRepo
    ) {
        this.checkpointService = checkpointService;
        this.payPalClient = payPalClient;
        this.paymentRepo = paymentRepo;
    }

    enum ReturnOutcome {
        COMPLETED, HELD, ALREADY_PROCESSED, CANCELLED, NOT_FOUND, PAYPAL_ERROR, AUTH_FAILED
    }

    enum TrustedContactOutcome {
        APPROVED, BLOCKED, NOT_FOUND, EXPIRED, ALREADY_PROCESSED, STATE_UNKNOWN, PAYPAL_ERROR
    }

    public InitiatePaymentResponse initiate(CheckpointRequest req) {
        CheckpointResult checkpoint = checkpointService.evaluate(req);
        Decision decision = checkpoint.getDecision();
        PaymentRecord record = paymentRepo.findById(checkpoint.getPaymentRecordId())
                .orElseThrow(() -> new IllegalStateException("Record not found after checkpoint save"));
        record.setDecision(decision);

        if (decision == Decision.BLOCK) {
            record.setStatus("BLOCKED");
            paymentRepo.save(record);
            return new InitiatePaymentResponse(
                    record.getId(), decision, checkpoint.getMessage(), checkpoint.getReasons(), null);
        }

        String amount = record.getAmount().setScale(2, RoundingMode.HALF_UP).toPlainString();
        try {
            PayPalClient.OrderResult order = payPalClient.createAuthorizeOrder(amount);
            record.setPaypalOrderId(order.id());
            paymentRepo.save(record);
            return new InitiatePaymentResponse(
                    record.getId(), decision, checkpoint.getMessage(), checkpoint.getReasons(), order.approveUrl());
        } catch (PayPalClient.PayPalClientException ex) {
            record.setStatus("PAYPAL_ERROR");
            paymentRepo.save(record);
            throw new PaymentFlowException(ex.httpStatus, ex.getMessage());
        }
    }

    /** Handles PayPal's return callback. The database lock makes duplicate callbacks a no-op. */
    @Transactional
    public ReturnOutcome handleReturn(String paypalOrderId) {
        if (paypalOrderId == null || paypalOrderId.isBlank()) return ReturnOutcome.NOT_FOUND;

        PaymentRecord record = paymentRepo.findByPaypalOrderIdForUpdate(paypalOrderId).orElse(null);
        if (record == null) return ReturnOutcome.NOT_FOUND;

        String status = record.getStatus();
        if (!"DECIDED_ALLOW".equals(status) && !"DECIDED_HOLD".equals(status)
                && !"AUTH_FAILED".equals(status)) {
            return ReturnOutcome.ALREADY_PROCESSED;
        }

        // An uncertain attempt must be checked before any further authorization request.
        if ("AUTH_FAILED".equals(status)) {
            PayPalClient.OrderDetails details = readOrderDetails(paypalOrderId);
            if (details == null) return ReturnOutcome.AUTH_FAILED;
            return resumeFromPayPalState(record, paypalOrderId, details, true);
        }

        return authorizeThenVerify(record, paypalOrderId);
    }

    /** Cancels only after PayPal confirms there is no live authorization, or confirms a void. */
    @Transactional
    public ReturnOutcome handleCancel(String paypalOrderId) {
        if (paypalOrderId == null || paypalOrderId.isBlank()) return ReturnOutcome.NOT_FOUND;

        PaymentRecord record = paymentRepo.findByPaypalOrderIdForUpdate(paypalOrderId).orElse(null);
        if (record == null) return ReturnOutcome.NOT_FOUND;

        String status = record.getStatus();
        if (!"DECIDED_ALLOW".equals(status) && !"DECIDED_HOLD".equals(status)
                && !"AUTH_FAILED".equals(status)) {
            return ReturnOutcome.ALREADY_PROCESSED;
        }

        PayPalClient.OrderDetails details = readOrderDetails(paypalOrderId);
        if (details == null) {
            record.setStatus("AUTH_FAILED");
            paymentRepo.save(record);
            return ReturnOutcome.AUTH_FAILED;
        }

        if (isVoided(details)) return markCancelled(record);
        if (isCaptured(details)) {
            record.setStatus(record.getDecision() == Decision.ALLOW ? "COMPLETED" : "PAYPAL_ERROR");
            paymentRepo.save(record);
            return ReturnOutcome.ALREADY_PROCESSED;
        }

        if (!details.authorizationStateKnown()) {
            record.setStatus("AUTH_FAILED");
            paymentRepo.save(record);
            return ReturnOutcome.AUTH_FAILED;
        }
        if (details.existingCaptureStatus() != null) {
            record.setStatus("AUTH_FAILED");
            paymentRepo.save(record);
            return ReturnOutcome.AUTH_FAILED;
        }

        String authId = details.existingAuthorizationId();
        String authStatus = details.existingAuthorizationStatus();
        if (authId != null && isActiveAuthorization(authStatus)) {
            try {
                payPalClient.voidAuthorization(authId);
            } catch (RuntimeException ignored) {
                // The follow-up read decides whether PayPal actually voided the authorization.
            }

            PayPalClient.OrderDetails afterVoid = readOrderDetails(paypalOrderId);
            if (afterVoid != null && isVoided(afterVoid)) return markCancelled(record);
            record.setStatus("AUTH_FAILED");
            paymentRepo.save(record);
            return ReturnOutcome.AUTH_FAILED;
        }

        if (authId == null && authStatus == null && details.orderStatus() != null
                && ORDER_NOT_CANCELLED.contains(details.orderStatus())) {
            return markCancelled(record);
        }

        // PayPal's state is not enough to say this payment was cancelled.
        record.setStatus("AUTH_FAILED");
        paymentRepo.save(record);
        return ReturnOutcome.AUTH_FAILED;
    }

    @Transactional
    public TrustedContactOutcome approveWithTrustedToken(String token) {
        return decideWithTrustedToken(token, true);
    }

    @Transactional
    public TrustedContactOutcome blockWithTrustedToken(String token) {
        return decideWithTrustedToken(token, false);
    }

    private ReturnOutcome authorizeThenVerify(PaymentRecord record, String orderId) {
        PayPalClient.AuthorizationResult authorization;
        try {
            authorization = payPalClient.authorizeOrder(orderId);
        } catch (RuntimeException ignored) {
            PayPalClient.OrderDetails details = readOrderDetails(orderId);
            if (details == null) return markAuthFailed(record);
            return resumeFromPayPalState(record, orderId, details, false);
        }

        if (authorization != null && authorization.authorizationId() != null) {
            record.setAuthorizationId(authorization.authorizationId());
        }
        PayPalClient.OrderDetails details = readOrderDetails(orderId);
        if (details == null) return markAuthFailed(record);

        boolean responseLooksActive = authorization != null
                && authorization.authorizationId() != null
                && isActiveAuthorization(authorization.status());
        if (responseLooksActive
                && details.authorizationStateKnown()
                && details.existingCaptureStatus() == null
                && authorization.authorizationId().equals(details.existingAuthorizationId())
                && isActiveAuthorization(details.existingAuthorizationStatus())) {
            return processVerifiedAuthorization(record, details.existingAuthorizationId());
        }

        // Do not act on a response/state mismatch. A later callback will re-check PayPal.
        return resumeFromPayPalState(record, orderId, details, false);
    }

    private ReturnOutcome resumeFromPayPalState(
            PaymentRecord record,
            String orderId,
            PayPalClient.OrderDetails details,
            boolean mayAuthorize
    ) {
        if (isVoided(details)) return markCancelled(record);

        if (isCaptured(details)) {
            if (record.getDecision() == Decision.ALLOW) {
                record.setStatus("COMPLETED");
                paymentRepo.save(record);
                return ReturnOutcome.COMPLETED;
            }
            record.setStatus("PAYPAL_ERROR");
            paymentRepo.save(record);
            return ReturnOutcome.PAYPAL_ERROR;
        }

        if (!details.authorizationStateKnown() || details.existingCaptureStatus() != null) {
            return markAuthFailed(record);
        }

        String existingAuthId = details.existingAuthorizationId();
        if (existingAuthId != null && isActiveAuthorization(details.existingAuthorizationStatus())) {
            record.setAuthorizationId(existingAuthId);
            return processVerifiedAuthorization(record, existingAuthId);
        }

        if (details.existingAuthorizationStatus() != null
                && AUTH_TERMINAL.contains(details.existingAuthorizationStatus())) {
            record.setStatus("PAYPAL_ERROR");
            paymentRepo.save(record);
            return ReturnOutcome.PAYPAL_ERROR;
        }

        String orderStatus = details.orderStatus();
        if (orderStatus != null && NOT_YET_APPROVED.contains(orderStatus)) {
            return markAuthFailed(record);
        }

        if (mayAuthorize && "APPROVED".equals(orderStatus) && existingAuthId == null) {
            return authorizeThenVerify(record, orderId);
        }

        if ("APPROVED".equals(orderStatus) && existingAuthId == null) {
            record.setStatus("PAYPAL_ERROR");
            paymentRepo.save(record);
            return ReturnOutcome.PAYPAL_ERROR;
        }

        return markAuthFailed(record);
    }

    private ReturnOutcome processVerifiedAuthorization(PaymentRecord record, String authId) {
        record.setAuthorizationId(authId);
        if (record.getDecision() == Decision.ALLOW) {
            try {
                String captureStatus = payPalClient.captureAuthorization(authId);
                if ("COMPLETED".equals(captureStatus)) {
                    record.setStatus("COMPLETED");
                    paymentRepo.save(record);
                    return ReturnOutcome.COMPLETED;
                }
            } catch (RuntimeException ignored) {
                // A failed/uncertain capture is terminal here to prevent duplicate captures.
            }
            record.setStatus("PAYPAL_ERROR");
            paymentRepo.save(record);
            return ReturnOutcome.PAYPAL_ERROR;
        }

        createTrustedToken(record);
        record.setStatus("HELD");
        paymentRepo.save(record);
        return ReturnOutcome.HELD;
    }

    private TrustedContactOutcome decideWithTrustedToken(String token, boolean approve) {
        if (token == null || token.isBlank()) return TrustedContactOutcome.NOT_FOUND;

        PaymentRecord record = paymentRepo.findByTrustedTokenForUpdate(token).orElse(null);
        if (record == null) return TrustedContactOutcome.NOT_FOUND;
        if (!"HELD".equals(record.getStatus())) return TrustedContactOutcome.ALREADY_PROCESSED;

        LocalDateTime expiresAt = record.getTrustedTokenExpiresAt();
        if (expiresAt == null || !expiresAt.isAfter(LocalDateTime.now())) {
            clearTrustedToken(record);
            record.setStatus("TOKEN_EXPIRED");
            paymentRepo.save(record);
            return TrustedContactOutcome.EXPIRED;
        }

        PayPalClient.OrderDetails details = readOrderDetails(record.getPaypalOrderId());
        if (details == null) return TrustedContactOutcome.STATE_UNKNOWN;

        if (isCaptured(details)) {
            clearTrustedToken(record);
            record.setStatus("COMPLETED");
            paymentRepo.save(record);
            return TrustedContactOutcome.ALREADY_PROCESSED;
        }
        if (isVoided(details)) {
            clearTrustedToken(record);
            record.setStatus("BLOCKED");
            paymentRepo.save(record);
            return TrustedContactOutcome.ALREADY_PROCESSED;
        }

        if (!details.authorizationStateKnown()) return TrustedContactOutcome.STATE_UNKNOWN;
        if (details.existingCaptureStatus() != null) return TrustedContactOutcome.STATE_UNKNOWN;

        String authId = details.existingAuthorizationId();
        if (authId == null || !authId.equals(record.getAuthorizationId())
                || !"CREATED".equals(details.existingAuthorizationStatus())) {
            return TrustedContactOutcome.STATE_UNKNOWN;
        }

        // Consume the bearer token before the external side effect. The row lock prevents
        // concurrent actions; PayPal is re-read on any retry after an uncertain network result.
        clearTrustedToken(record);
        record.setStatus("CONTACT_ACTION_PENDING");
        paymentRepo.save(record);

        if (approve) return captureForTrustedContact(record, authId);
        return voidForTrustedContact(record, authId);
    }

    private TrustedContactOutcome captureForTrustedContact(PaymentRecord record, String authId) {
        try {
            String status = payPalClient.captureAuthorization(authId);
            if ("COMPLETED".equals(status)) {
                record.setStatus("COMPLETED");
                paymentRepo.save(record);
                return TrustedContactOutcome.APPROVED;
            }
        } catch (RuntimeException ignored) {
            // Reconcile below before deciding whether PayPal captured the payment.
        }

        PayPalClient.OrderDetails afterCapture = readOrderDetails(record.getPaypalOrderId());
        if (afterCapture != null && isCaptured(afterCapture)) {
            record.setStatus("COMPLETED");
            paymentRepo.save(record);
            return TrustedContactOutcome.APPROVED;
        }
        record.setStatus("CONTACT_ACTION_UNCERTAIN");
        paymentRepo.save(record);
        return TrustedContactOutcome.PAYPAL_ERROR;
    }

    private TrustedContactOutcome voidForTrustedContact(PaymentRecord record, String authId) {
        try {
            payPalClient.voidAuthorization(authId);
        } catch (RuntimeException ignored) {
            // Reconcile below before deciding whether PayPal released the authorization.
        }

        PayPalClient.OrderDetails afterVoid = readOrderDetails(record.getPaypalOrderId());
        if (afterVoid != null && isVoided(afterVoid)) {
            record.setStatus("BLOCKED");
            paymentRepo.save(record);
            return TrustedContactOutcome.BLOCKED;
        }
        record.setStatus("CONTACT_ACTION_UNCERTAIN");
        paymentRepo.save(record);
        return TrustedContactOutcome.PAYPAL_ERROR;
    }

    private PayPalClient.OrderDetails readOrderDetails(String orderId) {
        try {
            return payPalClient.getOrderDetails(orderId);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean isCaptured(PayPalClient.OrderDetails details) {
        return "CAPTURED".equals(details.existingAuthorizationStatus())
                || "COMPLETED".equals(details.existingCaptureStatus());
    }

    private boolean isActiveAuthorization(String status) {
        return status != null && AUTH_ACTIVE.contains(status);
    }

    private boolean isVoided(PayPalClient.OrderDetails details) {
        return "VOIDED".equals(details.orderStatus())
                || "VOIDED".equals(details.existingAuthorizationStatus());
    }

    private ReturnOutcome markCancelled(PaymentRecord record) {
        record.setStatus("CANCELLED");
        paymentRepo.save(record);
        return ReturnOutcome.CANCELLED;
    }

    private ReturnOutcome markAuthFailed(PaymentRecord record) {
        record.setStatus("AUTH_FAILED");
        paymentRepo.save(record);
        return ReturnOutcome.AUTH_FAILED;
    }

    private void createTrustedToken(PaymentRecord record) {
        byte[] tokenBytes = new byte[32];
        SECURE_RANDOM.nextBytes(tokenBytes);
        record.setTrustedToken(Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes));
        record.setTrustedTokenExpiresAt(LocalDateTime.now().plusHours(24));
    }

    private void clearTrustedToken(PaymentRecord record) {
        record.setTrustedToken(null);
        record.setTrustedTokenExpiresAt(null);
    }

    static class PaymentFlowException extends RuntimeException {
        final int httpStatus;

        PaymentFlowException(int httpStatus, String message) {
            super(message);
            this.httpStatus = httpStatus;
        }
    }
}
