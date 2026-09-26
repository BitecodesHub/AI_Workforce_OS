package os.aiworkforce.identity.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.identity.domain.Membership;
import os.aiworkforce.identity.domain.Role;
import os.aiworkforce.identity.domain.User;
import os.aiworkforce.identity.repository.Memberships;
import os.aiworkforce.identity.repository.Roles;
import os.aiworkforce.identity.repository.Users;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * The people in a workspace, and the one signed in.
 *
 * <p>Memberships live here, in identity, because they decide what a token carries. Serving the
 * list from anywhere else would mean a second copy of who holds which role, and two copies of an
 * authorisation fact are two chances for them to disagree.
 */
@RestController
@RequestMapping("/api/users")
@Tag(name = "Members")
public class MemberController {

    private final Users users;
    private final Memberships memberships;
    private final Roles roles;

    public MemberController(Users users, Memberships memberships, Roles roles) {
        this.users = users;
        this.memberships = memberships;
        this.roles = roles;
    }

    public record MemberView(
            UUID userId,
            String displayName,
            String email,
            String role,
            String status,
            Instant joinedAt,
            Instant lastSignInAt) {}

    public record MeView(
            UUID userId, String displayName, String email, String role, List<String> permissions) {}

    @GetMapping
    @RequiresPermission(Permission.Codes.MEMBER_READ)
    @Operation(summary = "Members of this workspace and the role each holds")
    public List<MemberView> list() {
        UUID orgId = UUID.fromString(RequestContext.requireOrgId());
        List<Membership> all = memberships.findByOrgIdAndStatus(orgId, "active");

        Map<UUID, User> people = users.findByIdIn(all.stream().map(Membership::getUserId).toList())
                .stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<UUID, String> roleNames = roles.findAvailableTo(orgId).stream()
                .collect(Collectors.toMap(Role::getId, Role::getName));

        return all.stream()
                .filter(membership -> people.containsKey(membership.getUserId()))
                .map(membership -> {
                    User user = people.get(membership.getUserId());
                    return new MemberView(
                            user.getId(), user.getDisplayName(), user.getEmail(),
                            roleNames.getOrDefault(membership.getRoleId(), "unknown"),
                            membership.getStatus(), membership.getJoinedAt(), user.getLastLoginAt());
                })
                .sorted(java.util.Comparator.comparing(MemberView::displayName))
                .toList();
    }

    @GetMapping("/me")
    @RequiresPermission(value = Permission.Codes.WORKSPACE_READ, allowWithoutOrganisation = true)
    @Operation(summary = "The signed-in account")
    public MeView me() {
        var actor = RequestContext.requireActor();
        User user = users.findById(UUID.fromString(actor.id()))
                .orElseThrow(() -> os.aiworkforce.platform.error.ApiException.notFound("user", actor.id()));
        String role = actor.roleId() == null
                ? null
                : roles.findById(UUID.fromString(actor.roleId())).map(Role::getName).orElse(null);
        return new MeView(
                user.getId(), user.getDisplayName(), user.getEmail(), role,
                actor.permissions().stream().sorted().toList());
    }
}
