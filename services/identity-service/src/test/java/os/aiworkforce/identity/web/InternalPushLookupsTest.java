// @find: tests for internal sessions live, batch member permissions, push notification lookups, service token only, InternalPushLookupsTest
// @what: Tests of the two internal lookups the orchestrator's push notifications use: which sessions are still signed in, and what many members hold at once.
package os.aiworkforce.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import os.aiworkforce.identity.repository.Sessions;
import os.aiworkforce.identity.service.GrantFixture;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.web.error.GlobalExceptionHandler;
import os.aiworkforce.platform.web.security.PermissionInterceptor;

/** What identity tells the orchestrator before it sends a push notification. */
class InternalPushLookupsTest {

    private GrantFixture workspace;
    private Sessions sessions;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        workspace = new GrantFixture();
        sessions = mock(Sessions.class);
        mvc = MockMvcBuilders.standaloneSetup(
                        new InternalMembershipController(
                                workspace.memberships, workspace.roles, workspace.users, workspace.guard),
                        new InternalSessionController(sessions))
                .addInterceptors(new PermissionInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        RequestContext.setActor(GrantFixture.service());
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    private ResultActions batch(Collection<UUID> userIds) throws Exception {
        String ids = userIds.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
        return mvc.perform(post("/internal/memberships/permissions/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orgId\":\"" + GrantFixture.ORG + "\",\"userIds\":[" + ids + "]}"));
    }

    private ResultActions live(Collection<UUID> sessionIds) throws Exception {
        String ids = sessionIds.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
        return mvc.perform(post("/internal/sessions/live")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionIds\":[" + ids + "]}"));
    }

    @Test
    @DisplayName("many members' permissions come back in one answer; a removed member and a stranger are left out")
    void batchPermissions() throws Exception {
        UUID manager = workspace.member("Maya Manager", workspace.manager, "active");
        UUID otherManager = workspace.member("Mo Manager", workspace.manager, "active");
        UUID employee = workspace.member("Eli Employee", workspace.employee, "active");
        UUID removed = workspace.member("Rita Removed", workspace.manager, "removed");
        UUID stranger = workspace.stranger("Sam Stranger");

        batch(List.of(manager, otherManager, employee, removed, stranger))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions['" + manager + "']").isArray())
                .andExpect(jsonPath("$.permissions['" + manager + "'][?(@ == '" + Permission.Codes.APPROVAL_DECIDE + "')]").exists())
                .andExpect(jsonPath("$.permissions['" + otherManager + "']").isArray())
                .andExpect(jsonPath("$.permissions['" + employee + "'][?(@ == '" + Permission.Codes.APPROVAL_DECIDE + "')]").doesNotExist())
                .andExpect(jsonPath("$.permissions['" + removed + "']").doesNotExist())
                .andExpect(jsonPath("$.permissions['" + stranger + "']").doesNotExist());

        // Two people with the same role cost one read of it.
        verify(workspace.roles, atMost(1)).findById(eq(workspace.manager.getId()));
    }

    @Test
    @DisplayName("a batch is at most two hundred people, and a person's own token cannot ask")
    void batchLimits() throws Exception {
        List<UUID> tooMany = new ArrayList<>();
        for (int i = 0; i <= InternalMembershipController.MAX_BATCH; i++) {
            tooMany.add(UUID.randomUUID());
        }
        batch(tooMany).andExpect(status().is4xxClientError());

        UUID manager = workspace.member("Maya Manager", workspace.manager, "active");
        RequestContext.setActor(workspace.tokenOf(manager));
        batch(List.of(manager)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the sessions still signed in come back; the query is asked about exactly those sessions, now")
    void liveSessions() throws Exception {
        UUID stillSignedIn = UUID.randomUUID();
        UUID signedOut = UUID.randomUUID();
        when(sessions.findSignedInIds(any(), any())).thenReturn(List.of(stillSignedIn));
        Instant before = Instant.now();

        live(List.of(stillSignedIn, signedOut))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.live.length()").value(1))
                .andExpect(jsonPath("$.live[0]").value(stillSignedIn.toString()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> asked = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Instant> at = ArgumentCaptor.forClass(Instant.class);
        verify(sessions).findSignedInIds(asked.capture(), at.capture());
        assertThat(Set.copyOf(asked.getValue())).containsExactlyInAnyOrder(stillSignedIn, signedOut);
        assertThat(at.getValue()).isAfterOrEqualTo(before);
    }

    @Test
    @DisplayName("an empty question asks the database nothing; too many, or a person's own token or an API key, are refused")
    void liveSessionLimits() throws Exception {
        live(List.of()).andExpect(status().isOk()).andExpect(jsonPath("$.live.length()").value(0));
        verify(sessions, never()).findSignedInIds(any(), any());

        List<UUID> tooMany = new ArrayList<>();
        for (int i = 0; i <= InternalSessionController.MAX_SESSIONS; i++) {
            tooMany.add(UUID.randomUUID());
        }
        live(tooMany).andExpect(status().is4xxClientError());

        UUID manager = workspace.member("Maya Manager", workspace.manager, "active");
        RequestContext.setActor(workspace.tokenOf(manager));
        live(List.of(UUID.randomUUID())).andExpect(status().isForbidden());
        RequestContext.setActor(new Actor(
                "key-1", Actor.Kind.API_KEY, GrantFixture.ORG.toString(), null, Set.of(), 0L, null, null, null, Map.of()));
        live(List.of(UUID.randomUUID())).andExpect(status().isForbidden());
        verify(sessions, never()).findSignedInIds(any(), any());
    }
}
