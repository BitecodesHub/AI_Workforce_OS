// @find: usage report, usage by model, cost report, usage csv export, spend, tokens, /api/orchestrator/usage, Usage page, Download CSV
// @what: REST endpoints for the usage and cost report and its CSV export.
// @flow: Reads usage rows written by JpaUsageRecorder
package os.aiworkforce.orchestrator.web;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.Usage;
import os.aiworkforce.orchestrator.service.JpaBudgetGuard;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * What the workspace has spent on language models, summed from the usage table and grouped, and the
 * same data as a file for finance.
 *
 * <p>Every attempt is in that table, the failures and the skipped candidates as well as the
 * answers, so the report accounts for what failed calls cost - a provider bills for them - and
 * for what was never tried. Every amount is estimated US dollars from the model catalogue's
 * prices, and each response and each file says so: the figure is for working out where spend
 * goes, not a replacement for the provider's invoice.
 *
 * <p>Reading needs {@code budget:read}, the same permission as the budget itself.
 *
 * <p>The window is half open, {@code [from, to)}, so consecutive reports never count a boundary
 * row twice. A date is read as the start of that day in UTC; as an end it means the end of that
 * day, so {@code from=2026-10-01&to=2026-10-03} covers three whole days.
 */
@RestController
@RequestMapping("/api/orchestrator")
@Tag(name = "Usage")
public class UsageController {

    /** Longest window one report covers: a year, which is also a bound on the rows a day grouping returns. */
    static final int MAX_WINDOW_DAYS = 366;

    /** Rows fetched at a time when streaming the file, so a large window is never held in memory. */
    static final int EXPORT_PAGE = 1_000;

    private static final UUID NIL = new UUID(0L, 0L);

    private static final String NO_AGENT = "No agent (chat and knowledge)";
    private static final String REMOVED_AGENT = "Removed agent";

    /** The groupings, in the words a client uses. */
    public enum GroupBy {
        DAY,
        PROVIDER,
        MODEL,
        AGENT,
        OUTCOME;

        static GroupBy parse(String text) {
            if (text == null || text.isBlank()) {
                return DAY;
            }
            try {
                return valueOf(text.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw ApiException.validation("groupBy", "Group by day, provider, model, agent or outcome.");
            }
        }

        String word() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One row of the report, or its total.
     *
     * @param key what the row is for: a day, a provider, a model, an agent's id or an outcome; null
     *     for spend that belongs to no agent, and for the total
     * @param label the same in words a person reads
     * @param promptTokens tokens sent, cached ones included
     * @param cachedTokens the part of those the provider served from its cache
     * @param completionTokens tokens answered
     * @param totalTokens prompt and completion together
     * @param cost estimated USD from catalogue prices, for every attempt
     * @param failedAttemptCost the part of {@code cost} that went on attempts that failed
     * @param attempts calls actually made, failed ones included
     * @param failedAttempts how many of those failed
     * @param skipped candidates that were set aside without being called
     */
    public record Line(
            String key,
            String label,
            long promptTokens,
            long cachedTokens,
            long completionTokens,
            long totalTokens,
            BigDecimal cost,
            BigDecimal failedAttemptCost,
            long attempts,
            long failedAttempts,
            long skipped) {}

    /** What the figures are, in the report itself so a screenshot of it still says so. */
    public record UsageReport(
            Instant from, Instant to, String groupBy, String basis, Line totals, List<Line> groups) {}

    private final Usage usage;
    private final Agents agents;
    private final Clock clock;

    @Autowired
    public UsageController(Usage usage, Agents agents) {
        this(usage, agents, Clock.systemUTC());
    }

    UsageController(Usage usage, Agents agents, Clock clock) {
        this.usage = usage;
        this.agents = agents;
        this.clock = clock;
    }

    // @find: usage report, GET /api/orchestrator/usage
    @GetMapping("/usage")
    @RequiresPermission(Permission.Codes.BUDGET_READ)
    @Operation(summary = "Spend, tokens, failed-attempt cost and skipped candidates, grouped, for a window")
    public UsageReport report(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String groupBy) {
        Window window = window(from, to);
        GroupBy grouping = GroupBy.parse(groupBy);
        return report(orgId(), window, grouping);
    }

    @GetMapping("/usage.csv")
    @RequiresPermission(Permission.Codes.BUDGET_READ)
    @Operation(
            summary = "The same data as a CSV file: one row per attempt, or one per group when groupBy is given")
    // @find: usage CSV export, GET /api/orchestrator/usage.csv
    public void csv(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String groupBy,
            HttpServletResponse response)
            throws IOException {
        Window window = window(from, to);
        GroupBy grouping = groupBy == null || groupBy.isBlank() ? null : GroupBy.parse(groupBy);
        UUID orgId = orgId();
        // Only the attempt-by-attempt file names agents; a grouped one gets them from its report.
        Map<UUID, String> names = grouping == null ? agentNames(orgId) : Map.of();

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment()
                        .filename(fileName(window, grouping))
                        .build()
                        .toString());
        // Written to the response a page at a time, on this thread: the request's identity is
        // already checked, and a file handed to another thread would have none to check.
        OutputStream out = response.getOutputStream();
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        if (grouping == null) {
            writeAttempts(writer, orgId, window, names);
        } else {
            writeGroups(writer, report(orgId, window, grouping));
        }
        writer.flush();
    }

    // ---- The report ------------------------------------------------------------------------

    private UsageReport report(UUID orgId, Window window, GroupBy grouping) {
        List<Object[]> rows =
                switch (grouping) {
                    case DAY -> usage.reportByDay(orgId, window.from(), window.to());
                    case PROVIDER -> usage.reportByProvider(orgId, window.from(), window.to());
                    case MODEL -> usage.reportByModel(orgId, window.from(), window.to());
                    case AGENT -> usage.reportByAgent(orgId, window.from(), window.to());
                    case OUTCOME -> usage.reportByOutcome(orgId, window.from(), window.to());
                };
        Map<UUID, String> names = grouping == GroupBy.AGENT ? agentNames(orgId) : Map.of();

        List<Line> groups = rows.stream().map(row -> line(row, grouping, names)).toList();
        return new UsageReport(
                window.from(), window.to(), grouping.word(), BudgetController.BASIS, total(groups), groups);
    }

    /** One row from the repository: [key, prompt, cached, completion, cost, failedCost, attempts, failed, skipped]. */
    private static Line line(Object[] row, GroupBy grouping, Map<UUID, String> agentNames) {
        String key = row[0] == null ? null : row[0].toString();
        long prompt = whole(row[1]);
        long completion = whole(row[3]);
        return new Line(
                key,
                label(key, grouping, agentNames),
                prompt,
                whole(row[2]),
                completion,
                prompt + completion,
                money(row[4]),
                money(row[5]),
                whole(row[6]),
                whole(row[7]),
                whole(row[8]));
    }

    private static Line total(List<Line> groups) {
        long prompt = 0;
        long cached = 0;
        long completion = 0;
        long attempts = 0;
        long failed = 0;
        long skipped = 0;
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal failedCost = BigDecimal.ZERO;
        for (Line group : groups) {
            prompt += group.promptTokens();
            cached += group.cachedTokens();
            completion += group.completionTokens();
            attempts += group.attempts();
            failed += group.failedAttempts();
            skipped += group.skipped();
            cost = cost.add(group.cost());
            failedCost = failedCost.add(group.failedAttemptCost());
        }
        return new Line(
                null,
                "Total",
                prompt,
                cached,
                completion,
                prompt + completion,
                cost,
                failedCost,
                attempts,
                failed,
                skipped);
    }

    private static String label(String key, GroupBy grouping, Map<UUID, String> agentNames) {
        return switch (grouping) {
            case AGENT -> {
                if (key == null) {
                    yield NO_AGENT;
                }
                String name = agentNames.get(UUID.fromString(key));
                yield name == null ? REMOVED_AGENT : name;
            }
            case OUTCOME ->
                switch (key == null ? "" : key) {
                    case "SUCCEEDED" -> "Answered";
                    case "FAILED" -> "Failed";
                    case "SKIPPED" -> "Not tried";
                    default -> key == null ? "" : key;
                };
            default -> key == null ? "" : key;
        };
    }

    private Map<UUID, String> agentNames(UUID orgId) {
        Map<UUID, String> names = new HashMap<>();
        for (Agent agent : agents.findByOrgIdOrderByName(orgId)) {
            names.put(agent.getId(), agent.getName());
        }
        return names;
    }

    private static long whole(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static BigDecimal money(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
    }

    // ---- The window ------------------------------------------------------------------------

    private record Window(Instant from, Instant to) {}

    /** The window asked for; the month so far when none is given. */
    private Window window(String fromText, String toText) {
        Instant now = clock.instant();
        Instant from = bound("from", fromText, JpaBudgetGuard.monthStart(now), false);
        // By default to the end of today, so the rows written a moment ago are in.
        Instant to = bound("to", toText, now.truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS), true);
        if (!from.isBefore(to)) {
            throw ApiException.validation("to", "The end of the period must be after its start.");
        }
        if (from.plus(MAX_WINDOW_DAYS, ChronoUnit.DAYS).isBefore(to)) {
            throw ApiException.validation("to", "A report covers at most " + MAX_WINDOW_DAYS + " days.");
        }
        return new Window(from, to);
    }

    /** A date, or an instant with its offset; a date as an end bound covers that whole day. */
    private static Instant bound(String field, String text, Instant fallback, boolean isEnd) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        String value = text.strip();
        try {
            if (value.length() <= 10) {
                LocalDate date = LocalDate.parse(value);
                return (isEnd ? date.plusDays(1) : date).atStartOfDay(ZoneOffset.UTC).toInstant();
            }
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw ApiException.validation(field, "Use a date such as 2026-10-01.");
        }
    }

    // ---- The file --------------------------------------------------------------------------

    private static String fileName(Window window, GroupBy grouping) {
        LocalDate first = window.from().atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate last = window.to().minusMillis(1).atZone(ZoneOffset.UTC).toLocalDate();
        String kind = grouping == null ? "attempts" : "by-" + grouping.word();
        return "usage-" + kind + "_" + first + "_to_" + last + ".csv";
    }

    private static final String[] ATTEMPT_HEADER = {
        "occurred_at_utc",
        "agent_id",
        "agent",
        "run_id",
        "provider",
        "model",
        "outcome",
        "failure",
        "skip_reason",
        "prompt_tokens",
        "cached_tokens",
        "completion_tokens",
        "estimated_cost_usd (estimated USD from catalogue prices)",
        "duration_ms"
    };

    private static final String[] GROUP_HEADER = {
        "group",
        "label",
        "attempts",
        "failed_attempts",
        "skipped_candidates",
        "prompt_tokens",
        "cached_tokens",
        "completion_tokens",
        "total_tokens",
        "estimated_cost_usd (estimated USD from catalogue prices)",
        "estimated_failed_attempt_cost_usd (estimated USD from catalogue prices)"
    };

    /** Every attempt in the window, oldest first, a page at a time so the window is never held whole. */
    private void writeAttempts(Writer out, UUID orgId, Window window, Map<UUID, String> names) throws IOException {
        row(out, ATTEMPT_HEADER);
        Instant afterTime = window.from().minusNanos(1);
        UUID afterId = NIL;
        while (true) {
            List<Object[]> page = usage.exportPage(
                    orgId, window.from(), window.to(), afterTime, afterId, PageRequest.of(0, EXPORT_PAGE));
            for (Object[] attempt : page) {
                UUID agentId = (UUID) attempt[1];
                row(
                        out,
                        String.valueOf(attempt[0]),
                        agentId == null ? "" : agentId.toString(),
                        agentId == null ? "" : names.getOrDefault(agentId, REMOVED_AGENT),
                        attempt[2] == null ? "" : attempt[2].toString(),
                        String.valueOf(attempt[3]),
                        String.valueOf(attempt[4]),
                        String.valueOf(attempt[5]),
                        attempt[6] == null ? "" : attempt[6].toString(),
                        attempt[7] == null ? "" : attempt[7].toString(),
                        String.valueOf(whole(attempt[8])),
                        String.valueOf(whole(attempt[9])),
                        String.valueOf(whole(attempt[10])),
                        money(attempt[11]).toPlainString(),
                        String.valueOf(whole(attempt[12])));
            }
            out.flush();
            if (page.size() < EXPORT_PAGE) {
                return;
            }
            Object[] last = page.get(page.size() - 1);
            afterTime = (Instant) last[0];
            afterId = (UUID) last[13];
        }
    }

    private static void writeGroups(Writer out, UsageReport report) throws IOException {
        row(out, GROUP_HEADER);
        for (Line line : report.groups()) {
            groupRow(out, line.key() == null ? "" : line.key(), line);
        }
        groupRow(out, "total", report.totals());
    }

    private static void groupRow(Writer out, String group, Line line) throws IOException {
        row(
                out,
                group,
                line.label(),
                String.valueOf(line.attempts()),
                String.valueOf(line.failedAttempts()),
                String.valueOf(line.skipped()),
                String.valueOf(line.promptTokens()),
                String.valueOf(line.cachedTokens()),
                String.valueOf(line.completionTokens()),
                String.valueOf(line.totalTokens()),
                line.cost().toPlainString(),
                line.failedAttemptCost().toPlainString());
    }

    private static void row(Writer out, String... cells) throws IOException {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                out.write(',');
            }
            out.write(cell(cells[i]));
        }
        out.write("\r\n");
    }

    /**
     * One CSV cell, safe to open in a spreadsheet.
     *
     * <p>A cell that starts with {@code =}, {@code +}, {@code -} or {@code @} is read by a
     * spreadsheet as a formula, and an agent's name or a failure message is text somebody else
     * wrote. A leading apostrophe makes the application show it as text; a leading tab or carriage
     * return is neutralised the same way. The cell is then quoted if it holds a comma, a quote or
     * a line break, with quotes doubled.
     */
    static String cell(String value) {
        String text = value == null ? "" : value;
        if (!text.isEmpty()) {
            char first = text.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
                text = "'" + text;
            }
        }
        boolean quote = text.indexOf(',') >= 0
                || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0
                || text.indexOf('\r') >= 0;
        return quote ? '"' + text.replace("\"", "\"\"") + '"' : text;
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
