package os.aiworkforce.identity.web;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.identity.domain.Membership;
import os.aiworkforce.identity.domain.Role;
import os.aiworkforce.identity.repository.Memberships;
import os.aiworkforce.identity.repository.Roles;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * Creates the first membership in a newly created workspace.
 *
 * <p>Organisations live in the organisation service; memberships and roles live here. Creating a
 * workspace therefore spans two services: the organisation service creates the organisation row,
 * then calls here to grant the person who created it the system {@code owner} role.
 *
 * <p>Reachable only with a service token minted through {@code /internal/tokens}, the same as
 * every other internal endpoint - never with a person's own token, whatever permissions it
 * carries. Granting a role is exactly the kind of action that must not be reachable by presenting
 * one's own credentials with a crafted body.
 *
 * <p>Idempotent: calling it twice for the same user and organisation returns the existing
 * membership rather than creating a second one or failing. The organisation service may retry a
 * timed-out call, and a retry must not produce two owners where one was asked for.
 */
@RestController
@RequestMapping("/internal/memberships")
@Tag(name = "Internal")
public class InternalMembershipController {

    private static final Logger log = LoggerFactory.getLogger(InternalMembershipController.class);

    private final Memberships memberships;
    private final Roles roles;

    public InternalMembershipController(Memberships memberships, Roles roles) {
        this.memberships = memberships;
        this.roles = roles;
    }

    public record BootstrapOwnerRequest(@NotNull UUID orgId, @NotNull UUID userId) {}

    public record BootstrapMemberRequest(
            @NotNull UUID orgId, @NotNull UUID userId, @jakarta.validation.constraints.NotBlank String roleName) {}

    public record MembershipResponse(UUID membershipId, UUID orgId, UUID userId, String role) {}

    @PostMapping("/bootstrap-owner")
    @Operation(summary = "Internal: grant the owner role for a workspace that was just created")
    @Transactional
    public MembershipResponse bootstrapOwner(@Valid @RequestBody BootstrapOwnerRequest request) {
        Actor actor = RequestContext.requireActor();
        if (actor.kind() == Actor.Kind.USER) {
            // A person's own token, whatever it carries, must never be able to grant a role by
            // calling this directly - only a sibling service acting through the internal token
            // flow may.
            throw new ApiException(ErrorCode.PERMISSION_DENIED, "This endpoint is for internal service calls only.");
        }

        Role ownerRole = roles.findSystemRole("owner")
                .orElseThrow(() -> new ApiException(
                        ErrorCode.INTERNAL_ERROR, "The owner role has not been seeded on this platform."));

        Membership existing = memberships
                .findByUserIdAndOrgId(request.userId(), request.orgId())
                .orElse(null);
        if (existing != null) {
            return new MembershipResponse(existing.getId(), request.orgId(), request.userId(), "owner");
        }

        Membership membership = new Membership();
        membership.setId(UuidV7.generate());
        membership.setUserId(request.userId());
        membership.setOrgId(request.orgId());
        membership.setRoleId(ownerRole.getId());
        membership.setStatus("active");
        membership.setJoinedAt(Instant.now());
        memberships.save(membership);

        log.info("Granted owner in workspace {} to user {}", request.orgId(), request.userId());
        return new MembershipResponse(membership.getId(), request.orgId(), request.userId(), "owner");
    }

    /**
     * Grants a named, non-owner role, for an invitation being accepted.
     *
     * <p>The organisation service creates the invitation and, once a person accepts it and
     * registers their account, calls here to place them in the workspace at the role the
     * invitation named - the same split of responsibility as {@link #bootstrapOwner}, and behind
     * the same internal-service-token gate.
     */
    @PostMapping("/bootstrap-member")
    @Operation(summary = "Internal: grant a named role to a person who accepted an invitation")
    @Transactional
    public MembershipResponse bootstrapMember(@Valid @RequestBody BootstrapMemberRequest request) {
        Actor actor = RequestContext.requireActor();
        if (actor.kind() == Actor.Kind.USER) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED, "This endpoint is for internal service calls only.");
        }

        Role role = roles.findByOrgAndName(request.orgId(), request.roleName())
                .or(() -> roles.findSystemRole(request.roleName()))
                .orElseThrow(() -> ApiException.validation("roleName", "no such role is available to this workspace"));

        Membership existing = memberships
                .findByUserIdAndOrgId(request.userId(), request.orgId())
                .orElse(null);
        if (existing != null) {
            return new MembershipResponse(existing.getId(), request.orgId(), request.userId(), role.getName());
        }

        Membership membership = new Membership();
        membership.setId(UuidV7.generate());
        membership.setUserId(request.userId());
        membership.setOrgId(request.orgId());
        membership.setRoleId(role.getId());
        membership.setStatus("active");
        membership.setJoinedAt(Instant.now());
        memberships.save(membership);

        log.info("Granted {} in workspace {} to user {}", role.getName(), request.orgId(), request.userId());
        return new MembershipResponse(membership.getId(), request.orgId(), request.userId(), role.getName());
    }
}
