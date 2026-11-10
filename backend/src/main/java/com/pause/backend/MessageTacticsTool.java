package com.pause.backend;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * MessageTacticsTool scans the pasted message for social engineering tactics
 * commonly used in authorized push payment scams.
 *
 * Each category fires at most once (first matched phrase wins) to avoid
 * double-counting. All matching is case-insensitive.
 */
@Component
public class MessageTacticsTool implements InvestigationTool {

    @Override
    public String name() { return "MessageTacticsTool"; }

    @Override
    public ToolFinding run(InvestigationContext ctx) {
        List<Finding> findings = new ArrayList<>();
        // Normalize to lowercase once so every check below is case-insensitive.
        String msg = ctx.getMessage().toLowerCase();

        checkUrgency(msg, findings);
        checkFear(msg, findings);
        checkAuthority(msg, findings);
        checkSecrecy(msg, findings);
        checkSafeAccount(msg, findings);
        checkGiftCardOrCrypto(msg, findings);

        String summary = findings.isEmpty()
                ? "No social engineering tactics detected."
                : findings.size() + " tactic(s) detected: "
                    + findings.stream().map(Finding::getCode)
                              .reduce("", (a, b) -> a.isEmpty() ? b : a + ", " + b);

        return new ToolFinding(name(), findings, summary);
    }

    // -----------------------------------------------------------------------
    // Tactic checks — each fires at most once per message
    // -----------------------------------------------------------------------

    /** URGENCY — time-pressure phrases. 30 points. */
    private void checkUrgency(String msg, List<Finding> out) {
        String[] keywords = {
            "urgent", "immediately", "right now", "act now", "last chance",
            "within 10 minutes", "within 30 minutes", "within an hour", "within 24 hours"
        };
        String match = firstMatch(msg, keywords);
        if (match != null) {
            out.add(new Finding("URGENCY", 30,
                    "Message contains urgency language: '" + match + "'."));
        }
    }

    /** FEAR — threat of loss or legal consequences. 30 points. */
    private void checkFear(String msg, List<Finding> out) {
        String[] keywords = {
            "account locked", "account suspended", "account closed",
            "will be arrested", "legal action", "suspended", "closed your account"
        };
        String match = firstMatch(msg, keywords);
        if (match != null) {
            out.add(new Finding("FEAR", 30,
                    "Message uses fear tactics: '" + match + "'."));
        }
    }

    /** AUTHORITY — impersonating a trusted institution. 25 points. */
    private void checkAuthority(String msg, List<Finding> out) {
        String[] keywords = {
            "your bank", "from your bank", "paypal", "tax office", "hmrc", "irs",
            "tech support", "microsoft support", "apple support", "from amazon",
            "fraud department", "security team"
        };
        String match = firstMatch(msg, keywords);
        if (match != null) {
            out.add(new Finding("AUTHORITY", 25,
                    "Message claims to be from a trusted authority: '" + match + "'."));
        }
    }

    /** SECRECY — asking the victim to hide the transaction. 35 points. */
    private void checkSecrecy(String msg, List<Finding> out) {
        String[] keywords = {
            "don't tell anyone", "do not tell anyone", "keep this secret",
            "confidential", "don't share", "do not share", "tell no one",
            "keep it between us"
        };
        String match = firstMatch(msg, keywords);
        if (match != null) {
            out.add(new Finding("SECRECY", 35,
                    "Message asks for secrecy: '" + match + "'."));
        }
    }

    /** SAFE_ACCOUNT — classic scam instruction. 40 points. */
    private void checkSafeAccount(String msg, List<Finding> out) {
        String[] keywords = {
            "safe account", "holding account", "verification account",
            "secure account", "protective account"
        };
        String match = firstMatch(msg, keywords);
        if (match != null) {
            out.add(new Finding("SAFE_ACCOUNT", 40,
                    "Message mentions a '" + match + "' — a classic scam instruction."));
        }
    }

    /** GIFT_CARD_OR_CRYPTO — unusual payment method request. 35 points. */
    private void checkGiftCardOrCrypto(String msg, List<Finding> out) {
        String[] keywords = {
            "gift card", "itunes card", "google play card", "amazon card",
            "bitcoin", "crypto", "cryptocurrency", "wire transfer", "western union"
        };
        String match = firstMatch(msg, keywords);
        if (match != null) {
            out.add(new Finding("GIFT_CARD_OR_CRYPTO", 35,
                    "Message requests an unusual payment method: '" + match + "'."));
        }
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    /**
     * Returns the first keyword found in the (already-lowercased) message,
     * or null if none match.
     */
    private String firstMatch(String lowerMsg, String[] keywords) {
        for (String kw : keywords) {
            if (lowerMsg.contains(kw)) return kw;
        }
        return null;
    }
}
