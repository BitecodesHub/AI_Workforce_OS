// @find: analytics, GET /api/analytics, activity last 30 days, dashboard numbers, daily activity, usage counts, Analytics page, activity overview
// @what: Serves the workspace activity summary for the last 30 days, computed from audit entries.
// @flow: Reads AuditEvents; shown on the Analytics page
package os.aiworkforce.analytics.web;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.analytics.repository.AuditEvents;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * A workspace's activity, over the last 30 days.
 *
 * <p>{@code daily_activity} is the table this was meant to read from, but nothing populates it
 * yet - no service writes a rollup row - so a dashboard built on it would show zeros forever,
 * indistinguishable from a workspace with no activity. This reads {@code audit_events} directly
 * instead: every action already lands there (see {@link InternalAuditController}), so the counts
 * here are real, computed from actual rows, even before the rollup job exists. A workspace with
 * no audit entries in the window gets zeros and empty lists, not invented figures.
 */
@RestController
@RequestMapping("/api/analytics")
@Tag(name = "Analytics")
public class AnalyticsController {

    private static final Duration WINDOW = Duration.ofDays(30);

    private final AuditEvents events;

    public AnalyticsController(AuditEvents events) {
        this.events = events;
    }

    public record ActionCountView(String action, long count) {}

    public record OutcomeCountView(String outcome, long count) {}

    public record AnalyticsSummary(
            Instant windowStart,
            Instant windowEnd,
            long totalEvents,
            List<ActionCountView> byAction,
            List<OutcomeCountView> byOutcome) {}

    // @find: GET /api/analytics, summary, endpoint, analytics
    @GetMapping
    @RequiresPermission(Permission.Codes.ANALYTICS_READ)
    @Operation(summary = "Activity for this workspace over the last 30 days, derived from the audit log")
    public AnalyticsSummary summary() {
        UUID orgId = orgId();
        Instant end = Instant.now();
        Instant start = end.minus(WINDOW);

        long total = events.countByOrgIdAndOccurredAtAfter(orgId, start);
        List<ActionCountView> byAction = events.countByActionSince(orgId, start).stream()
                .map(row -> new ActionCountView(row.getAction(), row.getCount()))
                .toList();
        List<OutcomeCountView> byOutcome = events.countByOutcomeSince(orgId, start).stream()
                .map(row -> new OutcomeCountView(row.getOutcome(), row.getCount()))
                .toList();

        return new AnalyticsSummary(start, end, total, byAction, byOutcome);
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
