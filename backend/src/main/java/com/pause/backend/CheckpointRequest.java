package com.pause.backend;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * The JSON body for POST /api/checkpoint.
 *
 * Spring concept - @Valid + Bean Validation annotations: Spring reads these
 * annotations and rejects the request with HTTP 400 automatically if any
 * constraint fails — no manual if-checks needed in the controller.
 */
public class CheckpointRequest {

    // Payee must be present and non-blank.
    @NotBlank(message = "payee must not be blank")
    private String payee;

    // Amount must be provided and positive (> 0).
    @NotNull(message = "amount must not be null")
    @DecimalMin(value = "0.01", message = "amount must be greater than 0")
    private BigDecimal amount;

    // Message is optional; when provided it must not exceed 2000 characters.
    @Size(max = 2000, message = "message must not exceed 2000 characters")
    private String message;

    // Hour is optional (0-23). Null means "use the current server hour".
    @Min(value = 0,  message = "hour must be between 0 and 23")
    @Max(value = 23, message = "hour must be between 0 and 23")
    private Integer hour;

    // No-args constructor required by Spring's JSON deserializer.
    public CheckpointRequest() {}

    public String     getPayee()   { return payee; }
    public void       setPayee(String payee) { this.payee = payee; }

    public BigDecimal getAmount()  { return amount; }
    public void       setAmount(BigDecimal amount) { this.amount = amount; }

    public String     getMessage() { return message; }
    public void       setMessage(String message) { this.message = message; }

    public Integer    getHour()    { return hour; }
    public void       setHour(Integer hour) { this.hour = hour; }
}
