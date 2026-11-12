package com.pause.backend;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * CheckpointService is the main orchestrator for Step 7.
 * It runs the rule engine and investigation tools, merges their results
 * into one decision, and saves a PaymentRecord.
 */
@Component
public class CheckpointService {

    private static final int HOLD_THRESHOLD  = 40;
    private static final int BLOCK_THRESHOLD = 90;

    // Demo user id — hardcoded for now; a real app would take it from the session.
    private static final Long DEMO_USER_ID = 1L;

    private final RuleEngine              ruleEngine;
    private final InvestigationService    investigationService;
    private final PaymentRecordRepository paymentRepo;
    private final MikasaAnalyst           mikasaAnalyst;

    public CheckpointService(
            RuleEngine              ruleEngine,
            InvestigationService    investigationService,
            PaymentRecordRepository paymentRepo,
            MikasaAnalyst           mikasaAnalyst
    ) {
        this.ruleEngine           = ruleEngine;
        this.investigationService = investigationService;
        this.paymentRepo          = paymentRepo;
        this.mikasaAnalyst        = mikasaAnalyst;
    }

    /**
     * Runs both engines, merges results, saves a record, and returns CheckpointResult.
     * Input validation is handled by @Valid on the controller; no null-checks needed here.
     */
    public CheckpointResult evaluate(CheckpointRequest req) {

        // --- 1. Build timestamp ---
        LocalDateTime timestamp = LocalDateTime.now();
        if (req.getHour() != null) {
            timestamp = timestamp.withHour(req.getHour()).withMinute(0).withSecond(0).withNano(0);
        }

        // --- 2. Run rule engine ---
        RiskAssessment ruleResult = ruleEngine.evaluate(
                DEMO_USER_ID,
                req.getPayee(),
                req.getAmount(),
                timestamp
        );

        // --- 3. Run investigation tools ---
        String safeMessage = req.getMessage() == null ? "" : req.getMessage();
        InvestigationContext ctx = new InvestigationContext(
                safeMessage, req.getPayee(), req.getAmount());
        InvestigationResult toolResult = investigationService.runAll(ctx);

        // --- 4. Combined score: sum of both engines, capped at 100 ---
        int combinedScore = Math.min(100,
                ruleResult.getScore() + toolResult.getTotalPoints());

        // --- 5. Score-based decision ---
        Decision scoreDecision;
        if (combinedScore >= BLOCK_THRESHOLD) {
            scoreDecision = Decision.BLOCK;
        } else if (combinedScore >= HOLD_THRESHOLD) {
            scoreDecision = Decision.HOLD;
        } else {
            scoreDecision = Decision.ALLOW;
        }

        // --- 6. Final decision = higher of rule engine's own decision and score decision ---
        // raise() keeps whichever has the higher enum ordinal — never lowers.
        Decision decision = raise(scoreDecision, ruleResult.getDecision());

        // --- 7. Standing orders (separate last step, can only raise) ---
        // SAFE_ACCOUNT or GIFT_CARD_OR_CRYPTO in any tool finding forces at least HOLD.
        if (hasCode(toolResult, "SAFE_ACCOUNT") || hasCode(toolResult, "GIFT_CARD_OR_CRYPTO")) {
            decision = raise(decision, Decision.HOLD);
        }

        // The analyst can raise this decision, but can never make it less strict.
        MikasaAnalysis analystResult = mikasaAnalyst.analyze(req, ruleResult, toolResult);
        if (analystResult.isAvailable()) {
            decision = raise(decision, analystResult.getSuggestedDecision());
        }

        // --- 8. Merge reasons from both sources ---
        List<String> reasons = new ArrayList<>();
        for (RiskSignal signal : ruleResult.getSignals()) {
            reasons.add(signal.getReason());
        }
        for (ToolFinding tf : toolResult.getToolFindings()) {
            for (Finding f : tf.getFindings()) {
                reasons.add(f.getReason());
            }
        }

        // --- 9. Build personalized message ---
        String message = buildMessage(req, ruleResult, toolResult, decision);

        // --- 10. Build summary string (message + reasons joined) for storage ---
        // Truncate to 2000 chars to respect the DB column limit.
        StringBuilder summaryBuilder = new StringBuilder(message);
        for (String r : reasons) {
            summaryBuilder.append(" | ").append(r);
        }
        String summary = summaryBuilder.length() > 2000
                ? summaryBuilder.substring(0, 2000)
                : summaryBuilder.toString();

        // --- 11. Save PaymentRecord with DECIDED_* status ---
        // Status prefix "DECIDED_" ensures these records are never counted as
        // COMPLETED history by RuleEngine's baseline filter.
        String status = "DECIDED_" + decision.name(); // e.g. "DECIDED_HOLD"

        PaymentRecord record = new PaymentRecord();
        record.setUserId(DEMO_USER_ID);
        record.setPayeeName(req.getPayee());
        record.setAmount(req.getAmount());
        record.setCreatedAt(LocalDateTime.now());
        record.setStatus(status);
        record.setSummary(summary);
        record = paymentRepo.save(record);

        CheckpointResult result = new CheckpointResult(
                record.getId(),
                combinedScore,
                decision,
                ruleResult.getSignals(),
                toolResult.getToolFindings(),
                reasons,
                message
        );
        result.setAnalystResult(analystResult);
        return result;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Raises current to at least minimum using enum ordinal order.
     * Never lowers: raise(BLOCK, HOLD) -> BLOCK.
     */
    private Decision raise(Decision current, Decision minimum) {
        return current.ordinal() >= minimum.ordinal() ? current : minimum;
    }

    /** Returns true if any Finding in the tool result has the given code. */
    private boolean hasCode(InvestigationResult toolResult, String code) {
        for (ToolFinding tf : toolResult.getToolFindings()) {
            for (Finding f : tf.getFindings()) {
                if (code.equals(f.getCode())) return true;
            }
        }
        return false;
    }

    /**
     * Builds a personalized plain-English message.
     * For ALLOW: calm, no warnings.
     * For HOLD/BLOCK: starts with the rule engine's personalized baseline message,
     * then appends a tactic sentence only if tool findings exist.
     */
    private String buildMessage(
            CheckpointRequest req,
            RiskAssessment ruleResult,
            InvestigationResult toolResult,
            Decision decision
    ) {
        if (decision == Decision.ALLOW) {
            // Calm message — no warning tone.
            return "This payment of " + fmt(req.getAmount()) + " to " + req.getPayee()
                    + " looks normal based on your history.";
        }

        // Start with the rule engine's already-personalized baseline message.
        StringBuilder sb = new StringBuilder(ruleResult.getMessage());

        // Append tactic names only if the tools found something.
        List<String> tacticNames = new ArrayList<>();
        for (ToolFinding tf : toolResult.getToolFindings()) {
            for (Finding f : tf.getFindings()) {
                tacticNames.add(f.getCode().toLowerCase().replace('_', ' '));
            }
        }
        if (!tacticNames.isEmpty()) {
            sb.append(" The message you pasted uses: ");
            sb.append(String.join(", ", tacticNames)).append(".");
        }

        return sb.toString();
    }

    /** Formats a BigDecimal as "$X.XX". */
    private String fmt(BigDecimal v) {
        return "$" + v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
