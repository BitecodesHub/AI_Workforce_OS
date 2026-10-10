// @find: tests for audit controller, GET /api/audit, export, verify, permission audit:read, workspace from token, filters
// @what: Checks the audit endpoints use the caller's workspace, need audit:read and pass filters through.
package os.aiworkforce.analytics.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import os.aiworkforce.analytics.domain.AuditEvent;
import os.aiworkforce.analytics.service.AuditAppender;
import os.aiworkforce.analytics.service.AuditChain;
import os.aiworkforce.analytics.service.AuditExport;
import os.aiworkforce.analytics.service.AuditFilter;
import os.aiworkforce.analytics.service.AuditSearch;
import os.aiworkforce.analytics.service.AuditVerification;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.web.error.GlobalExceptionHandler;
import os.aiworkforce.platform.web.security.PermissionInterceptor;

/** What an auditor can ask of the log: narrowing it, taking it away, and having it re-checked. */
class AuditControllerTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-0000000000a1");

    private AuditSearch search;
    private AuditVerification verification;
    private AuditAppender appender;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        search = mock(AuditSearch.class);
        verification = mock(AuditVerification.class);
        appender = mock(AuditAppender.class);
        mvc = MockMvcBuilders.standaloneSetup(new AuditController(search, verification, appender))
                .addInterceptors(new PermissionInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        RequestContext.setActor(auditor(Set.of(Permission.Codes.AUDIT_READ)));
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    private static Actor auditor(Set<String> permissions) {
        return Actor.user("user-1", ORG.toString(), "role", permissions, 0L);
    }

    private static AuditEvent entry(long sequence, String actorId, String resourceId) {
        AuditEvent event = new AuditEvent();
        event.setOrgId(ORG);
        event.setActorId(actorId);
        event.setActorKind("USER");
        event.setAction("member.role_change");
        event.setResourceType("member");
        event.setResourceId(resourceId);
        event.setOutcome("succeeded");
        event.setDetail(Map.of("to", "owner"));
        event.setOccurredAt(Instant.parse("2026-10-06T01:02:03Z"));
        event.setPreviousHash("prev-" + sequence);
        event.setEntryHash("hash-" + sequence);
        try {
            var field = AuditEvent.class.getDeclaredField("sequence");
            field.setAccessible(true);
            field.setLong(event, sequence);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return event;
    }

    @Test
    @DisplayName("every filter reaches the search, scoped to the caller's own workspace")
    void filtersReachTheSearch() throws Exception {
        when(search.find(any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of(entry(7, "u1", "r1")));

        mvc.perform(get("/api/audit")
                        .param("actorId", "u1")
                        .param("onBehalfOf", "boss")
                        .param("action", "member.role_change, member.remove")
                        .param("resourceType", "member")
                        .param("resourceId", "r1")
                        .param("outcome", "denied")
                        .param("from", "2026-10-01")
                        .param("to", "2026-10-05")
                        .param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sequence").value(7))
                .andExpect(jsonPath("$[0].previousHash").value("prev-7"))
                .andExpect(jsonPath("$[0].entryHash").value("hash-7"));

        ArgumentCaptor<AuditFilter> filter = ArgumentCaptor.forClass(AuditFilter.class);
        verify(search).find(eq(ORG), filter.capture(), isNull(), eq(0), eq(100));
        assertThat(filter.getValue().actorId()).isEqualTo("u1");
        assertThat(filter.getValue().onBehalfOf()).isEqualTo("boss");
        assertThat(filter.getValue().actions()).containsExactly("member.role_change", "member.remove");
        assertThat(filter.getValue().resourceType()).isEqualTo("member");
        assertThat(filter.getValue().resourceId()).isEqualTo("r1");
        assertThat(filter.getValue().outcome()).isEqualTo("denied");
        // A plain date is the whole of that day, so a range of two dates includes both.
        assertThat(filter.getValue().from()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(filter.getValue().to()).isEqualTo(Instant.parse("2026-10-06T00:00:00Z"));
    }

    @Test
    @DisplayName("a cursor pages by sequence, and a page number pages by offset")
    void paging() throws Exception {
        when(search.find(any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        mvc.perform(get("/api/audit").param("before", "50").param("size", "20")).andExpect(status().isOk());
        verify(search).find(eq(ORG), any(), eq(50L), eq(0), eq(20));

        mvc.perform(get("/api/audit").param("page", "3").param("size", "20")).andExpect(status().isOk());
        verify(search).find(eq(ORG), any(), isNull(), eq(60), eq(20));
    }

    @Test
    @DisplayName("a filter that makes no sense is refused with a message, not ignored")
    void badFilters() throws Exception {
        mvc.perform(get("/api/audit").param("outcome", "maybe"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("validation_failed"));
        mvc.perform(get("/api/audit").param("from", "last tuesday")).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/audit").param("from", "2026-10-05").param("to", "2026-10-01"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/audit").param("page", "-1")).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/audit/export").param("format", "xlsx")).andExpect(status().isUnprocessableEntity());

        verify(search, never()).find(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("a locked sign-in is a filterable outcome")
    void lockedOutcome() throws Exception {
        when(search.find(any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        mvc.perform(get("/api/audit").param("outcome", "locked")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("the log needs audit:read for the list, the export and the check")
    void permission() throws Exception {
        RequestContext.setActor(auditor(Set.of()));

        mvc.perform(get("/api/audit")).andExpect(status().isForbidden());
        mvc.perform(get("/api/audit/export")).andExpect(status().isForbidden());
        mvc.perform(get("/api/audit/verify")).andExpect(status().isForbidden());

        verify(search, never()).find(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("a CSV export streams every page, neutralises formulas, and is recorded as an event")
    void csvExport() throws Exception {
        List<AuditEvent> first = new ArrayList<>();
        for (int i = 0; i < AuditExport.PAGE_SIZE; i++) {
            first.add(entry(1000 - i, "u1", "r" + i));
        }
        List<AuditEvent> second = List.of(entry(500, "=cmd|' /C calc'!A0", "tail"));
        when(search.find(eq(ORG), any(), isNull(), eq(0), eq(AuditExport.PAGE_SIZE)))
                .thenReturn(first);
        when(search.find(eq(ORG), any(), eq(501L), eq(0), eq(AuditExport.PAGE_SIZE)))
                .thenReturn(second);

        String body = mvc.perform(get("/api/audit/export").param("format", "csv").param("action", "member.role_change"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/csv")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string(
                        "Content-Disposition",
                        org.hamcrest.Matchers.matchesPattern("attachment; filename=\"\\d{4}-\\d{2}-\\d{2}_Audit-log_v1\\.csv\"")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String[] lines = body.split("\r\n");
        assertThat(lines).hasSize(1 + AuditExport.PAGE_SIZE + 1);
        assertThat(lines[0]).startsWith("sequence,occurred_at,actor_id");
        assertThat(lines[lines.length - 1]).contains("'=cmd|' /C calc'!A0").contains("hash-500");

        ArgumentCaptor<AuditAppender.Command> recorded = ArgumentCaptor.forClass(AuditAppender.Command.class);
        verify(appender).append(recorded.capture());
        assertThat(recorded.getValue().action()).isEqualTo("audit.export");
        assertThat(recorded.getValue().orgId()).isEqualTo(ORG);
        assertThat(recorded.getValue().actorId()).isEqualTo("user-1");
        assertThat(recorded.getValue().detail()).containsEntry("format", "csv");
        assertThat(recorded.getValue().detail().toString()).contains("member.role_change");
    }

    @Test
    @DisplayName("a JSON Lines export is one object per line")
    void jsonlExport() throws Exception {
        when(search.find(eq(ORG), any(), isNull(), eq(0), eq(AuditExport.PAGE_SIZE)))
                .thenReturn(List.of(entry(2, "u1", "r1"), entry(1, "u2", "r2")));

        String body = mvc.perform(get("/api/audit/export").param("format", "jsonl"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/x-ndjson")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String[] lines = body.split("\n");
        assertThat(lines).hasSize(2);
        assertThat(lines[0]).startsWith("{\"sequence\":2,").contains("\"entry_hash\":\"hash-2\"");
    }

    @Test
    @DisplayName("an export that cannot be recorded is not given")
    void exportFailsClosed() throws Exception {
        when(appender.append(any())).thenThrow(new IllegalStateException("database is down"));

        try {
            mvc.perform(get("/api/audit/export"));
        } catch (Exception expected) {
            // The failure surfaces as an error response or an exception; either way nothing was read.
        }

        verify(search, never()).find(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("the check reports the first broken entry for the caller's workspace, or that it verified")
    void verifyEndpoint() throws Exception {
        when(verification.verifyWorkspace(ORG))
                .thenReturn(new AuditChain.Verification(ORG.toString(), 120, 120, null, null));

        mvc.perform(get("/api/audit/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.checked").value(120))
                .andExpect(jsonPath("$.lastSequence").value(120))
                .andExpect(jsonPath("$.firstBrokenSequence").doesNotExist());

        when(verification.verifyWorkspace(ORG))
                .thenReturn(new AuditChain.Verification(ORG.toString(), 41, 41, 41L, "the entry no longer matches its hash"));

        mvc.perform(get("/api/audit/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.firstBrokenSequence").value(41))
                .andExpect(jsonPath("$.reason").value("the entry no longer matches its hash"));
    }
}
