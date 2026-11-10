package com.pause.backend;

/**
 * RiskSignal represents one triggered rule in a risk assessment.
 * Each rule that fires produces one RiskSignal.
 */
public class RiskSignal {

    // Short machine-readable rule name, e.g. "OVER_MAX_PER_PAYMENT".
    private final String name;

    // How many risk points this signal contributes to the total score.
    private final int points;

    // Plain-English explanation shown to the user, e.g.
    // "This payment ($800.00) exceeds your per-payment limit of $200.00."
    private final String reason;

    public RiskSignal(String name, int points, String reason) {
        this.name   = name;
        this.points = points;
        this.reason = reason;
    }

    // Getters — needed so Spring can serialize this object to JSON.
    public String getName()   { return name; }
    public int    getPoints() { return points; }
    public String getReason() { return reason; }
}
