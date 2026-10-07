package os.aiworkforce.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import os.aiworkforce.identity.domain.Role;
import os.aiworkforce.identity.service.GrantFixture;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.web.audit.AuditClient;
import os.aiworkforce.platform.web.error.GlobalExceptionHandler;
import os.aiworkforce.platform.web.security.PermissionInterceptor;

/**
 * Building and editing roles follows the grant rule: a role carries only what its author holds,
 * so the role builder cannot be used to step around who may give what.
 */
class RoleCompositionGuardTest {

    private GrantFixture workspace;
    private AuditClient audit;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        workspace = new GrantFixture();
        audit = mock(AuditClient.class);
        RoleController controller = new RoleController(workspace.roles, workspace.memberships, workspace.guard, audit);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new PermissionInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    private ResultActions create(String name, String... codes) throws Exception {
        return mvc.perform(post("/api/roles").contentType(MediaType.APPLICATION_JSON).content(body(name, codes)));
    }

    private ResultActions update(UUID roleId, String name, String... codes) throws Exception {
        return mvc.perform(
                put("/api/roles/{roleId}", roleId).contentType(MediaType.APPLICATION_JSON).content(body(name, codes)));
    }

    private static String body(String name, String... codes) {
        StringBuilder json = new StringBuilder("{\"name\":\"" + name + "\",\"permissions\":[");
        for (int i = 0; i < codes.length; i++) {
            json.append(i == 0 ? "" : ",").append('"').append(codes[i]).append('"');
        }
        return json.append("]}").toString();
    }

    @Test
    @DisplayName("a role:create holder adding permissions they lack is refused, and told which")
    void composingBeyondOwnPermissionsIsRefused() throws Exception {
        Role builder = workspace.customRole(
                "builder", Permission.Codes.ROLE_CREATE, Permission.Codes.WORKSPACE_READ, Permission.Codes.AGENT_READ);
        RequestContext.setActor(workspace.tokenOf(workspace.member("Bea Builder", builder, "active")));

        create("everything", Permission.Codes.AGENT_READ, Permission.Codes.MEMBER_UPDATE, Permission.Codes.ROLE_UPDATE)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("permission_denied"))
                .andExpect(jsonPath("$.errors.missing[0]").value("member:update"))
                .andExpect(jsonPath("$.errors.missing[1]").value("role:update"));

        create("readers", Permission.Codes.AGENT_READ, Permission.Codes.WORKSPACE_READ)
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("an admin cannot build a role carrying a permission only owners hold")
    void adminCannotComposeOwnerOnlyPermissions() throws Exception {
        RequestContext.setActor(workspace.tokenOf(workspace.member("Arjun Admin", workspace.admin, "active")));

        create("closer", Permission.Codes.WORKSPACE_READ, Permission.Codes.WORKSPACE_DELETE)
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a role:update holder widening the role they hold is refused")
    void wideningOwnRoleIsRefused() throws Exception {
        Role editor = workspace.customRole(
                "editor", Permission.Codes.ROLE_UPDATE, Permission.Codes.WORKSPACE_READ, Permission.Codes.AGENT_READ);
        RequestContext.setActor(workspace.tokenOf(workspace.member("Ed Editor", editor, "active")));

        update(editor.getId(), "editor", Permission.Codes.ROLE_UPDATE, Permission.Codes.WORKSPACE_READ,
                        Permission.Codes.AGENT_READ, Permission.Codes.AGENT_UPDATE)
                .andExpect(status().isForbidden());
        assertThat(editor.getPermissions()).doesNotContain(Permission.Codes.AGENT_UPDATE);

        // Narrowing their own role is allowed.
        update(editor.getId(), "editor", Permission.Codes.ROLE_UPDATE, Permission.Codes.WORKSPACE_READ)
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a role carrying permissions the editor lacks cannot be edited by them, even to narrow it")
    void editingAWiderRoleIsRefused() throws Exception {
        Role auditor = workspace.customRole("auditor", Permission.Codes.AUDIT_READ, Permission.Codes.WORKSPACE_DELETE);
        RequestContext.setActor(workspace.tokenOf(workspace.member("Arjun Admin", workspace.admin, "active")));

        update(auditor.getId(), "auditor", Permission.Codes.AUDIT_READ)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors.reason").value("exceeds_grantor"));
    }

    @Test
    @DisplayName("an owner may compose any role")
    void ownerComposesFreely() throws Exception {
        RequestContext.setActor(workspace.tokenOf(workspace.member("Olivia Owner", workspace.owner, "active")));

        create("closer", Permission.Codes.WORKSPACE_READ, Permission.Codes.WORKSPACE_DELETE)
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("the permission catalogue leaves planned codes out")
    void catalogueOmitsPlanned() throws Exception {
        RequestContext.setActor(workspace.tokenOf(workspace.member("Emma Employee", workspace.employee, "active")));

        mvc.perform(get("/api/roles/permissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(Permission.available().size())))
                .andExpect(jsonPath("$[*].code", hasItem("workspace:update")))
                .andExpect(jsonPath("$[*].code", not(hasItem("workspace:delete"))))
                .andExpect(jsonPath("$[*].code", not(hasItem("api_key:read"))))
                .andExpect(jsonPath("$[*].code", not(hasItem("memory:purge"))));
    }

    @Test
    @DisplayName("a stored role holding a planned code still validates, so it can be saved again")
    void plannedCodesStillValidate() throws Exception {
        RequestContext.setActor(workspace.tokenOf(workspace.member("Olivia Owner", workspace.owner, "active")));

        create("keys", Permission.Codes.WORKSPACE_READ, Permission.Codes.API_KEY_READ)
                .andExpect(status().isCreated());
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, Object>> detail() {
        return ArgumentCaptor.forClass(Map.class);
    }

    @Test
    @DisplayName("creating a role is recorded with its name and its permissions")
    void creationIsAudited() throws Exception {
        RequestContext.setActor(workspace.tokenOf(workspace.member("Olivia Owner", workspace.owner, "active")));

        create("readers", Permission.Codes.WORKSPACE_READ, Permission.Codes.AGENT_READ).andExpect(status().isCreated());

        ArgumentCaptor<Map<String, Object>> detail = detail();
        verify(audit).record(eq("role.create"), eq("role"), any(), eq("succeeded"), detail.capture());
        assertThat(detail.getValue()).containsEntry("name", "readers");
        assertThat(detail.getValue().get("permissions")).isEqualTo(List.of("agent:read", "workspace:read"));
    }

    @Test
    @DisplayName("a refused role is not recorded as created")
    void refusedCreationIsNotAudited() throws Exception {
        RequestContext.setActor(workspace.tokenOf(workspace.member("Arjun Admin", workspace.admin, "active")));

        create("closer", Permission.Codes.WORKSPACE_READ, Permission.Codes.WORKSPACE_DELETE)
                .andExpect(status().isForbidden());

        verify(audit, never()).record(eq("role.create"), any(), any(), any(), any());
    }

    @Test
    @DisplayName("changing a role records the permissions it gained and lost, and the name it had")
    void updateIsAudited() throws Exception {
        Role team = workspace.customRole("team", Permission.Codes.WORKSPACE_READ, Permission.Codes.AGENT_READ);
        RequestContext.setActor(workspace.tokenOf(workspace.member("Olivia Owner", workspace.owner, "active")));

        update(team.getId(), "crew", Permission.Codes.WORKSPACE_READ, Permission.Codes.CHAT_USE)
                .andExpect(status().isOk());

        ArgumentCaptor<Map<String, Object>> detail = detail();
        verify(audit).record(eq("role.update"), eq("role"), eq(team.getId().toString()), eq("succeeded"), detail.capture());
        assertThat(detail.getValue())
                .containsEntry("name", "crew")
                .containsEntry("previousName", "team")
                .containsEntry("added", List.of("chat:use"))
                .containsEntry("removed", List.of("agent:read"));
    }

    @Test
    @DisplayName("deleting a role records what it was")
    void deletionIsAudited() throws Exception {
        Role spare = workspace.customRole("spare", Permission.Codes.WORKSPACE_READ);
        RequestContext.setActor(workspace.tokenOf(workspace.member("Olivia Owner", workspace.owner, "active")));

        mvc.perform(delete("/api/roles/{roleId}", spare.getId())).andExpect(status().isNoContent());

        ArgumentCaptor<Map<String, Object>> detail = detail();
        verify(audit).record(eq("role.delete"), eq("role"), eq(spare.getId().toString()), eq("succeeded"), detail.capture());
        assertThat(detail.getValue()).containsEntry("name", "spare");
        assertThat(detail.getValue().get("permissions")).isEqualTo(List.of("workspace:read"));
    }
}
