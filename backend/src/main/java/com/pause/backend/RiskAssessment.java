package com.pause.backend;

import java.util.List;

/**
 * RiskAssessment is the full result of evaluating one payment.
 * It is returned by RuleEngine.evaluate() and serialized to JSON
 * by the controller.
 */
public class RiskAssessment {

    // Total risk score: 0 (no risk) to 100 (maximum risk).
    private final int score;

    // All the rules that fired (each one added points to the score).
    private final List<RiskSignal> signals;

    // The final outcome: ALLOW, HOLD, or BLOCK.
    private final Decision decision;

    // A plain-English message personalized to this user's history,
    // e.g. "You normally spend $20.50–$48.00 per payment. This one is
    // $800.00 to a payee you've never paid."
    private final String message;

    public RiskAssessment(int score, List<RiskSignal> signals, Decision decision, String message) {
        this.score    = score;
        this.signals  = signals;
        this.decision = decision;
        this.message  = message;
    }

    // Getters — needed for JSON serialization.
    public int            getScore()    { return score; }
    public List<RiskSignal> getSignals() { return signals; }
    public Decision       getDecision() { return decision; }
    public String         getMessage()  { return message; }
}
