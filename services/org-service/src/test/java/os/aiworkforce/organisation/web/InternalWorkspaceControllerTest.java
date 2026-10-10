// @find: tests for internal workspace endpoint, GET /internal/workspaces/{id}, service token reads, person refused, API key refused, 404
// @what: Tests who may read a workspace through the internal endpoint.
// @flow: Exercises InternalWorkspaceController.
package os.aiworkforce.organisation.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import os.aiworkforce.organisation.domain.Organisation;
import os.aiworkforce.organisation.repository.Organisations;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.web.error.GlobalExceptionHandler;
import os.aiworkforce.platform.web.security.PermissionInterceptor;

/** The internal workspace read answers sibling services and nobody else. */
class InternalWorkspaceControllerTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Organisations organisations = mock(Organisations.class);
        Organisation org = new Organisation();
        org.setId(ORG);
        org.setName("Acme Operations");
        org.setSlug("acme-operations");
        org.setTimezone("Europe/London");
        when(organisations.findById(ORG)).thenReturn(Optional.of(org));
        mvc = MockMvcBuilders.standaloneSetup(new InternalWorkspaceController(organisations))
                .addInterceptors(new PermissionInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("a service token with no workspace reads the name and timezone")
    void serviceReads() throws Exception {
        RequestContext.setActor(Actor.SYSTEM);

        mvc.perform(get("/internal/workspaces/{id}", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ORG.toString()))
                .andExpect(jsonPath("$.name").value("Acme Operations"))
                .andExpect(jsonPath("$.slug").value("acme-operations"))
                .andExpect(jsonPath("$.timezone").value("Europe/London"))
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    @DisplayName("an agent's service token reads it too")
    void agentReads() throws Exception {
        RequestContext.setActor(new Actor(
                "agent-1", Actor.Kind.AGENT, ORG.toString(), null, Set.of(), 0L, "person", null, "agent-1", Map.of()));

        mvc.perform(get("/internal/workspaces/{id}", ORG)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a person's token is refused, even an owner's in the same workspace")
    void userRefused() throws Exception {
        RequestContext.setActor(Actor.user(
                UUID.randomUUID().toString(), ORG.toString(), "owner", Set.of(Permission.Codes.WORKSPACE_READ), 0L));

        mvc.perform(get("/internal/workspaces/{id}", ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("permission_denied"));
    }

    @Test
    @DisplayName("a machine key is refused")
    void apiKeyRefused() throws Exception {
        RequestContext.setActor(new Actor(
                "key-1", Actor.Kind.API_KEY, ORG.toString(), null, Set.of(), 0L, "person", null, null, Map.of()));

        mvc.perform(get("/internal/workspaces/{id}", ORG)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an unknown workspace is a 404")
    void unknownWorkspace() throws Exception {
        RequestContext.setActor(Actor.SYSTEM);

        mvc.perform(get("/internal/workspaces/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
    }
}
