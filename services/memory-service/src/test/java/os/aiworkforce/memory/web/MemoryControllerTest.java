package os.aiworkforce.memory.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import os.aiworkforce.memory.domain.Episode;
import os.aiworkforce.memory.repository.Episodes;
import os.aiworkforce.memory.service.EpisodicMemory;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.web.error.GlobalExceptionHandler;
import os.aiworkforce.platform.web.security.PermissionInterceptor;

/**
 * Reading memory is scoped to the workspace on the caller's token and needs memory:read.
 *
 * <p>The repository is mocked beneath the real service, so these check the workspace that
 * actually reaches the query - whatever the request tried to name.
 */
class MemoryControllerTest {

    private static final UUID ORG_A = UUID.fromString("00000000-0000-7000-8000-00000000000a");
    private static final UUID ORG_B = UUID.fromString("00000000-0000-7000-8000-00000000000b");
    private static final UUID AGENT = UUID.fromString("00000000-0000-7000-8000-0000000000a1");
    private static final UUID RUN = UUID.fromString("00000000-0000-7000-8000-0000000000f1");

    private Episodes episodes;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        episodes = mock(Episodes.class);
        Episode own = Episode.of(ORG_A, AGENT, "decision", "Invoices go out on the first of the month", 8);
        Episode other = Episode.of(ORG_B, AGENT, "observation", "Another company's secret", 5);
        when(episodes.findRecent(eq(ORG_A), eq(AGENT), any())).thenReturn(List.of(own));
        when(episodes.findRecent(eq(ORG_B), eq(AGENT), any())).thenReturn(List.of(other));
        when(episodes.search(eq(ORG_A), eq(AGENT), eq("invoices"), any())).thenReturn(List.of(own));
        when(episodes.search(eq(ORG_B), any(), any(), any())).thenReturn(List.of(other));
        when(episodes.findByOrgIdAndRunIdOrderByOccurredAt(ORG_A, RUN)).thenReturn(List.of(own));
        when(episodes.findByOrgIdAndRunIdOrderByOccurredAt(ORG_B, RUN)).thenReturn(List.of(other));

        mvc = MockMvcBuilders.standaloneSetup(new MemoryController(new EpisodicMemory(episodes)))
                .addInterceptors(new PermissionInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    private static void signedInTo(UUID org, String... permissions) {
        RequestContext.setActor(
                Actor.user(UUID.randomUUID().toString(), org.toString(), "role", Set.of(permissions), 0L));
    }

    @Test
    @DisplayName("a workspace A token naming workspace B in the query still reads only workspace A")
    void orgParameterIgnored() throws Exception {
        signedInTo(ORG_A, Permission.Codes.MEMORY_READ);

        mvc.perform(get("/api/memory/episodes")
                        .param("orgId", ORG_B.toString())
                        .param("agentId", AGENT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].summary").value("Invoices go out on the first of the month"))
                .andExpect(jsonPath("$[0].orgId").doesNotExist());

        verify(episodes).findRecent(eq(ORG_A), eq(AGENT), any());
        verify(episodes, never()).findRecent(eq(ORG_B), any(), any());
    }

    @Test
    @DisplayName("a search stays inside the token's workspace and the named agent")
    void searchScoped() throws Exception {
        signedInTo(ORG_A, Permission.Codes.MEMORY_READ);

        mvc.perform(get("/api/memory/episodes")
                        .param("orgId", ORG_B.toString())
                        .param("agentId", AGENT.toString())
                        .param("query", "invoices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("decision"));

        verify(episodes).search(eq(ORG_A), eq(AGENT), eq("invoices"), any());
        verify(episodes, never()).search(eq(ORG_B), any(), any(), any());
    }

    @Test
    @DisplayName("a run's episodes come from the token's workspace, whatever the query names")
    void runScoped() throws Exception {
        signedInTo(ORG_A, Permission.Codes.MEMORY_READ);

        mvc.perform(get("/api/memory/episodes/run/{runId}", RUN).param("orgId", ORG_B.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].summary").value("Invoices go out on the first of the month"));

        verify(episodes, never()).findByOrgIdAndRunIdOrderByOccurredAt(ORG_B, RUN);
    }

    @Test
    @DisplayName("a member without memory:read gets 403 from both reads")
    void readPermissionRequired() throws Exception {
        signedInTo(ORG_A, Permission.Codes.AGENT_READ);

        mvc.perform(get("/api/memory/episodes").param("agentId", AGENT.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("permission_denied"));
        mvc.perform(get("/api/memory/episodes/run/{runId}", RUN))
                .andExpect(status().isForbidden());

        verifyNoInteractions(episodes);
    }

    @Test
    @DisplayName("a token with no workspace selected reads nothing")
    void noWorkspace() throws Exception {
        RequestContext.setActor(Actor.user(UUID.randomUUID().toString(), null, null, Set.of(), 0L));

        mvc.perform(get("/api/memory/episodes").param("agentId", AGENT.toString()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(episodes);
    }
}
