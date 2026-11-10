package com.pause.backend;

import java.util.List;

/**
 * The combined result of running all InvestigationTools on one context.
 */
public class InvestigationResult {

    private final List<ToolFinding> toolFindings; // one entry per tool
    private final int               totalPoints;  // sum of all Finding.points

    public InvestigationResult(List<ToolFinding> toolFindings, int totalPoints) {
        this.toolFindings = toolFindings;
        this.totalPoints  = totalPoints;
    }

    public List<ToolFinding> getToolFindings() { return toolFindings; }
    public int               getTotalPoints()  { return totalPoints; }
}
