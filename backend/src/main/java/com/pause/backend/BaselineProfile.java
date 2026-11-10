package com.pause.backend;

import java.math.BigDecimal;
import java.util.Set;

/**
 * BaselineProfile summarises what is "normal" for a user, computed from
 * their completed payment history. RuleEngine builds one of these before
 * running the rules, so every rule can reference the same computed values.
 */
public class BaselineProfile {

    // Median of all completed payment amounts.
    // Used to detect when a new payment is unusually large compared to normal.
    private final BigDecimal medianAmount;

    // Lowest and highest completed payment amounts — used in the message
    // ("You normally spend $X–$Y per payment").
    private final BigDecimal minAmount;
    private final BigDecimal maxAmount;

    // All distinct payee names the user has paid before (from COMPLETED history).
    // Used to detect payments to unknown payees.
    private final Set<String> knownPayees;

    // Total of COMPLETED payments in the last 7 days (not including this payment).
    // Used to check if adding this payment would exceed the weekly cap.
    private final BigDecimal weeklySpentSoFar;

    // True if there were at least 2 COMPLETED payments in history.
    // We need a minimum number of data points before the typical-amount rule
    // is meaningful; otherwise we skip it to avoid false positives.
    private final boolean hasEnoughHistory;

    public BaselineProfile(
            BigDecimal medianAmount,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            Set<String> knownPayees,
            BigDecimal weeklySpentSoFar,
            boolean hasEnoughHistory
    ) {
        this.medianAmount      = medianAmount;
        this.minAmount         = minAmount;
        this.maxAmount         = maxAmount;
        this.knownPayees       = knownPayees;
        this.weeklySpentSoFar  = weeklySpentSoFar;
        this.hasEnoughHistory  = hasEnoughHistory;
    }

    // Getters
    public BigDecimal  getMedianAmount()     { return medianAmount; }
    public BigDecimal  getMinAmount()        { return minAmount; }
    public BigDecimal  getMaxAmount()        { return maxAmount; }
    public Set<String> getKnownPayees()      { return knownPayees; }
    public BigDecimal  getWeeklySpentSoFar() { return weeklySpentSoFar; }
    public boolean     hasEnoughHistory()    { return hasEnoughHistory; }
}
