package com.pause.backend;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * InvestigationService runs all registered InvestigationTools against
 * one InvestigationContext and returns the combined result.
 *
 * Spring concept - injecting a List of interface implementations:
 * Spring sees "List<InvestigationTool>" in the constructor and automatically
 * collects every @Component class that implements InvestigationTool into
 * that list. You never have to manually register LinkCheckTool,
 * MessageTacticsTool, or PayeeStoryTool — Spring finds them for you.
 */
@Component
public class InvestigationService {

    private final List<InvestigationTool> tools;

    // Spring injects all InvestigationTool implementations into this list.
    public InvestigationService(List<InvestigationTool> tools) {
        this.tools = tools;
    }

    /**
     * Runs every tool against the context and returns all findings
     * plus the total risk points.
     */
    public InvestigationResult runAll(InvestigationContext ctx) {
        List<ToolFinding> toolFindings = new ArrayList<>();
        int totalPoints = 0;

        for (InvestigationTool tool : tools) {
            ToolFinding finding = tool.run(ctx);
            toolFindings.add(finding);

            // Sum the points from every individual finding inside this tool's result.
            for (Finding f : finding.getFindings()) {
                totalPoints += f.getPoints();
            }
        }

        return new InvestigationResult(toolFindings, totalPoints);
    }
}
