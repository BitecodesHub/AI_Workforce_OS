// @find: invitation endpoints, POST /api/orgs/{orgId}/invitations, GET /api/orgs/{orgId}/invitations, DELETE invitations, POST /api/invitations/accept, accept-signed-in, invite teammate, Team page, Accept invite page, invite link
// @what: REST endpoints for inviting, listing, revoking and accepting workspace invitations.
// @flow: Delegates to InvitationService; the accept-invite page in the web app calls the accept endpoints.
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

    /** Business logic for creating, listing, revoking and accepting invitations. */
    private final InvitationService invitations;

    /** Creates the controller with its service. */
    public InvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    /**
     * Request body for inviting someone.
     *
     * @param email the invitee's address
     * @param roleName the role the invitee receives on joining
     */
    public record CreateInvitationRequest(
            @NotBlank @Email @Size(max = 320) String email, @NotBlank @Size(max = 60) String roleName) {}

    /**
     * Request body for accepting an invitation as a new person.
     *
     * @param token the raw token from the invitation link
     * @param displayName the name for the new account
     * @param password the password for the new account, at least twelve characters
     */
    public record AcceptInvitationRequest(
            @NotBlank String token,
            @NotBlank @Size(max = 120) String displayName,
            @NotBlank @Size(min = 12, max = 256) String password) {}

    /**
     * Request body for accepting an invitation with an existing account; the token comes from the
     * invitation link.
     */
    public record AcceptSignedInRequest(@NotBlank String token) {}

    // @find: create invitation, invite member, POST /api/orgs/{orgId}/invitations
    /**
     * {@code POST /api/orgs/{orgId}/invitations}: invites a person to the workspace.
     *
     * <p>Permission: {@code MEMBER_INVITE}. The path workspace must equal the token's workspace.
     * Response: 201 with the invitation view, which includes the one-time token and the accept link built
     * from the request's origin; nothing is emailed, so the administrator passes the link on. Side effects:
     * asks identity whether the role may be given, replaces any open invitation to the same address, stores
      * the new invitation (hash only) and records an audit event, all in {@code
      * InvitationService.create}. Failure: 403 {@code ORGANISATION_MISMATCH}; 422 for invalid input.
     */
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

    // @find: list invitations, pending invites, GET /api/orgs/{orgId}/invitations
    /**
     * {@code GET /api/orgs/{orgId}/invitations}: lists the workspace's invitations, newest first.
     *
     * <p>Permission: {@code MEMBER_INVITE}. Response: 200 with the views. Failure: 403
     * {@code ORGANISATION_MISMATCH} when the path workspace differs from the token's. Read-only.
     */
    @GetMapping("/api/orgs/{orgId}/invitations")
    @RequiresPermission(Permission.Codes.MEMBER_INVITE)
    @Operation(summary = "Invitations pending for this workspace")
    public List<InvitationService.InvitationView> list(@PathVariable UUID orgId) {
        requireSameOrg(orgId);
        return invitations.list(orgId);
    }

    // @find: revoke invitation, withdraw invite, DELETE /api/orgs/{orgId}/invitations/{invitationId}
    /**
     * {@code DELETE /api/orgs/{orgId}/invitations/{invitationId}}: withdraws an invitation so its link
     * stops working.
     *
     * <p>Permission: {@code MEMBER_INVITE}. Response: 200 with the revoked invitation's view (the row is
     * kept). Failure: 403 for a workspace mismatch; 404 when the invitation is not in this workspace.
     */
    @DeleteMapping("/api/orgs/{orgId}/invitations/{invitationId}")
    @RequiresPermission(Permission.Codes.MEMBER_INVITE)
    @Operation(summary = "Withdraw an invitation, so its link stops working")
    public InvitationService.InvitationView revoke(@PathVariable UUID orgId, @PathVariable UUID invitationId) {
        requireSameOrg(orgId);
        return invitations.revoke(orgId, invitationId);
    }

    // @find: accept invitation with new account, POST /api/invitations/accept
    /**
     * {@code POST /api/invitations/accept}: accepts an invitation by creating a new account.
     *
     * <p>Public path: no token is needed, because the invitation token is the credential. Response: 200
     * with the accept result (see {@code InvitationService.AcceptResult}). Side effects and failures
     * (unknown, expired, revoked or used token, weak password) are those of {@code InvitationService.accept}.
     */
    @PostMapping("/api/invitations/accept")
    @Operation(summary = "Accept an invitation and become a member")
    public InvitationService.AcceptResult accept(@Valid @RequestBody AcceptInvitationRequest request) {
        return invitations.accept(request.token(), request.displayName(), request.password());
    }

    // @find: accept invitation when signed in, POST /api/invitations/accept-signed-in
    /**
     * {@code POST /api/invitations/accept-signed-in}: accepts an invitation with an existing account.
     *
     * <p>Requires a valid token but no permission, since the person has no role yet. The raw
     * {@code Authorization} header is passed to the service, which uses it to identify the account and
     * check that the invitation was sent to the same address. Response: 200 with the accept result.
     */
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
