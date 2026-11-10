package com.pause.backend;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RuleEngine is the core of Pause. It evaluates one proposed payment against
 * the user's history and limits, and returns a RiskAssessment.
 *
 * Design: rules are private methods that each return Optional<RiskSignal>.
 * If the rule fires it returns a signal; if not, it returns Optional.empty().
 * This keeps each rule self-contained and easy to read.
 */
@Component
public class RuleEngine {

    // Point thresholds for each decision level.
    private static final int HOLD_THRESHOLD  = 40;
    private static final int BLOCK_THRESHOLD = 90;

    // An amount is "far above typical" if it is more than this multiple of median.
    private static final BigDecimal FAR_ABOVE_MULTIPLIER = new BigDecimal("3");

    // We require at least this many COMPLETED payments before applying the
    // typical-amount rule, to avoid false positives on a thin history.
    private static final int MIN_HISTORY_FOR_TYPICAL = 2;

    private final AppUserRepository         userRepo;
    private final PaymentRecordRepository   paymentRepo;

    // Constructor injection.
    public RuleEngine(AppUserRepository userRepo, PaymentRecordRepository paymentRepo) {
        this.userRepo    = userRepo;
        this.paymentRepo = paymentRepo;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Evaluates one proposed payment and returns a RiskAssessment.
     *
     * @param userId    the user making the payment (must exist in DB)
     * @param payeeName the name of the recipient
     * @param amount    the payment amount in USD
     * @param timestamp when the payment is being made (used for hour-of-day rule)
     * @return a full RiskAssessment with score, signals, decision, and message
     * @throws IllegalArgumentException if the user is not found
     */
    public RiskAssessment evaluate(
            Long userId,
            String payeeName,
            BigDecimal amount,
            LocalDateTime timestamp
    ) {
        // --- 1. Load the user ---
        AppUser user = userRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "User not found: " + userId));

        // --- 2. Load completed payment history ---
        List<PaymentRecord> history = paymentRepo
                .findByUserId(userId, org.springframework.data.domain.Sort.unsorted())
                .stream()
                .filter(r -> "COMPLETED".equals(r.getStatus()))
                .collect(Collectors.toList());

        // --- 3. Compute baseline profile from history ---
        BaselineProfile baseline = buildBaseline(history, timestamp);

        // --- 4. Run each rule; collect fired signals ---
        List<RiskSignal> signals = new ArrayList<>();

        checkOverMaxPerPayment(user, amount)       .ifPresent(signals::add);
        checkOverWeeklyCap(user, amount, baseline) .ifPresent(signals::add);
        checkNewPayee(user, payeeName, baseline)   .ifPresent(signals::add);
        checkFarAboveTypical(amount, baseline)     .ifPresent(signals::add);
        checkUnusualHour(timestamp)                .ifPresent(signals::add);

        // --- 5. Sum points (cap at 100) ---
        int rawScore = signals.stream().mapToInt(RiskSignal::getPoints).sum();
        int score    = Math.min(rawScore, 100);

        // --- 6. Score-based decision ---
        Decision decision;
        if (score >= BLOCK_THRESHOLD) {
            decision = Decision.BLOCK;
        } else if (score >= HOLD_THRESHOLD) {
            decision = Decision.HOLD;
        } else {
            decision = Decision.ALLOW;
        }

        // --- 7. Standing orders: can only RAISE the decision, never lower it ---
        // We compare enum ordinals: higher ordinal = more severe.
        // Using Collections.max-style logic on enum ordinals keeps this explicit.
        boolean overMax     = signals.stream().anyMatch(s -> "OVER_MAX_PER_PAYMENT".equals(s.getName()));
        boolean newPayee    = signals.stream().anyMatch(s -> "NEW_PAYEE".equals(s.getName()));

        if (overMax || newPayee) {
            // Standing order: at least HOLD.
            decision = raise(decision, Decision.HOLD);
        }

        // --- 8. Build personalized message ---
        String message = buildMessage(user, payeeName, amount, baseline, signals);

        return new RiskAssessment(score, signals, decision, message);
    }

    // -----------------------------------------------------------------------
    // Baseline builder
    // -----------------------------------------------------------------------

    /**
     * Computes a BaselineProfile from the user's COMPLETED payment history.
     * Safe when history is empty or very short.
     */
    private BaselineProfile buildBaseline(List<PaymentRecord> history, LocalDateTime now) {

        boolean hasEnough = history.size() >= MIN_HISTORY_FOR_TYPICAL;

        // Known payees: all distinct payee names in history.
        Set<String> knownPayees = new HashSet<>();
        for (PaymentRecord r : history) {
            knownPayees.add(r.getPayeeName());
        }

        // Weekly spend: sum of COMPLETED payments in the last 7 days.
        LocalDateTime weekAgo = now.minusDays(7);
        BigDecimal weeklySpentSoFar = history.stream()
                .filter(r -> r.getCreatedAt() != null && r.getCreatedAt().isAfter(weekAgo))
                .map(PaymentRecord::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Amount statistics: only meaningful when we have enough history.
        BigDecimal medianAmount = BigDecimal.ZERO;
        BigDecimal minAmount    = BigDecimal.ZERO;
        BigDecimal maxAmount    = BigDecimal.ZERO;

        if (hasEnough) {
            List<BigDecimal> sorted = history.stream()
                    .map(PaymentRecord::getAmount)
                    .sorted()
                    .collect(Collectors.toList());

            minAmount = sorted.get(0);
            maxAmount = sorted.get(sorted.size() - 1);

            // Median: middle value for odd count, average of two middle values for even count.
            int mid = sorted.size() / 2;
            if (sorted.size() % 2 == 1) {
                medianAmount = sorted.get(mid);
            } else {
                medianAmount = sorted.get(mid - 1)
                        .add(sorted.get(mid))
                        .divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
            }
        }

        return new BaselineProfile(
                medianAmount, minAmount, maxAmount,
                knownPayees, weeklySpentSoFar, hasEnough
        );
    }

    // -----------------------------------------------------------------------
    // Rules (each returns Optional<RiskSignal>)
    // -----------------------------------------------------------------------

    /** Rule 1: payment exceeds the user's per-payment limit. 60 points. */
    private Optional<RiskSignal> checkOverMaxPerPayment(AppUser user, BigDecimal amount) {
        if (amount.compareTo(user.getMaxPerPayment()) > 0) {
            return Optional.of(new RiskSignal(
                    "OVER_MAX_PER_PAYMENT",
                    60,
                    "This payment (" + fmt(amount) + ") exceeds your per-payment limit of "
                            + fmt(user.getMaxPerPayment()) + "."
            ));
        }
        return Optional.empty();
    }

    /** Rule 2: weekly total (including this payment) would exceed the weekly cap. 50 points. */
    private Optional<RiskSignal> checkOverWeeklyCap(
            AppUser user, BigDecimal amount, BaselineProfile baseline) {

        BigDecimal projectedWeekly = baseline.getWeeklySpentSoFar().add(amount);
        if (projectedWeekly.compareTo(user.getWeeklyCap()) > 0) {
            return Optional.of(new RiskSignal(
                    "OVER_WEEKLY_CAP",
                    50,
                    "Adding this payment (" + fmt(amount) + ") would bring your 7-day total to "
                            + fmt(projectedWeekly) + ", over your weekly cap of "
                            + fmt(user.getWeeklyCap()) + "."
            ));
        }
        return Optional.empty();
    }

    /** Rule 3: payee is new and user has newPayeesNeedApproval=true. 40 points. */
    private Optional<RiskSignal> checkNewPayee(
            AppUser user, String payeeName, BaselineProfile baseline) {

        if (user.isNewPayeesNeedApproval()
                && !baseline.getKnownPayees().contains(payeeName)) {
            return Optional.of(new RiskSignal(
                    "NEW_PAYEE",
                    40,
                    "You have never paid \"" + payeeName + "\" before, "
                            + "and your settings require approval for new payees."
            ));
        }
        return Optional.empty();
    }

    /**
     * Rule 4: amount is more than 3× the user's median payment. 35 points.
     * Skipped if history is too short to be meaningful.
     */
    private Optional<RiskSignal> checkFarAboveTypical(
            BigDecimal amount, BaselineProfile baseline) {

        if (!baseline.hasEnoughHistory()) return Optional.empty();

        BigDecimal threshold = baseline.getMedianAmount().multiply(FAR_ABOVE_MULTIPLIER);
        if (amount.compareTo(threshold) > 0) {
            return Optional.of(new RiskSignal(
                    "AMOUNT_FAR_ABOVE_TYPICAL",
                    35,
                    "This payment (" + fmt(amount) + ") is more than 3× your median payment of "
                            + fmt(baseline.getMedianAmount()) + "."
            ));
        }
        return Optional.empty();
    }

    /** Rule 5: payment is being made at an unusual hour (before 6 AM or after 10 PM). 20 points. */
    private Optional<RiskSignal> checkUnusualHour(LocalDateTime timestamp) {
        int hour = timestamp.getHour();
        if (hour < 6 || hour > 22) {
            return Optional.of(new RiskSignal(
                    "UNUSUAL_HOUR",
                    20,
                    "This payment is being made at " + hour + ":00, which is outside normal hours."
            ));
        }
        return Optional.empty();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Raises the decision to at least the given minimum.
     * Uses the enum ordinal so the logic is explicit and can never lower a decision.
     * e.g. raise(ALLOW, HOLD) -> HOLD  |  raise(BLOCK, HOLD) -> BLOCK
     */
    private Decision raise(Decision current, Decision minimum) {
        return current.ordinal() >= minimum.ordinal() ? current : minimum;
    }

    /**
     * Formats a BigDecimal as a dollar string with exactly two decimal places,
     * e.g. 800 -> "$800.00", 21.5 -> "$21.50".
     */
    private String fmt(BigDecimal value) {
        return "$" + value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Builds a personalized plain-English message for the user.
     */
    private String buildMessage(
            AppUser user,
            String payeeName,
            BigDecimal amount,
            BaselineProfile baseline,
            List<RiskSignal> signals
    ) {
        StringBuilder sb = new StringBuilder();

        // Opening: what is normal for this user.
        if (baseline.hasEnoughHistory()) {
            sb.append("You normally spend ")
              .append(fmt(baseline.getMinAmount()))
              .append("–")
              .append(fmt(baseline.getMaxAmount()))
              .append(" per payment (median ")
              .append(fmt(baseline.getMedianAmount()))
              .append("). ");
        }

        // This payment.
        sb.append("This payment is ").append(fmt(amount)).append(" to ");

        if (baseline.getKnownPayees().contains(payeeName)) {
            sb.append(payeeName).append(", a payee you have paid before.");
        } else {
            sb.append("\"").append(payeeName).append("\", a payee you have never paid before.");
        }

        // Append each signal reason on a new line.
        if (!signals.isEmpty()) {
            sb.append(" Reasons flagged:");
            for (RiskSignal signal : signals) {
                sb.append(" ").append(signal.getReason());
            }
        }

        return sb.toString();
    }
}
