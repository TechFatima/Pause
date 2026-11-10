package com.pause.backend;

/**
 * Contract every investigation tool must implement.
 * Each tool receives the same InvestigationContext and returns its findings.
 *
 * Java concept - interface: defines what a class must do, not how.
 * Any class that implements this interface can be used wherever an
 * InvestigationTool is expected — including being auto-collected by Spring.
 */
public interface InvestigationTool {

    /** A short human-readable name for this tool, e.g. "LinkCheckTool". */
    String name();

    /** Run the tool against the context and return all findings. */
    ToolFinding run(InvestigationContext ctx);
}
