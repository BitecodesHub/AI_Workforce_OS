package os.aiworkforce.organisation.web;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.organisation.service.InvitationService;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * Inviting people to a workspace, and the endpoints a person who is not yet in it can reach.
 *
 * <p>Creating, listing and withdrawing invitations are ordinary, permission-gated,
 * workspace-scoped writes. Accepting one is not: it is reachable by a brand-new person who holds
 * no token at all, which is why it is carved out in {@code ResourceServerConfig}'s public-path
 * list the same way {@code /api/auth/register} is, and why it lives at a path of its own rather
 * than nested under {@code /api/orgs/{orgId}} - the accepting person does not know, and should
 * not have to guess, which workspace their invitation belongs to.
 *
 * <p>Accepting as somebody already signed in is the other door, for an account that already
 * exists. It sits beside the public one but is not public: the public-path entry is the exact
 * path {@code /api/invitations/accept}, and this one needs a valid token like any other request.
 * It carries no permission check, because the person has no role in the workspace yet - the
 * invitation, matched to their own address, is the authority.
 */
@RestController
@Tag(name = "Invitations")
public class InvitationController {

    private final InvitationService invitations;

    public InvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    public record CreateInvitationRequest(
            @NotBlank @Email @Size(max = 320) String email, @NotBlank @Size(max = 60) String roleName) {}

    public record AcceptInvitationRequest(
            @NotBlank String token,
            @NotBlank @Size(max = 120) String displayName,
            @NotBlank @Size(min = 12, max = 256) String password) {}

    public record AcceptSignedInRequest(@NotBlank String token) {}

    @PostMapping("/api/orgs/{orgId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.Codes.MEMBER_INVITE)
    @Operation(summary = "Invite someone to this workspace")
    public InvitationService.InvitationView create(
            @PathVariable UUID orgId, @Valid @RequestBody CreateInvitationRequest request, HttpServletRequest http) {
        requireSameOrg(orgId);
        var actor = RequestContext.requireActor();
        return invitations.create(
                orgId, UUID.fromString(actor.id()), request.email(), request.roleName(), originOf(http));
    }

    @GetMapping("/api/orgs/{orgId}/invitations")
    @RequiresPermission(Permission.Codes.MEMBER_INVITE)
    @Operation(summary = "Invitations pending for this workspace")
    public List<InvitationService.InvitationView> list(@PathVariable UUID orgId) {
        requireSameOrg(orgId);
        return invitations.list(orgId);
    }

    @DeleteMapping("/api/orgs/{orgId}/invitations/{invitationId}")
    @RequiresPermission(Permission.Codes.MEMBER_INVITE)
    @Operation(summary = "Withdraw an invitation, so its link stops working")
    public InvitationService.InvitationView revoke(@PathVariable UUID orgId, @PathVariable UUID invitationId) {
        requireSameOrg(orgId);
        return invitations.revoke(orgId, invitationId);
    }

    @PostMapping("/api/invitations/accept")
    @Operation(summary = "Accept an invitation and become a member")
    public InvitationService.AcceptResult accept(@Valid @RequestBody AcceptInvitationRequest request) {
        return invitations.accept(request.token(), request.displayName(), request.password());
    }

    @PostMapping("/api/invitations/accept-signed-in")
    @Operation(summary = "Accept an invitation with the account already signed in")
    public InvitationService.AcceptResult acceptSignedIn(
            @Valid @RequestBody AcceptSignedInRequest request,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return invitations.acceptSignedIn(request.token(), authorization);
    }

    /** The path names the workspace explicitly so a stale or forged id cannot reach another. */
    private static void requireSameOrg(UUID orgId) {
        UUID tokenOrgId = UUID.fromString(RequestContext.requireOrgId());
        if (!tokenOrgId.equals(orgId)) {
            throw new ApiException(ErrorCode.ORGANISATION_MISMATCH);
        }
    }

    /** Best-effort origin for the accept-invitation link, from the request that created it. */
    private static String originOf(HttpServletRequest http) {
        String forwardedProto = http.getHeader("X-Forwarded-Proto");
        String forwardedHost = http.getHeader("X-Forwarded-Host");
        if (forwardedProto != null && forwardedHost != null) {
            return forwardedProto + "://" + forwardedHost;
        }
        String origin = http.getHeader("Origin");
        return origin != null ? origin : http.getScheme() + "://" + http.getHeader("Host");
    }
}
