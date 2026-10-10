// @find: tests for workspaces, create workspace, update settings, timezone validation, cross-workspace refusal, workspace:update permission, rename
// @what: Tests workspace creation and settings changes, including permission and tenant-isolation checks.
// @flow: Exercises WorkspaceController.
package os.aiworkforce.organisation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.organisation.domain.Organisation;
import os.aiworkforce.organisation.repository.Organisations;
import os.aiworkforce.organisation.service.InternalTokenProvider;
import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.web.error.GlobalExceptionHandler;
import os.aiworkforce.platform.web.security.PermissionInterceptor;

/**
 * Workspace settings change only in the caller's own workspace, only with workspace:update, and
 * only to a name and a timezone that can actually be used.
 *
 * <p>Runs through MockMvc with the real permission interceptor and error handler, so a refusal is
 * checked as the status and code a client would receive.
 */
class WorkspaceControllerTest {

    private static final UUID ORG_A = UUID.fromString("00000000-0000-7000-8000-00000000000a");
    private static final UUID ORG_B = UUID.fromString("00000000-0000-7000-8000-00000000000b");

    private Organisations organisations;
    private Organisation own;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        organisations = mock(Organisations.class);
        own = workspace(ORG_A, "Acme Operations");
        when(organisations.findById(ORG_A)).thenReturn(Optional.of(own));
        when(organisations.findById(ORG_B)).thenReturn(Optional.of(workspace(ORG_B, "Other Company")));
        when(organisations.findBySlug(any())).thenReturn(Optional.empty());

        PlatformProperties properties = mock(PlatformProperties.class);
        when(properties.services())
                .thenReturn(new PlatformProperties.Services(
                        "http://gateway",
                        "http://identity",
                        "http://organisation",
                        "http://orchestrator",
                        "http://memory",
                        "http://knowledge",
                        "http://integrations",
                        "http://analytics"));
        WorkspaceController controller = new WorkspaceController(
                organisations, WebClient.builder(), properties, mock(InternalTokenProvider.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new PermissionInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    private static Organisation workspace(UUID id, String name) {
        Organisation org = new Organisation();
        org.setId(id);
        org.setName(name);
        org.setSlug(name.toLowerCase(java.util.Locale.ROOT).replace(' ', '-'));
        org.setTimezone("Australia/Melbourne");
        // The demo workspace has no recorded owner; the old owner check threw on exactly this.
        org.setOwnerId(null);
        return org;
    }

    private static void signedInTo(UUID org, String... permissions) {
        RequestContext.setActor(
                Actor.user(UUID.randomUUID().toString(), org.toString(), "role", Set.of(permissions), 0L));
    }

    private static String body(String name, String timezone) {
        StringBuilder json = new StringBuilder("{");
        if (name != null) {
            json.append("\"name\":\"").append(name).append('"');
        }
        if (timezone != null) {
            json.append(name != null ? "," : "").append("\"timezone\":\"").append(timezone).append('"');
        }
        return json.append('}').toString();
    }

    @Test
    @DisplayName("an administrator of workspace A cannot change workspace B")
    void otherWorkspaceRefused() throws Exception {
        signedInTo(ORG_A, Permission.Codes.WORKSPACE_UPDATE);

        mvc.perform(patch("/api/workspaces/{id}/settings", ORG_B)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Taken over", "UTC")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("organisation_mismatch"));

        verify(organisations, never()).save(any());
    }

    @Test
    @DisplayName("a member without workspace:update gets 403, even in their own workspace")
    void viewerRefused() throws Exception {
        signedInTo(ORG_A, Permission.Codes.WORKSPACE_READ);

        mvc.perform(patch("/api/workspaces/{id}/settings", ORG_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Renamed", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("permission_denied"));

        verify(organisations, never()).save(any());
    }

    @Test
    @DisplayName("a token with no workspace selected cannot change settings")
    void noWorkspaceRefused() throws Exception {
        RequestContext.setActor(Actor.user(UUID.randomUUID().toString(), null, null, Set.of(), 0L));

        mvc.perform(patch("/api/workspaces/{id}/settings", ORG_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Renamed", null)))
                .andExpect(status().isForbidden());

        verify(organisations, never()).save(any());
    }

    @Test
    @DisplayName("an unrecognised timezone is a validation error, not a silently saved value")
    void invalidTimezone() throws Exception {
        signedInTo(ORG_A, Permission.Codes.WORKSPACE_UPDATE);

        mvc.perform(patch("/api/workspaces/{id}/settings", ORG_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, "Mars/Olympus_Mons")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.errors.field").value("timezone"))
                .andExpect(jsonPath("$.errors.problem").value("That is not a recognised time zone."));

        verify(organisations, never()).save(any());
    }

    @Test
    @DisplayName("a fixed offset is not a time zone the console can show, on change or on create")
    void fixedOffsetRefused() throws Exception {
        signedInTo(ORG_A, Permission.Codes.WORKSPACE_UPDATE);
        mvc.perform(patch("/api/workspaces/{id}/settings", ORG_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, "UTC+10")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors.field").value("timezone"));

        RequestContext.setActor(Actor.user(UUID.randomUUID().toString(), null, null, Set.of(), 0L));
        mvc.perform(post("/api/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Acme Operations", "UTC+10")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors.field").value("timezone"));

        verify(organisations, never()).save(any());
    }

    @Test
    @DisplayName("a good name with a bad timezone changes nothing at all")
    void nothingHalfApplied() throws Exception {
        signedInTo(ORG_A, Permission.Codes.WORKSPACE_UPDATE);

        mvc.perform(patch("/api/workspaces/{id}/settings", ORG_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Acme Field Services", "Somewhere/Else")))
                .andExpect(status().isUnprocessableEntity());

        assertThat(own.getName()).isEqualTo("Acme Operations");
        assertThat(own.getTimezone()).isEqualTo("Australia/Melbourne");
        verify(organisations, never()).save(any());
    }

    @Test
    @DisplayName("a name that is only spaces is rejected rather than saved as an empty name")
    void blankName() throws Exception {
        signedInTo(ORG_A, Permission.Codes.WORKSPACE_UPDATE);

        mvc.perform(patch("/api/workspaces/{id}/settings", ORG_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("   ", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors.field").value("name"));

        verify(organisations, never()).save(any());
    }

    @Test
    @DisplayName("an administrator renames their own workspace and moves its timezone")
    void ownWorkspaceUpdated() throws Exception {
        signedInTo(ORG_A, Permission.Codes.WORKSPACE_UPDATE);

        mvc.perform(patch("/api/workspaces/{id}/settings", ORG_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("  Acme Field Services  ", " Europe/London ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Acme Field Services"))
                .andExpect(jsonPath("$.timezone").value("Europe/London"));

        verify(organisations).save(any());
    }

    @Test
    @DisplayName("creating a workspace with an unrecognised timezone creates nothing")
    void createValidatesTimezone() throws Exception {
        RequestContext.setActor(Actor.user(UUID.randomUUID().toString(), null, null, Set.of(), 0L));

        mvc.perform(post("/api/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Acme Operations", "Not/A_Zone")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors.field").value("timezone"));

        verify(organisations, never()).save(any());
    }

    @Test
    @DisplayName("a person reads their own workspace, and another workspace looks like it does not exist")
    void readScopedToOwnWorkspace() throws Exception {
        signedInTo(ORG_A);

        mvc.perform(get("/api/workspaces/{id}", ORG_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Acme Operations"));
        mvc.perform(get("/api/workspaces/{id}", ORG_B))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    @DisplayName("a service token with no workspace can still read one, so schedules keep their zone")
    void serviceReadsAnyWorkspace() throws Exception {
        RequestContext.setActor(Actor.SYSTEM);

        mvc.perform(get("/api/workspaces/{id}", ORG_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Australia/Melbourne"));
    }
}
