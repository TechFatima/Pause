package com.pause.backend;

import java.util.List;

/**
 * Response body for POST /api/payments/initiate.
 * approveUrl is null for BLOCK decisions (no PayPal order is created).
 */
public class InitiatePaymentResponse {

    private final Long       paymentRecordId;
    private final Decision   decision;
    private final String     message;
    private final List<String> reasons;
    private final String     approveUrl;  // null when decision is BLOCK

    public InitiatePaymentResponse(
            Long         paymentRecordId,
            Decision     decision,
            String       message,
            List<String> reasons,
            String       approveUrl
    ) {
        this.paymentRecordId = paymentRecordId;
        this.decision        = decision;
        this.message         = message;
        this.reasons         = reasons;
        this.approveUrl      = approveUrl;
    }

    public Long         getPaymentRecordId() { return paymentRecordId; }
    public Decision     getDecision()        { return decision; }
    public String       getMessage()         { return message; }
    public List<String> getReasons()         { return reasons; }
    public String       getApproveUrl()      { return approveUrl; }
}
