package os.aiworkforce.identity.web;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import os.aiworkforce.identity.domain.Membership;
import os.aiworkforce.identity.domain.Role;
import os.aiworkforce.identity.domain.User;
import os.aiworkforce.identity.repository.Memberships;
import os.aiworkforce.identity.repository.Roles;
import os.aiworkforce.identity.repository.Users;
import os.aiworkforce.identity.service.GrantGuard;
import os.aiworkforce.identity.service.OrchestratorClient;
import os.aiworkforce.identity.service.TokenService;
import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;
import os.aiworkforce.platform.web.audit.AuditClient;

/**
 * The people in a workspace, and the one signed in.
 *
 * <p>Memberships live here, in identity, because they decide what a token carries. Serving the
 * list from anywhere else would mean a second copy of who holds which role, and two copies of an
 * authorisation fact are two chances for them to disagree.
 *
 * <p>Changing a role and removing a member both go through {@link GrantGuard}: nobody grants a
 * role carrying more than they hold, and only an owner touches an owner. Either change reaches the
 * person at their next refresh, within the access-token lifetime - nothing here revokes a token
 * already issued, and a refresh refuses a membership that is no longer active.
 *
 * <p>Both are written to the audit log with who made the change and, for a role, the role before
 * and after - the answer to "who made this person an owner" - in the same transaction as the
 * change.
 */
@RestController
@RequestMapping("/api/users")
@Tag(name = "Members")
public class MemberController {

    private static final Logger log = LoggerFactory.getLogger(MemberController.class);

    /** Long enough for a busy organisation service, short enough that a picker never hangs on it. */
    private static final Duration WORKSPACE_LOOKUP_TIMEOUT = Duration.ofSeconds(3);

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {};

    private final Users users;
    private final Memberships memberships;
    private final Roles roles;
    private final GrantGuard guard;
    private final OrchestratorClient orchestrator;
    private final TokenService tokens;
    private final WebClient organisationClient;
    private final AuditClient audit;

    public MemberController(
            Users users,
            Memberships memberships,
            Roles roles,
            GrantGuard guard,
            OrchestratorClient orchestrator,
            TokenService tokens,
            WebClient.Builder builder,
            PlatformProperties properties,
            AuditClient audit) {
        this.users = users;
        this.memberships = memberships;
        this.roles = roles;
        this.guard = guard;
        this.orchestrator = orchestrator;
        this.tokens = tokens;
        this.organisationClient =
                builder.clone().baseUrl(properties.services().organisation()).build();
        this.audit = audit;
    }

    public record MemberView(
            UUID userId,
            String displayName,
            String email,
            String role,
            String status,
            Instant joinedAt,
            Instant lastSignInAt) {}

    public record MeView(UUID userId, String displayName, String email, String role, List<String> permissions) {}

    /**
     * One workspace the signed-in person belongs to, for choosing which to open.
     *
     * @param name null when the organisation service could not be asked; the workspace is still
     *     listed, because leaving it out would hide a membership the person really holds
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record WorkspaceView(UUID orgId, String name, String role) {}

    public record UpdateRoleRequest(@NotBlank String roleName) {}

    @GetMapping
    @RequiresPermission(Permission.Codes.MEMBER_READ)
    @Operation(summary = "Members of this workspace and the role each holds")
    public List<MemberView> list() {
        UUID orgId = UUID.fromString(RequestContext.requireOrgId());
        List<Membership> all = memberships.findByOrgIdAndStatus(orgId, "active");

        Map<UUID, User> people = users
                .findByIdIn(all.stream().map(Membership::getUserId).toList())
                .stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<UUID, String> roleNames =
                roles.findAvailableTo(orgId).stream().collect(Collectors.toMap(Role::getId, Role::getName));

        return all.stream()
                .filter(membership -> people.containsKey(membership.getUserId()))
                .map(membership -> {
                    User user = people.get(membership.getUserId());
                    return new MemberView(
                            user.getId(),
                            user.getDisplayName(),
                            user.getEmail(),
                            roleNames.getOrDefault(membership.getRoleId(), "unknown"),
                            membership.getStatus(),
                            membership.getJoinedAt(),
                            user.getLastLoginAt());
                })
                .sorted(Comparator.comparing(MemberView::displayName))
                .toList();
    }

    /*
     * Any signed-in person, not WORKSPACE_READ: an account with no workspace yet - just
     * registered, or removed from its only one - carries no permissions at all, and is exactly the
     * account that most needs to know who it is signed in as. It reveals only the caller's own
     * account.
     */
    @GetMapping("/me")
    @Operation(summary = "The signed-in account")
    public MeView me() {
        Actor actor = requirePerson();
        User user = users.findById(UUID.fromString(actor.id()))
                .orElseThrow(() -> ApiException.notFound("user", actor.id()));
        String role = actor.roleId() == null
                ? null
                : roles.findById(UUID.fromString(actor.roleId()))
                        .map(Role::getName)
                        .orElse(null);
        // Planned codes are left out: a stored role may still carry them, but they grant nothing
        // yet, and "what your role allows" should not list what nothing does.
        return new MeView(
                user.getId(),
                user.getDisplayName(),
                user.getEmail(),
                role,
                actor.permissions().stream()
                        .filter(code -> !Permission.isPlanned(code))
                        .sorted()
                        .toList());
    }

    /**
     * The workspaces the signed-in person belongs to, for choosing which one to open.
     *
     * <p>Like {@link #me()}, open to any signed-in person: it is how an account whose session has
     * no workspace yet finds one. Names come from the organisation service, which owns them; when
     * it cannot be reached the list still comes back, with each name null, so the person is never
     * told they belong nowhere when they do not.
     */
    @GetMapping("/me/workspaces")
    @Operation(summary = "The workspaces the signed-in account belongs to")
    public List<WorkspaceView> myWorkspaces() {
        Actor actor = requirePerson();
        List<Membership> active = memberships.findByUserIdAndStatus(UUID.fromString(actor.id()), "active");
        if (active.isEmpty()) {
            return List.of();
        }

        Map<UUID, String> roleNames = roles.findAllById(active.stream()
                        .map(Membership::getRoleId)
                        .distinct()
                        .toList())
                .stream()
                .collect(Collectors.toMap(Role::getId, Role::getName));
        Map<UUID, String> names = workspaceNames(
                active.stream().map(Membership::getOrgId).distinct().toList(), actor.id());

        return active.stream()
                .map(membership -> new WorkspaceView(
                        membership.getOrgId(),
                        names.get(membership.getOrgId()),
                        roleNames.get(membership.getRoleId())))
                .sorted(Comparator.comparing(
                        WorkspaceView::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    @PutMapping("/{userId}/role")
    @RequiresPermission(Permission.Codes.MEMBER_UPDATE)
    @Transactional
    @Operation(summary = "Change the role a member holds")
    public MemberView updateRole(@PathVariable UUID userId, @Valid @RequestBody UpdateRoleRequest request) {
        Actor actor = RequestContext.requireActor();
        UUID orgId = UUID.fromString(RequestContext.requireOrgId());
        Membership membership = memberships
                .findActive(userId, orgId)
                .orElseThrow(() -> ApiException.notFound("member", userId));

        Role newRole = roles.findAvailableTo(orgId).stream()
                .filter(r -> r.getName().equals(request.roleName()))
                .findFirst()
                .orElseThrow(() -> ApiException.validation("roleName", "no such role is available to this workspace"));

        GrantGuard.Grantor caller = guard.caller(actor);
        // Taking a member's current role away, then giving them the new one: both are decisions
        // about authority, and the caller must be entitled to each.
        guard.assertCanManage(caller, membership);
        guard.assertCanGrant(caller, newRole);
        if (userId.toString().equals(actor.id())) {
            guardOwnRole(membership, newRole);
        }
        guardLastOwner(orgId, membership, newRole.getId());

        UUID previousRoleId = membership.getRoleId();
        String previousRoleName =
                roles.findById(previousRoleId).map(Role::getName).orElse(null);
        membership.setRoleId(newRole.getId());
        memberships.save(membership);
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("fromRole", previousRoleName);
        change.put("toRole", newRole.getName());
        change.put("fromRoleId", previousRoleId.toString());
        change.put("toRoleId", newRole.getId().toString());
        audit.record("member.role_change", "member", userId.toString(), "succeeded", change);
        log.info(
                "Role of user {} in workspace {} changed from {} to {} by {}",
                userId,
                orgId,
                previousRoleId,
                newRole.getName(),
                actor.id());

        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("user", userId));
        return new MemberView(
                user.getId(),
                user.getDisplayName(),
                user.getEmail(),
                newRole.getName(),
                membership.getStatus(),
                membership.getJoinedAt(),
                user.getLastLoginAt());
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.Codes.MEMBER_REMOVE)
    @Transactional
    @Operation(summary = "Remove a member from the workspace")
    public void remove(@PathVariable UUID userId) {
        Actor actor = RequestContext.requireActor();
        UUID orgId = UUID.fromString(RequestContext.requireOrgId());
        Membership membership = memberships
                .findByUserIdAndOrgId(userId, orgId)
                .orElseThrow(() -> ApiException.notFound("member", userId));
        if (!membership.isActive()) {
            // Already gone. Saying so again is not an error, and must not pause anything twice.
            return;
        }

        guard.assertCanManage(guard.caller(actor), membership);
        guardLastOwner(orgId, membership, null);

        // Kept, never deleted: this is the historical fact that the person was once a member,
        // and an audit-conscious platform does not let removal erase that it happened. It is also
        // the row an invitation reactivates if they are asked back.
        membership.setStatus("removed");
        memberships.save(membership);
        Map<String, Object> removed = new LinkedHashMap<>();
        removed.put("role", roles.findById(membership.getRoleId()).map(Role::getName).orElse(null));
        audit.record("member.remove", "member", userId.toString(), "succeeded", removed);
        log.info("User {} removed from workspace {} by {}", userId, orgId, actor.id());

        // Their schedules would otherwise keep running in their name. Only once the removal has
        // committed, and never at the cost of the removal itself.
        orchestrator.memberLeftAfterCommit(orgId, userId, actor.id());
    }

    /**
     * Refuses a change to one's own role unless it only narrows it.
     *
     * <p>The subset rule already stops most of this; the explicit check also covers a token minted
     * before a widening, and says plainly why the change was refused.
     */
    private void guardOwnRole(Membership membership, Role newRole) {
        Role current = roles.findById(membership.getRoleId()).orElse(null);
        if (current != null && !newRole.isWithin(current.getPermissions())) {
            throw new ApiException(
                            ErrorCode.PERMISSION_DENIED,
                            "You cannot give yourself more permissions than your current role has.")
                    .with("reason", GrantGuard.EXCEEDS_GRANTOR);
        }
    }

    /** A signed-in person, never a service or a machine key acting through these endpoints. */
    private static Actor requirePerson() {
        Actor actor = RequestContext.requireActor();
        if (actor.kind() != Actor.Kind.USER) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED, "This is only available to a signed-in person.");
        }
        return actor;
    }

    /**
     * Workspace names by id, from the organisation service's internal endpoint.
     *
     * <p>Asked in parallel, each with its own timeout. A workspace whose name could not be read is
     * simply missing from the map, and its entry is shown without a name.
     */
    private Map<UUID, String> workspaceNames(List<UUID> orgIds, String personId) {
        try {
            Map<UUID, String> names = Flux.fromIterable(orgIds)
                    .flatMap(orgId -> workspaceName(orgId, personId)
                            .map(name -> Map.entry(orgId, name))
                            .onErrorResume(failure -> {
                                log.warn("Could not read the name of workspace {}: {}", orgId, failure.toString());
                                return Mono.empty();
                            }))
                    .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                    .block(WORKSPACE_LOOKUP_TIMEOUT.plusSeconds(1));
            return names == null ? Map.of() : names;
        } catch (RuntimeException e) {
            log.warn("Could not read workspace names: {}", e.toString());
            return Map.of();
        }
    }

    private Mono<String> workspaceName(UUID orgId, String personId) {
        return Mono.fromCallable(() -> serviceToken(orgId, personId))
                .flatMap(token -> organisationClient
                        .get()
                        .uri("/internal/workspaces/{id}", orgId)
                        .header("Authorization", "Bearer " + token)
                        .retrieve()
                        .bodyToMono(JSON_OBJECT))
                .timeout(WORKSPACE_LOOKUP_TIMEOUT)
                .flatMap(body -> body.get("name") instanceof String name ? Mono.just(name) : Mono.empty());
    }

    /** A service token for the organisation service, naming the person it is asked for. */
    private String serviceToken(UUID orgId, String personId) {
        Actor service = new Actor(
                "identity", Actor.Kind.SYSTEM, orgId.toString(), null, Set.of(), 0L, personId, null, null, Map.of());
        return tokens.issueInternalToken("organisation", service).token();
    }

    /**
     * Refuses to move a workspace's last active owner away from the owner role, whether by
     * changing their role or removing them outright.
     *
     * <p>{@code targetRoleId} is the role a role-change would move the member to; {@code null}
     * for a removal, which always leaves the owner role. Best-effort: if the {@code owner} system
     * role has not been seeded, the guard is skipped rather than blocking every workspace on a
     * lookup that will never succeed.
     */
    private void guardLastOwner(UUID orgId, Membership membership, UUID targetRoleId) {
        Role ownerRole = roles.findSystemRole("owner").orElse(null);
        if (ownerRole == null || !ownerRole.getId().equals(membership.getRoleId())) {
            return;
        }
        if (ownerRole.getId().equals(targetRoleId)) {
            return;
        }
        if (memberships.countActiveWithRole(orgId, ownerRole.getId()) <= 1) {
            throw new ApiException(ErrorCode.LAST_OWNER_PROTECTED);
        }
    }
}
