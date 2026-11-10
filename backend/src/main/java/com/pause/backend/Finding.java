package com.pause.backend;

/**
 * One flagged item produced by an InvestigationTool.
 */
public class Finding {

    private final String code;    // machine-readable, e.g. "LOOKALIKE_DOMAIN"
    private final int    points;  // risk contribution
    private final String reason;  // plain-English explanation shown to the user

    public Finding(String code, int points, String reason) {
        this.code   = code;
        this.points = points;
        this.reason = reason;
    }

    public String getCode()   { return code; }
    public int    getPoints() { return points; }
    public String getReason() { return reason; }
}
