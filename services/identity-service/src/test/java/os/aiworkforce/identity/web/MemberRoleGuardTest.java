// @find: tests for member role change, promote to owner refused, demote owner, remove owner, last owner kept, MemberController
// @what: Tests that member role changes and removals follow the grant rules.
package os.aiworkforce.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import os.aiworkforce.identity.domain.Membership;
import os.aiworkforce.identity.domain.Role;
import os.aiworkforce.identity.service.GrantFixture;
import os.aiworkforce.identity.service.OrchestratorClient;
import os.aiworkforce.identity.service.TokenService;
import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.web.audit.AuditClient;
import os.aiworkforce.platform.web.error.GlobalExceptionHandler;
import os.aiworkforce.platform.web.security.PermissionInterceptor;

/**
 * Changing roles and removing people, through MockMvc with the real permission interceptor and
 * error handler: a refusal is checked as the status and code the console would receive.
 */
class MemberRoleGuardTest {

    private static final UUID OTHER_ORG = UUID.fromString("00000000-0000-7000-8000-0000000000b2");

    private GrantFixture workspace;
    private OrchestratorClient orchestrator;
    private AuditClient audit;
    private TokenService tokens;
    private final List<String> organisationCalls = new ArrayList<>();
    private boolean organisationDown;
    private MockMvc mvc;

    private UUID owner;
    private UUID admin;
    private UUID employee;

    @BeforeEach
    void setUp() {
        workspace = new GrantFixture();
        owner = workspace.member("Olivia Owner", workspace.owner, "active");
        admin = workspace.member("Arjun Admin", workspace.admin, "active");
        employee = workspace.member("Emma Employee", workspace.employee, "active");

        orchestrator = mock(OrchestratorClient.class);
        audit = mock(AuditClient.class);
        tokens = mock(TokenService.class);
        when(tokens.issueInternalToken(anyString(), any()))
                .thenReturn(new TokenService.IssuedToken("service-token", Instant.now().plusSeconds(60)));

        WebClient.Builder organisation = WebClient.builder().exchangeFunction(request -> {
            organisationCalls.add(request.url().getPath() + " " + request.headers().getFirst("Authorization"));
            if (organisationDown || request.url().getPath().endsWith(OTHER_ORG.toString())) {
                return Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build());
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body("{\"id\":\"" + GrantFixture.ORG + "\",\"name\":\"Acme Operations\",\"slug\":\"acme\"}")
                    .build());
        });
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

        MemberController controller = new MemberController(
                workspace.users,
                workspace.memberships,
                workspace.roles,
                workspace.guard,
                orchestrator,
                tokens,
                organisation,
                properties,
                audit);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new PermissionInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    private void signIn(UUID userId) {
        RequestContext.setActor(workspace.tokenOf(userId));
    }

    private org.springframework.test.web.servlet.ResultActions changeRole(UUID userId, String roleName)
            throws Exception {
        return mvc.perform(put("/api/users/{userId}/role", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roleName\":\"" + roleName + "\"}"));
    }

    @Test
    @DisplayName("an admin promoting themselves to owner is refused")
    void adminCannotPromoteThemselves() throws Exception {
        signIn(admin);

        changeRole(admin, "owner")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("permission_denied"))
                .andExpect(jsonPath("$.errors.reason").value("owner_only"));

        assertThat(workspace.membershipOf(admin).getRoleId()).isEqualTo(workspace.admin.getId());
    }

    @Test
    @DisplayName("an admin demoting an owner is refused")
    void adminCannotDemoteAnOwner() throws Exception {
        workspace.member("Second Owner", workspace.owner, "active");
        signIn(admin);

        changeRole(owner, "employee")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors.reason").value("owner_only"));

        assertThat(workspace.membershipOf(owner).getRoleId()).isEqualTo(workspace.owner.getId());
    }

    @Test
    @DisplayName("an admin removing an owner is refused, and nothing is paused")
    void adminCannotRemoveAnOwner() throws Exception {
        workspace.member("Second Owner", workspace.owner, "active");
        signIn(admin);

        mvc.perform(delete("/api/users/{userId}", owner)).andExpect(status().isForbidden());

        assertThat(workspace.membershipOf(owner).isActive()).isTrue();
        verify(orchestrator, never()).memberLeftAfterCommit(any(), any(), any());
    }

    @Test
    @DisplayName("an owner may make an admin an owner, and step down while another owner remains")
    void ownersManageOwners() throws Exception {
        signIn(owner);
        changeRole(admin, "owner").andExpect(status().isOk()).andExpect(jsonPath("$.role").value("owner"));

        changeRole(owner, "admin").andExpect(status().isOk()).andExpect(jsonPath("$.role").value("admin"));
        assertThat(workspace.membershipOf(owner).getRoleId()).isEqualTo(workspace.admin.getId());
    }

    @Test
    @DisplayName("the last owner still cannot step down")
    void lastOwnerKept() throws Exception {
        signIn(owner);

        changeRole(owner, "admin")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("last_owner_protected"));
    }

    @Test
    @DisplayName("a narrow role holding member:update cannot change an admin, nor give a role wider than its own")
    void delegatedRoleStaysNarrow() throws Exception {
        Role lead = workspace.customRole(
                "lead",
                Permission.Codes.MEMBER_READ,
                Permission.Codes.MEMBER_UPDATE,
                Permission.Codes.WORKSPACE_READ,
                Permission.Codes.CHAT_USE);
        UUID leadId = workspace.member("Lena Lead", lead, "active");
        signIn(leadId);

        changeRole(admin, "employee")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors.reason").value("exceeds_grantor"));
        changeRole(employee, "manager")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors.reason").value("exceeds_grantor"));
        changeRole(employee, "lead").andExpect(status().isOk());
    }

    @Test
    @DisplayName("nobody widens their own role, even to one they could give someone else")
    void ownRoleOnlyNarrows() throws Exception {
        Role wide = workspace.customRole(
                "wide", Permission.Codes.MEMBER_READ, Permission.Codes.MEMBER_UPDATE, Permission.Codes.WORKSPACE_READ);
        Role wider = workspace.customRole(
                "wider",
                Permission.Codes.MEMBER_READ,
                Permission.Codes.MEMBER_UPDATE,
                Permission.Codes.WORKSPACE_READ,
                Permission.Codes.CHAT_USE);
        UUID person = workspace.member("Will Wide", wide, "active");
        // The token was minted while they briefly held the wider role.
        Actor stale = Actor.user(
                person.toString(),
                GrantFixture.ORG.toString(),
                wide.getId().toString(),
                wider.getPermissions(),
                1L);
        RequestContext.setActor(stale);

        changeRole(person, "wider").andExpect(status().isForbidden());
        assertThat(workspace.membershipOf(person).getRoleId()).isEqualTo(wide.getId());
    }

    @Test
    @DisplayName("removing a member keeps the row, marks it removed and pauses their schedules after commit")
    void removalPausesSchedules() throws Exception {
        signIn(admin);

        mvc.perform(delete("/api/users/{userId}", employee)).andExpect(status().isNoContent());

        Membership membership = workspace.membershipOf(employee);
        assertThat(membership.getStatus()).isEqualTo("removed");
        verify(orchestrator).memberLeftAfterCommit(GrantFixture.ORG, employee, admin.toString());
    }

    @Test
    @DisplayName("removing someone already removed changes nothing and pauses nothing again")
    void removalIsIdempotent() throws Exception {
        workspace.membershipOf(employee).setStatus("removed");
        signIn(admin);

        mvc.perform(delete("/api/users/{userId}", employee)).andExpect(status().isNoContent());

        verify(orchestrator, never()).memberLeftAfterCommit(any(), any(), any());
    }

    @Test
    @DisplayName("/me/workspaces lists active memberships with names from the organisation service")
    void workspacesHaveNames() throws Exception {
        RequestContext.setActor(Actor.user(employee.toString(), null, null, Set.of(), 0L));

        mvc.perform(get("/api/users/me/workspaces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orgId").value(GrantFixture.ORG.toString()))
                .andExpect(jsonPath("$[0].name").value("Acme Operations"))
                .andExpect(jsonPath("$[0].role").value("employee"));

        assertThat(organisationCalls)
                .containsExactly("/internal/workspaces/" + GrantFixture.ORG + " Bearer service-token");
        verify(tokens).issueInternalToken(eq("organisation"), any());
    }

    @Test
    @DisplayName("/me/workspaces still lists a workspace whose name cannot be read, with a null name")
    void workspaceNameFailureIsNull() throws Exception {
        organisationDown = true;
        RequestContext.setActor(Actor.user(employee.toString(), null, null, Set.of(), 0L));

        mvc.perform(get("/api/users/me/workspaces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orgId").value(GrantFixture.ORG.toString()))
                .andExpect(jsonPath("$[0].name").value(nullValue()))
                .andExpect(jsonPath("$[0].role").value("employee"));
    }

    @Test
    @DisplayName("/me/workspaces leaves out removed memberships, and refuses a service token")
    void workspacesOnlyActiveAndOnlyPeople() throws Exception {
        workspace.membershipOf(employee).setStatus("removed");
        RequestContext.setActor(Actor.user(employee.toString(), null, null, Set.of(), 0L));
        mvc.perform(get("/api/users/me/workspaces")).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());

        RequestContext.setActor(GrantFixture.service());
        mvc.perform(get("/api/users/me/workspaces")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("/me works for an account with no workspace and leaves planned codes out")
    void meWithoutWorkspaceAndWithoutPlannedCodes() throws Exception {
        RequestContext.setActor(Actor.user(employee.toString(), null, null, Set.of(), 0L));
        mvc.perform(get("/api/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(workspace.user(employee).getEmail()))
                .andExpect(jsonPath("$.permissions").isEmpty());

        signIn(owner);
        mvc.perform(get("/api/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("owner"))
                .andExpect(jsonPath("$.permissions", hasItem("workspace:update")))
                .andExpect(jsonPath("$.permissions", not(hasItem("workspace:delete"))))
                .andExpect(jsonPath("$.permissions", not(hasItem("api_key:manage"))));
    }

    @Test
    @DisplayName("the member list is unchanged: active members, by name")
    void listStillWorks() throws Exception {
        signIn(employee);
        mvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].displayName", contains("Arjun Admin", "Emma Employee", "Olivia Owner")));
    }

    @Test
    @DisplayName("a role change is recorded with the role before and after, and who made it")
    void roleChangeIsAudited() throws Exception {
        signIn(owner);

        changeRole(employee, "manager").andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> detail = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(audit).record(eq("member.role_change"), eq("member"), eq(employee.toString()), eq("succeeded"), detail.capture());
        assertThat(detail.getValue())
                .containsEntry("fromRole", "employee")
                .containsEntry("toRole", "manager")
                .containsEntry("fromRoleId", workspace.employee.getId().toString())
                .containsEntry("toRoleId", workspace.manager.getId().toString());
    }

    @Test
    @DisplayName("a refused role change is not recorded as one that happened")
    void refusedRoleChangeIsNotAudited() throws Exception {
        signIn(admin);

        changeRole(admin, "owner").andExpect(status().isForbidden());

        verify(audit, never()).record(eq("member.role_change"), any(), any(), any(), any());
    }

    @Test
    @DisplayName("removing a member is recorded with the role they held; removing one already gone is not")
    void removalIsAudited() throws Exception {
        signIn(admin);

        mvc.perform(delete("/api/users/{userId}", employee)).andExpect(status().isNoContent());

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> detail = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(audit).record(eq("member.remove"), eq("member"), eq(employee.toString()), eq("succeeded"), detail.capture());
        assertThat(detail.getValue()).containsEntry("role", "employee");

        org.mockito.Mockito.clearInvocations(audit);
        mvc.perform(delete("/api/users/{userId}", employee)).andExpect(status().isNoContent());
        verify(audit, never()).record(eq("member.remove"), any(), any(), any(), any());
    }
}
