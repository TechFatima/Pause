package com.pause.backend;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * PaymentRecord stores one payment event for a user.
 * The history of these records is what lets Pause learn what is "normal"
 * for a user and flag anything that looks out of place.
 */
@Entity
public class PaymentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // The user this payment belongs to.
    private Long userId;

    // The name of the payee as entered at the time of payment.
    // Stored as a plain string so the history survives even if a Payee is removed.
    private String payeeName;

    // Payment amount in USD.
    private BigDecimal amount;

    // When the payment was made.
    private LocalDateTime createdAt;

    // Current state: COMPLETED, HELD, VOIDED, BLOCKED, etc.
    private String status;

    // PayPal order id — set when a PayPal order is created. Nullable.
    private String paypalOrderId;

    // PayPal authorization id — set when the order is authorized. Nullable.
    private String authorizationId;

    // No-args constructor required by JPA and JSON serialization.
    public PaymentRecord() {}

    // -----------------------------------------------------------------------
    // Getters and setters
    // -----------------------------------------------------------------------

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getPayeeName() { return payeeName; }
    public void setPayeeName(String payeeName) { this.payeeName = payeeName; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getPaypalOrderId() { return paypalOrderId; }
    public void setPaypalOrderId(String paypalOrderId) { this.paypalOrderId = paypalOrderId; }

    public String getAuthorizationId() { return authorizationId; }
    public void setAuthorizationId(String authorizationId) { this.authorizationId = authorizationId; }
}
