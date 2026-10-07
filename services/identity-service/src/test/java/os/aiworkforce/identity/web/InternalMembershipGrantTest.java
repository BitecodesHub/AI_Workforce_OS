package os.aiworkforce.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import os.aiworkforce.identity.domain.Membership;
import os.aiworkforce.identity.domain.Role;
import os.aiworkforce.identity.service.GrantFixture;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.web.error.GlobalExceptionHandler;
import os.aiworkforce.platform.web.security.PermissionInterceptor;

/**
 * Accepting an invitation, from identity's side: the role is checked against the inviter as they
 * are now, a removed member comes back, and an active one keeps the role they hold.
 */
class InternalMembershipGrantTest {

    private GrantFixture workspace;
    private MockMvc mvc;

    private UUID owner;
    private UUID admin;

    @BeforeEach
    void setUp() {
        workspace = new GrantFixture();
        owner = workspace.member("Olivia Owner", workspace.owner, "active");
        admin = workspace.member("Arjun Admin", workspace.admin, "active");
        InternalMembershipController controller = new InternalMembershipController(
                workspace.memberships, workspace.roles, workspace.users, workspace.guard);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new PermissionInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        RequestContext.setActor(GrantFixture.service());
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    private ResultActions bootstrap(UUID userId, String roleName, UUID invitedBy, String email) throws Exception {
        String body = "{\"orgId\":\"" + GrantFixture.ORG + "\",\"userId\":\"" + userId + "\",\"roleName\":\""
                + roleName + "\",\"invitedBy\":\"" + invitedBy + "\""
                + (email == null ? "" : ",\"email\":\"" + email + "\"") + "}";
        return mvc.perform(post("/internal/memberships/bootstrap-member")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions checkGrant(UUID actorUserId, String roleName) throws Exception {
        String body = "{\"orgId\":\"" + GrantFixture.ORG + "\",\"actorUserId\":\"" + actorUserId
                + "\",\"roleName\":\"" + roleName + "\"}";
        return mvc.perform(post("/internal/memberships/check-grant")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    @DisplayName("a removed member accepting a new invitation is active again, at the invited role")
    void reactivatesRemovedMember() throws Exception {
        UUID returning = workspace.member("Rita Returning", workspace.admin, "removed");
        Instant before = workspace.membershipOf(returning).getJoinedAt();

        bootstrap(returning, "employee", admin, workspace.user(returning).getEmail())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("employee"));

        Membership membership = workspace.membershipOf(returning);
        assertThat(membership.isActive()).isTrue();
        assertThat(membership.getRoleId()).isEqualTo(workspace.employee.getId());
        assertThat(membership.getInvitedBy()).isEqualTo(admin);
        assertThat(membership.getJoinedAt()).isAfter(before);
    }

    @Test
    @DisplayName("a suspended member is brought back the same way")
    void reactivatesSuspendedMember() throws Exception {
        UUID suspended = workspace.member("Sam Suspended", workspace.employee, "suspended");

        bootstrap(suspended, "manager", owner, null).andExpect(status().isOk());

        assertThat(workspace.membershipOf(suspended).isActive()).isTrue();
        assertThat(workspace.membershipOf(suspended).getRoleId()).isEqualTo(workspace.manager.getId());
    }

    @Test
    @DisplayName("an active member keeps the role they hold, and the response says which")
    void activeMemberKeepsRole() throws Exception {
        UUID member = workspace.member("Maya Manager", workspace.manager, "active");

        bootstrap(member, "employee", admin, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("manager"));

        assertThat(workspace.membershipOf(member).getRoleId()).isEqualTo(workspace.manager.getId());
    }

    @Test
    @DisplayName("a new person is added at the invited role, with the inviter recorded")
    void newMemberCreated() throws Exception {
        UUID newcomer = workspace.stranger("Nia Newcomer");

        bootstrap(newcomer, "employee", admin, workspace.user(newcomer).getEmail())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("employee"));

        Membership membership = workspace.membershipOf(newcomer);
        assertThat(membership.isActive()).isTrue();
        assertThat(membership.getInvitedBy()).isEqualTo(admin);
    }

    @Test
    @DisplayName("an invitation from a role holding only member:invite cannot make an admin")
    void inviteOnlyRoleCannotGrantAdmin() throws Exception {
        Role inviter = workspace.customRole("inviter", Permission.Codes.MEMBER_INVITE, Permission.Codes.WORKSPACE_READ);
        UUID sender = workspace.member("Ivy Inviter", inviter, "active");
        UUID newcomer = workspace.stranger("Nia Newcomer");

        bootstrap(newcomer, "admin", sender, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors.reason").value("exceeds_grantor"));
        assertThat(workspace.membershipOf(newcomer)).isNull();

        checkGrant(sender, "admin")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.reason").value("exceeds_grantor"));
    }

    @Test
    @DisplayName("owner is granted only when the inviter is an owner at the moment of acceptance")
    void ownerOnlyFromOwners() throws Exception {
        UUID first = workspace.stranger("First Person");
        bootstrap(first, "owner", admin, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors.reason").value("owner_only"));

        bootstrap(first, "owner", owner, null).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("owner"));
    }

    @Test
    @DisplayName("an inviter demoted since sending the invitation can no longer give the role")
    void demotedInviterRefused() throws Exception {
        UUID newcomer = workspace.stranger("Nia Newcomer");
        workspace.membershipOf(admin).setRoleId(workspace.employee.getId());

        bootstrap(newcomer, "manager", admin, null).andExpect(status().isForbidden());

        workspace.membershipOf(admin).setStatus("removed");
        bootstrap(newcomer, "employee", admin, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors.reason").value("not_a_member"));
    }

    @Test
    @DisplayName("an account whose address is not the invitation's is refused")
    void emailMustMatch() throws Exception {
        UUID someone = workspace.stranger("Sid Someone");

        bootstrap(someone, "employee", admin, "another.person@example.test")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors.reason").value("email_mismatch"));

        bootstrap(someone, "employee", admin, "SID.SOMEONE@example.test").andExpect(status().isOk());
    }

    @Test
    @DisplayName("an unknown role is a validation failure at bootstrap and unknown_role at check-grant")
    void unknownRole() throws Exception {
        bootstrap(workspace.stranger("Nia Newcomer"), "astronaut", admin, null)
                .andExpect(status().isUnprocessableEntity());

        checkGrant(admin, "astronaut")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.reason").value("unknown_role"));
    }

    @Test
    @DisplayName("check-grant allows what the actor holds, and says not_a_member for a stranger")
    void checkGrantAnswers() throws Exception {
        checkGrant(admin, "employee").andExpect(jsonPath("$.allowed").value(true));
        checkGrant(owner, "owner").andExpect(jsonPath("$.allowed").value(true));
        checkGrant(admin, "owner").andExpect(jsonPath("$.reason").value("owner_only"));
        checkGrant(workspace.stranger("Out Sider"), "employee").andExpect(jsonPath("$.reason").value("not_a_member"));
    }

    @Test
    @DisplayName("a person's own token cannot call the internal endpoints")
    void userTokensRefused() throws Exception {
        RequestContext.setActor(workspace.tokenOf(owner));

        bootstrap(workspace.stranger("Nia Newcomer"), "employee", owner, null).andExpect(status().isForbidden());
        checkGrant(owner, "employee").andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the permissions endpoint tells an active member what their role holds, and a stranger nothing")
    void permissionsOfAMember() throws Exception {
        mvc.perform(post("/internal/memberships/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":\"" + GrantFixture.ORG + "\",\"userId\":\"" + admin + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.member").value(true));
        mvc.perform(post("/internal/memberships/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":\"" + GrantFixture.ORG + "\",\"userId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.member").value(false))
                .andExpect(jsonPath("$.permissions").isEmpty());
    }
}
