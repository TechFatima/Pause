package com.pause.backend;

import java.util.List;

/** A validated analyst response, or an unavailable result for safe fallback. */
public class MikasaAnalysis {

    private final boolean available;
    private final List<String> tactics;
    private final String explanation;
    private final List<String> questions;
    private final Decision suggestedDecision;

    public MikasaAnalysis(
            boolean available,
            List<String> tactics,
            String explanation,
            List<String> questions,
            Decision suggestedDecision
    ) {
        this.available = available;
        this.tactics = tactics;
        this.explanation = explanation;
        this.questions = questions;
        this.suggestedDecision = suggestedDecision;
    }

    public boolean isAvailable() { return available; }
    public List<String> getTactics() { return tactics; }
    public String getExplanation() { return explanation; }
    public List<String> getQuestions() { return questions; }
    public Decision getSuggestedDecision() { return suggestedDecision; }
}
