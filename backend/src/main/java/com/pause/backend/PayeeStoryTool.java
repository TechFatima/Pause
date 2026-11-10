package com.pause.backend;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PayeeStoryTool compares who the message CLAIMS to be from against who the
 * money is actually going to.
 *
 * Scammers often impersonate a bank or large company, but instruct the victim
 * to pay a private individual or a generic "safe account". This mismatch is
 * a strong scam signal.
 *
 * The tool fires only when:
 *   1. The message contains an institution keyword (e.g. "your bank").
 *   2. The message also contains a payment-request word (pay, transfer, etc.).
 *   3. The payee name does NOT contain any of those institution words.
 */
@Component
public class PayeeStoryTool implements InvestigationTool {

    // Institution keywords — words that suggest the message is from a body of authority.
    private static final Set<String> INSTITUTION_WORDS = Set.of(
            "bank", "paypal", "tax", "hmrc", "irs",
            "tech support", "microsoft", "apple", "amazon", "fraud department"
    );

    // Payment-request words — the message must also ask for money to fire the rule.
    private static final Set<String> PAYMENT_REQUEST_WORDS = Set.of(
            "pay", "transfer", "send", "deposit"
    );

    @Override
    public String name() { return "PayeeStoryTool"; }

    @Override
    public ToolFinding run(InvestigationContext ctx) {
        List<Finding> findings = new ArrayList<>();

        String msg   = ctx.getMessage().toLowerCase();
        String payee = ctx.getPayeeName() == null ? "" : ctx.getPayeeName().toLowerCase();

        // Step 1: find which institution keyword (if any) the message mentions.
        // \\b..\\b: word-boundary anchors — "bank" won't match inside "bankrupt".
        String claimedInstitution = null;
        for (String word : INSTITUTION_WORDS) {
            if (Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(msg).find()) {
                claimedInstitution = word;
                break;
            }
        }

        // Step 2: only proceed if the message also requests a payment action.
        if (claimedInstitution != null && containsPaymentRequest(msg)) {

            // Step 3: check whether the payee name matches the claimed institution.
            // If the payee name does NOT contain the institution word, it's a mismatch.
            if (!payee.contains(claimedInstitution)) {
                findings.add(new Finding("PAYEE_STORY_MISMATCH", 40,
                        "Message claims to be from '" + claimedInstitution
                                + "' but the payee is '" + ctx.getPayeeName()
                                + "' — this is a common scam pattern."));
            }
        }

        String summary = findings.isEmpty()
                ? "Payee matches the story in the message."
                : "Payee does not match the claimed sender.";

        return new ToolFinding(name(), findings, summary);
    }

    /** Returns true if the message contains a payment word as a whole word (not inside "PayPal"). */
    private boolean containsPaymentRequest(String lowerMsg) {
        // \\b..\\b: word-boundary anchors — "pay" won't match inside "paypal" or "payment".
        for (String word : PAYMENT_REQUEST_WORDS) {
            if (Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(lowerMsg).find()) {
                return true;
            }
        }
        return false;
    }
}
