package com.pause.backend;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Payment history fields safe to show to the signed-in owner. */
public record PaymentActivity(
        Long id,
        String payeeName,
        BigDecimal amount,
        Decision decision,
        Integer riskScore,
        boolean aiUsed,
        String status,
        LocalDateTime createdAt,
        String summary
) {
    public static PaymentActivity from(PaymentRecord record) {
        return new PaymentActivity(record.getId(), record.getPayeeName(), record.getAmount(),
                record.getDecision(), record.getRiskScore(), record.isAiUsed(), record.getStatus(),
                record.getCreatedAt(), record.getSummary());
    }
}
