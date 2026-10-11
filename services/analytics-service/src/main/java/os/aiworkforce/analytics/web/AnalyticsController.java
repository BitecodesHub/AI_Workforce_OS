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

    /** Length of the reporting window, counted back from the time of the request. */
    private static final Duration WINDOW = Duration.ofDays(30);

    private final AuditEvents events;

    /** Creates the controller over the audit events repository, the only data source. */
    public AnalyticsController(AuditEvents events) {
        this.events = events;
    }

    /** One row of the by-action breakdown: an action code and how many entries carry it. */
    public record ActionCountView(String action, long count) {}

    /**
     * One row of the by-outcome breakdown: an outcome (succeeded, failed, denied or locked) and
     * its entry count.
     */
    public record OutcomeCountView(String outcome, long count) {}

    /**
     * Response of the summary endpoint: the window bounds, the total number of entries and the two
     * breakdowns; the by-action list is ordered by count, highest first.
     */
    public record AnalyticsSummary(
            Instant windowStart,
            Instant windowEnd,
            long totalEvents,
            List<ActionCountView> byAction,
            List<OutcomeCountView> byOutcome) {}

    // @find: GET /api/analytics, summary, endpoint, analytics
    /**
     * Route: GET /api/analytics. Permission: analytics:read. Counts the caller's workspace audit entries
     * over the last 30 days: the total, the count per action and the count per outcome. Read-only, computed
     * live from audit_events with three queries; a workspace with no entries gets zeros and empty lists.
     */
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

    /** Reads the workspace id from the authenticated request; fails when the request has none. */
    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
