package com.pause.backend;

import java.util.List;

/**
 * All findings produced by one InvestigationTool, plus a short summary.
 */
public class ToolFinding {

    private final String        toolName;  // e.g. "LinkCheckTool"
    private final List<Finding> findings;  // may be empty (no issues found)
    private final String        summary;   // e.g. "2 suspicious links found"

    public ToolFinding(String toolName, List<Finding> findings, String summary) {
        this.toolName = toolName;
        this.findings = findings;
        this.summary  = summary;
    }

    public String        getToolName() { return toolName; }
    public List<Finding> getFindings() { return findings; }
    public String        getSummary()  { return summary; }
}
