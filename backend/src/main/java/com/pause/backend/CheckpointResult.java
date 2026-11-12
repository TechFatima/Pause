package com.pause.backend;

import java.util.List;

/**
 * The full result returned by POST /api/checkpoint.
 */
public class CheckpointResult {

    private final Long              paymentRecordId; // saved PaymentRecord id
    private final int               combinedScore;   // 0-100
    private final Decision          decision;        // ALLOW, HOLD, or BLOCK
    private final List<RiskSignal>  ruleSignals;     // from RuleEngine
    private final List<ToolFinding> toolFindings;    // from InvestigationService
    private final List<String>      reasons;         // merged plain-language reasons
    private final String            message;         // personalized summary
    private List<String>            tactics = List.of();
    private String                  explanation;
    private List<String>            questions = List.of();
    private boolean                 aiUsed;

    public CheckpointResult(
            Long              paymentRecordId,
            int               combinedScore,
            Decision          decision,
            List<RiskSignal>  ruleSignals,
            List<ToolFinding> toolFindings,
            List<String>      reasons,
            String            message
    ) {
        this.paymentRecordId = paymentRecordId;
        this.combinedScore   = combinedScore;
        this.decision        = decision;
        this.ruleSignals     = ruleSignals;
        this.toolFindings    = toolFindings;
        this.reasons         = reasons;
        this.message         = message;
        this.explanation     = message;
    }

    public Long              getPaymentRecordId() { return paymentRecordId; }
    public int               getCombinedScore()   { return combinedScore; }
    public Decision          getDecision()        { return decision; }
    public List<RiskSignal>  getRuleSignals()     { return ruleSignals; }
    public List<ToolFinding> getToolFindings()    { return toolFindings; }
    public List<String>      getReasons()         { return reasons; }
    public String            getMessage()         { return message; }
    public List<String>      getTactics()         { return tactics; }
    public String            getExplanation()     { return explanation; }
    public List<String>      getQuestions()       { return questions; }
    public boolean           isAiUsed()           { return aiUsed; }

    void setAnalystResult(MikasaAnalysis analysis) {
        if (analysis.isAvailable()) {
            tactics = analysis.getTactics();
            explanation = analysis.getExplanation();
            questions = analysis.getQuestions();
            aiUsed = true;
        }
    }
}
