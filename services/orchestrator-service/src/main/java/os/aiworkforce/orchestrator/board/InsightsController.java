package os.aiworkforce.orchestrator.board;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * What the workforce did, what it cost and what it was worth, for a manager.
 *
 * <p>Two views over the same window. The workspace's figures need {@code analytics:read}, the
 * permission that opens the Analytics page. The per-agent rows need only {@code run:read}: they
 * say what each agent did, which anyone who can read its runs can already count, and the agent
 * list and the Command Map read them without opening Analytics. Only the hourly staff cost is held
 * back from a reader who lacks {@code analytics:read}.
 *
 * <p>Every figure follows the rules in {@link InsightsService}: rates count finished work only,
 * a small sample has no rate, unpriced work is never shown as free, and anything derived from an
 * administrator's inputs says so.
 */
@RestController
@RequestMapping("/api/orchestrator/insights")
@Tag(name = "Insights")
public class InsightsController {

    private final InsightsService insights;

    public InsightsController(InsightsService insights) {
        this.insights = insights;
    }

    @GetMapping
    @RequiresPermission(Permission.Codes.ANALYTICS_READ)
    @Operation(summary = "Work done, success, spend, approvals, questions and estimated value over 7, 30 or 90 days")
    public InsightsService.Insights insights(@RequestParam(required = false) String window) {
        return insights.insights(orgId(), window);
    }

    @GetMapping("/agents")
    @RequiresPermission(Permission.Codes.RUN_READ)
    @Operation(summary = "Each agent's runs, success rate, cost, rejected approvals, satisfaction and hours returned")
    public InsightsService.AgentInsights agents(@RequestParam(required = false) String window) {
        InsightsService.AgentInsights result = insights.agents(orgId(), window);
        // The hourly staff cost is an administrator's input about their payroll. Whoever opens
        // Analytics sees it beside the estimates it produces; someone who can only read runs does
        // not, and the hours each agent returned do not give it away.
        if (RequestContext.requireActor().hasPermission(Permission.Codes.ANALYTICS_READ)) {
            return result;
        }
        return new InsightsService.AgentInsights(
                result.window(), result.from(), result.to(), result.basis(), result.valueLabel(), null, result.agents());
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
