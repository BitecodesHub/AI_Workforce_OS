package os.aiworkforce.analytics.web;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.analytics.domain.AuditEvent;
import os.aiworkforce.analytics.service.AuditAppender;
import os.aiworkforce.analytics.service.AuditChain;
import os.aiworkforce.analytics.service.AuditExport;
import os.aiworkforce.analytics.service.AuditFilter;
import os.aiworkforce.analytics.service.AuditSearch;
import os.aiworkforce.analytics.service.AuditVerification;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * The audit log, as a workspace sees it.
 *
 * <p>Every entry names the person accountable for it - {@code onBehalfOf} is populated whenever
 * an agent or a service acted for somebody - because a trail that stops at "the orchestrator did
 * it" cannot answer the only question ever asked of one.
 *
 * <p>Three things an auditor needs beyond reading: narrowing the log on the server (a browser
 * cannot filter a year of entries it has not loaded), taking it away as a file whose rows carry the
 * hashes needed to check it elsewhere, and asking the platform to re-walk the workspace's chain and
 * say whether anything was altered.
 */
@RestController
@RequestMapping("/api/audit")
@Tag(name = "Audit")
public class AuditController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_ACTIONS = 25;
    private static final List<String> OUTCOMES = List.of("succeeded", "failed", "denied", "locked");

    private final AuditSearch search;
    private final AuditVerification verification;
    private final AuditAppender appender;

    public AuditController(AuditSearch search, AuditVerification verification, AuditAppender appender) {
        this.search = search;
        this.verification = verification;
        this.appender = appender;
    }

    /**
     * @param previousHash the digest of the entry before this one in the workspace's chain; absent
     *     for the first entry
     * @param entryHash this entry's own digest, which the next entry carries as its previous hash
     */
    public record AuditEventView(
            UUID id,
            long sequence,
            String actorId,
            String actorKind,
            String onBehalfOf,
            String action,
            String resourceType,
            String resourceId,
            String outcome,
            Map<String, Object> detail,
            Instant occurredAt,
            String requestId,
            String previousHash,
            String entryHash) {}

    /**
     * @param verified false when any entry no longer matches
     * @param checked entries examined, up to and including the broken one
     * @param lastSequence the last entry examined
     * @param firstBrokenSequence the first entry that failed, present only when {@code verified} is false
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VerifyView(
            boolean verified, long checked, long lastSequence, Long firstBrokenSequence, String reason) {}

    @GetMapping
    @RequiresPermission(Permission.Codes.AUDIT_READ)
    @Operation(summary = "This workspace's audit entries, newest first, optionally filtered")
    public List<AuditEventView> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) Long before,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String onBehalfOf,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        if (page < 0) {
            throw ApiException.validation("page", "must be 0 or more");
        }
        if (size < 1) {
            throw ApiException.validation("size", "must be 1 or more");
        }
        int limit = Math.min(size, MAX_PAGE_SIZE);
        AuditFilter filter = filterOf(actorId, onBehalfOf, action, resourceType, resourceId, outcome, from, to);
        return search.find(orgId(), filter, before, before == null ? page * limit : 0, limit).stream()
                .map(AuditController::toView)
                .toList();
    }

    /**
     * The same entries as a file, newest first and narrowed by the same filters, written as they are
     * read from the database rather than collected first, so a long log does not sit in memory.
     */
    @GetMapping("/export")
    @RequiresPermission(Permission.Codes.AUDIT_READ)
    @Operation(summary = "Download the audit log as CSV or JSON Lines, newest first, optionally filtered")
    public void export(
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String onBehalfOf,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            HttpServletResponse response)
            throws IOException {
        boolean csv = "csv".equalsIgnoreCase(format);
        if (!csv && !"jsonl".equalsIgnoreCase(format)) {
            throw ApiException.validation("format", "must be csv or jsonl");
        }
        AuditFilter filter = filterOf(actorId, onBehalfOf, action, resourceType, resourceId, outcome, from, to);
        UUID orgId = orgId();
        Actor actor = RequestContext.requireActor();

        // Taking the log away is itself an event somebody may later ask about. Recorded before the
        // first byte is written: an export that cannot be recorded is not given.
        appender.append(new AuditAppender.Command(
                UUID.randomUUID(),
                orgId,
                actor.id(),
                actor.kind().name(),
                actor.onBehalfOf(),
                "audit.export",
                "audit_log",
                null,
                "succeeded",
                exportDetail(csv ? "csv" : "jsonl", filter),
                RequestContext.requestId(),
                null));

        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(csv ? "text/csv; charset=UTF-8" : "application/x-ndjson; charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader(
                "Content-Disposition",
                "attachment; filename=\"" + LocalDate.now(ZoneOffset.UTC) + "_Audit-log_v1." + (csv ? "csv" : "jsonl")
                        + "\"");

        PrintWriter out = response.getWriter();
        if (csv) {
            out.print(AuditExport.csvHeader());
        }
        Long before = null;
        int written = 0;
        while (written < AuditExport.MAX_ROWS) {
            int want = Math.min(AuditExport.PAGE_SIZE, AuditExport.MAX_ROWS - written);
            List<AuditEvent> rows = search.find(orgId, filter, before, 0, want);
            for (AuditEvent row : rows) {
                out.print(csv ? AuditExport.csvRow(row) : AuditExport.jsonLine(row));
            }
            out.flush();
            if (out.checkError()) {
                // The reader went away; there is nobody left to write for.
                return;
            }
            written += rows.size();
            if (rows.size() < want) {
                return;
            }
            before = rows.get(rows.size() - 1).getSequence();
        }
    }

    @GetMapping("/verify")
    @RequiresPermission(Permission.Codes.AUDIT_READ)
    @Operation(summary = "Re-walk this workspace's audit chain and report the first entry that does not match")
    public VerifyView verify() {
        AuditChain.Verification result = verification.verifyWorkspace(orgId());
        return new VerifyView(
                result.verified(),
                result.checked(),
                result.lastSequence(),
                result.firstBrokenSequence(),
                result.reason());
    }

    /** What the export was narrowed by, kept with the event so it says what was taken away. */
    private static Map<String, Object> exportDetail(String format, AuditFilter filter) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("format", format);
        Map<String, Object> filters = new LinkedHashMap<>();
        putIfPresent(filters, "actorId", filter.actorId());
        putIfPresent(filters, "onBehalfOf", filter.onBehalfOf());
        if (!filter.actions().isEmpty()) {
            filters.put("action", String.join(",", filter.actions()));
        }
        putIfPresent(filters, "resourceType", filter.resourceType());
        putIfPresent(filters, "resourceId", filter.resourceId());
        putIfPresent(filters, "outcome", filter.outcome());
        putIfPresent(filters, "from", filter.from() == null ? null : filter.from().toString());
        putIfPresent(filters, "to", filter.to() == null ? null : filter.to().toString());
        detail.put("filters", filters);
        return detail;
    }

    private static void putIfPresent(Map<String, Object> into, String key, String value) {
        if (value != null) {
            into.put(key, value);
        }
    }

    /** Parses and checks the filters shared by the list and the export. */
    static AuditFilter filterOf(
            String actorId,
            String onBehalfOf,
            String action,
            String resourceType,
            String resourceId,
            String outcome,
            String from,
            String to) {
        String checkedOutcome = blankToNull(outcome);
        if (checkedOutcome != null && !OUTCOMES.contains(checkedOutcome)) {
            throw ApiException.validation("outcome", "must be one of " + OUTCOMES);
        }
        List<String> actions = new ArrayList<>();
        if (action != null) {
            for (String code : action.split(",")) {
                if (!code.isBlank()) {
                    actions.add(code.strip());
                }
            }
        }
        if (actions.size() > MAX_ACTIONS) {
            throw ApiException.validation("action", "may name at most " + MAX_ACTIONS + " actions");
        }
        Instant start = instantOf("from", from, false);
        Instant end = instantOf("to", to, true);
        if (start != null && end != null && !start.isBefore(end)) {
            throw ApiException.validation("to", "must be after from");
        }
        return new AuditFilter(
                blankToNull(actorId),
                blankToNull(onBehalfOf),
                actions,
                blankToNull(resourceType),
                blankToNull(resourceId),
                checkedOutcome,
                start,
                end);
    }

    /**
     * A moment from a request: an ISO-8601 instant, or a plain date. A plain date means the whole
     * of that day in UTC, so a range of two dates includes both of them - which is what somebody who
     * typed "to 5 October" expects.
     */
    private static Instant instantOf(String name, String value, boolean endOfDay) {
        String text = blankToNull(value);
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException notAnInstant) {
            try {
                LocalDate day = LocalDate.parse(text);
                return (endOfDay ? day.plusDays(1) : day).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException notADate) {
                throw ApiException.validation(name, "must be a date (2026-10-05) or an ISO-8601 time");
            }
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static AuditEventView toView(AuditEvent event) {
        return new AuditEventView(
                event.getId(),
                event.getSequence(),
                event.getActorId(),
                event.getActorKind(),
                event.getOnBehalfOf(),
                event.getAction(),
                event.getResourceType(),
                event.getResourceId(),
                event.getOutcome(),
                event.getDetail(),
                event.getOccurredAt(),
                event.getRequestId(),
                event.getPreviousHash(),
                event.getEntryHash());
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
