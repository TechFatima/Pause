package com.pause.backend;

import java.math.BigDecimal;

/**
 * Holds the inputs passed to every InvestigationTool.
 */
public class InvestigationContext {

    private final String     message;    // the text the user pasted (may be null/blank)
    private final String     payeeName;  // who the user is sending money to
    private final BigDecimal amount;     // how much

    public InvestigationContext(String message, String payeeName, BigDecimal amount) {
        // Treat null message as empty string so tools never have to null-check.
        this.message   = message == null ? "" : message;
        this.payeeName = payeeName;
        this.amount    = amount;
    }

    public String     getMessage()   { return message; }
    public String     getPayeeName() { return payeeName; }
    public BigDecimal getAmount()    { return amount; }
}
