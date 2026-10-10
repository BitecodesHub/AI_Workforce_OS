// @find: tests for audit export, csv format, json lines, formula injection escaping, export fields
// @what: Checks the audit export formats and the spreadsheet formula protection.
package os.aiworkforce.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.analytics.domain.AuditEvent;

/** What leaves in an export: every cell safe to open in a spreadsheet, every line parseable. */
class AuditExportTest {

    private static AuditEvent event(String actorId, String resourceId, Map<String, Object> detail) {
        AuditEvent event = new AuditEvent();
        event.setOrgId(UUID.fromString("00000000-0000-7000-8000-0000000000a1"));
        event.setActorId(actorId);
        event.setActorKind("USER");
        event.setAction("member.role_change");
        event.setResourceType("member");
        event.setResourceId(resourceId);
        event.setOutcome("succeeded");
        event.setDetail(detail);
        event.setOccurredAt(Instant.parse("2026-10-06T01:02:03.123456789Z"));
        event.setPreviousHash("prev");
        event.setEntryHash("entry");
        return event;
    }

    @Test
    @DisplayName("a cell that a spreadsheet would run as a formula is written as plain text")
    void formulasAreNeutralised() {
        assertThat(AuditExport.csvCell("=HYPERLINK(\"http://evil\",\"x\")"))
                .isEqualTo("\"'=HYPERLINK(\"\"http://evil\"\",\"\"x\"\")\"");
        assertThat(AuditExport.csvCell("+1+1")).isEqualTo("'+1+1");
        assertThat(AuditExport.csvCell("-2+3")).isEqualTo("'-2+3");
        assertThat(AuditExport.csvCell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(AuditExport.csvCell("\t=1")).isEqualTo("'\t=1");
        assertThat(AuditExport.csvCell("\r=1")).isEqualTo("\"'\r=1\"");
    }

    @Test
    @DisplayName("a cell with a comma, a quote or a line break is quoted, and quotes are doubled")
    void quoting() {
        assertThat(AuditExport.csvCell("plain")).isEqualTo("plain");
        assertThat(AuditExport.csvCell("a,b")).isEqualTo("\"a,b\"");
        assertThat(AuditExport.csvCell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(AuditExport.csvCell("two\nlines")).isEqualTo("\"two\nlines\"");
        assertThat(AuditExport.csvCell("")).isEmpty();
    }

    @Test
    @DisplayName("a row carries every hashed field, the hashes, and the exact microsecond time")
    void row() {
        AuditEvent event = event("user-1", "user-2", Map.of("from", "viewer", "to", "owner"));
        event.setOnBehalfOf("boss");
        event.setRequestId("req-1");

        String row = AuditExport.csvRow(event);

        assertThat(AuditExport.csvHeader())
                .isEqualTo("sequence,occurred_at,actor_id,actor_kind,on_behalf_of,action,resource_type,resource_id,"
                        + "outcome,request_id,detail,org_id,hash_version,previous_hash,entry_hash\r\n");
        assertThat(row)
                .isEqualTo("0,2026-10-06T01:02:03.123456Z,user-1,USER,boss,member.role_change,member,user-2,"
                        + "succeeded,req-1,\"{\"\"from\"\":\"\"viewer\"\",\"\"to\"\":\"\"owner\"\"}\","
                        + "00000000-0000-7000-8000-0000000000a1,2,prev,entry\r\n");
    }

    @Test
    @DisplayName("text a person or an agent wrote cannot turn a row into a formula or into two rows")
    void hostileValues() {
        AuditEvent event = event("=cmd|' /C calc'!A0", "line one\r\nline two", Map.of("note", "=1+1"));

        String row = AuditExport.csvRow(event);

        assertThat(row).contains("'=cmd|' /C calc'!A0");
        assertThat(row).contains("\"line one\r\nline two\"");
        // The detail is JSON text, which begins with a brace; the formula inside it is harmless.
        assertThat(row).contains("\"{\"\"note\"\":\"\"=1+1\"\"}\"");
        assertThat(row).endsWith("\r\n");
    }

    @Test
    @DisplayName("a JSON Lines entry is one parseable line with the detail as an object")
    void jsonLine() throws Exception {
        AuditEvent event = event("user-1", null, Map.of("inner", Map.of("b", 1, "a", 2)));

        String line = AuditExport.jsonLine(event);

        assertThat(line).endsWith("\n");
        assertThat(line.indexOf('\n')).isEqualTo(line.length() - 1);
        JsonNode parsed = new ObjectMapper().readTree(line);
        assertThat(parsed.get("actor_id").asText()).isEqualTo("user-1");
        assertThat(parsed.get("resource_id").isNull()).isTrue();
        assertThat(parsed.get("detail").get("inner").get("a").asInt()).isEqualTo(2);
        assertThat(parsed.get("occurred_at").asText()).isEqualTo("2026-10-06T01:02:03.123456Z");
        assertThat(parsed.get("entry_hash").asText()).isEqualTo("entry");
        assertThat(List.copyOf(parsed.properties().stream().map(Map.Entry::getKey).toList()))
                .startsWith("sequence", "occurred_at", "actor_id");
    }
}
