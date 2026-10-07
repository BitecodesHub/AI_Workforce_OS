package os.aiworkforce.analytics.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import os.aiworkforce.analytics.domain.AuditEvent;

/**
 * The audit log as files an auditor can keep, and the rules for writing them.
 *
 * <p>Both formats carry every field the hash covers, in the exact form it was hashed - the time to
 * the microsecond, the detail as JSON - plus the hash itself and the one before it. That is what
 * lets a reviewer recompute the chain from the file alone, without trusting this service.
 *
 * <p>A cell of the CSV that begins with {@code =}, {@code +}, {@code -}, {@code @}, a tab or a
 * carriage return is read as a formula by a spreadsheet. Detail and names are written by people and
 * by agents, so an entry can hold {@code =HYPERLINK(...)}; opening the export would then run it.
 * Such a cell is written with a leading apostrophe, which a spreadsheet shows as plain text.
 */
public final class AuditExport {

    /** The most rows one export writes, so one request cannot stream the whole table for ever. */
    public static final int MAX_ROWS = 1_000_000;

    /** Rows read from the database at a time while streaming. */
    public static final int PAGE_SIZE = 500;

    static final List<String> COLUMNS = List.of(
            "sequence",
            "occurred_at",
            "actor_id",
            "actor_kind",
            "on_behalf_of",
            "action",
            "resource_type",
            "resource_id",
            "outcome",
            "request_id",
            "detail",
            "org_id",
            "hash_version",
            "previous_hash",
            "entry_hash");

    /** Not key-sorted: the columns keep their documented order, and the detail keeps what was stored. */
    private static final ObjectMapper JSON = new ObjectMapper();

    private AuditExport() {}

    public static String csvHeader() {
        return String.join(",", COLUMNS) + "\r\n";
    }

    public static String csvRow(AuditEvent event) {
        List<String> cells = List.of(
                Long.toString(event.getSequence()),
                AuditChain.microsText(event.getOccurredAt()),
                nullToEmpty(event.getActorId()),
                nullToEmpty(event.getActorKind()),
                nullToEmpty(event.getOnBehalfOf()),
                nullToEmpty(event.getAction()),
                nullToEmpty(event.getResourceType()),
                nullToEmpty(event.getResourceId()),
                nullToEmpty(event.getOutcome()),
                nullToEmpty(event.getRequestId()),
                CanonicalJson.write(event.getDetail()),
                event.getOrgId() == null ? "" : event.getOrgId().toString(),
                Short.toString(event.getHashVersion()),
                nullToEmpty(event.getPreviousHash()),
                nullToEmpty(event.getEntryHash()));
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                row.append(',');
            }
            row.append(csvCell(cells.get(i)));
        }
        return row.append("\r\n").toString();
    }

    /** One JSON object on one line, with the detail as an object rather than as text. */
    public static String jsonLine(AuditEvent event) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("sequence", event.getSequence());
        line.put("occurred_at", AuditChain.microsText(event.getOccurredAt()));
        line.put("actor_id", event.getActorId());
        line.put("actor_kind", event.getActorKind());
        line.put("on_behalf_of", event.getOnBehalfOf());
        line.put("action", event.getAction());
        line.put("resource_type", event.getResourceType());
        line.put("resource_id", event.getResourceId());
        line.put("outcome", event.getOutcome());
        line.put("request_id", event.getRequestId());
        line.put("detail", event.getDetail());
        line.put("org_id", event.getOrgId() == null ? null : event.getOrgId().toString());
        line.put("hash_version", event.getHashVersion());
        line.put("previous_hash", event.getPreviousHash());
        line.put("entry_hash", event.getEntryHash());
        try {
            return JSON.writeValueAsString(line) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("An audit entry could not be written as JSON", e);
        }
    }

    /** A cell, quoted when it needs to be and neutralised when a spreadsheet would run it. */
    static String csvCell(String value) {
        String text = neutralise(value);
        boolean quote = text.indexOf(',') >= 0
                || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0
                || text.indexOf('\r') >= 0;
        return quote ? '"' + text.replace("\"", "\"\"") + '"' : text;
    }

    /** Text a spreadsheet would read as a formula, with the apostrophe that makes it plain text. */
    static String neutralise(String value) {
        if (value.isEmpty()) {
            return value;
        }
        char first = value.charAt(0);
        boolean formula = first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r';
        return formula ? "'" + value : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
