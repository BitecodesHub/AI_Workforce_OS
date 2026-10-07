package os.aiworkforce.identity.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.identity.service.AuthService;
import os.aiworkforce.identity.service.PasswordResetService;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;
import os.aiworkforce.platform.web.audit.AuditClient;

/**
 * A person's own password and signed-in devices, and the reset link an administrator can create.
 *
 * <p>The {@code /me} endpoints carry no permission code on purpose: changing one's own password or
 * signing out one's own devices must work in any role, and with no workspace selected at all.
 * They do require a person's token. A service or agent token names a person in its subject too,
 * and without that check it could sign that person out everywhere or try their password.
 *
 * <p>Creating a reset link is written to the audit log, naming the administrator and the person it
 * was made for, in the transaction that stores the link. The link itself is never recorded.
 */
@RestController
@RequestMapping("/api/users")
@Tag(name = "Account security")
public class PasswordController {

    private final AuthService auth;
    private final PasswordResetService resets;
    private final AuditClient audit;

    public PasswordController(AuthService auth, PasswordResetService resets, AuditClient audit) {
        this.auth = auth;
        this.resets = resets;
        this.audit = audit;
    }

    public record ChangePasswordRequest(
            @NotBlank @Size(max = 256) String currentPassword, @NotBlank @Size(min = 12, max = 256) String newPassword) {}

    /**
     * @param url where the person chooses a new password, relative to the console's address
     * @param expiresAt when the link stops working
     */
    public record ResetLinkResponse(String url, Instant expiresAt) {}

    @GetMapping("/me/sessions")
    @Operation(summary = "The devices the signed-in account is signed in on")
    public List<AuthService.SessionSummary> sessions() {
        Actor person = requirePerson();
        return auth.listSessions(UUID.fromString(person.id()), person.sessionId());
    }

    @DeleteMapping("/me/sessions/{familyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Sign one device out")
    public void endSession(@PathVariable UUID familyId) {
        Actor person = requirePerson();
        auth.endSession(UUID.fromString(person.id()), familyId);
    }

    @PutMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Change the signed-in account's password and sign out its other devices")
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        Actor person = requirePerson();
        auth.changePassword(
                UUID.fromString(person.id()), person.sessionId(), request.currentPassword(), request.newPassword());
    }

    @PostMapping("/{userId}/password-reset-link")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.Codes.MEMBER_UPDATE)
    @Transactional
    @Operation(summary = "Create a one-time link a member can use to choose a new password")
    public ResetLinkResponse createResetLink(@PathVariable UUID userId) {
        PasswordResetService.ResetLink link = resets.createLink(RequestContext.requireActor(), userId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("expiresAt", link.expiresAt().toString());
        audit.record("auth.password_reset_link.create", "user", userId.toString(), "succeeded", detail);
        return new ResetLinkResponse(link.url(), link.expiresAt());
    }

    private static Actor requirePerson() {
        Actor actor = RequestContext.requireActor();
        if (actor.kind() != Actor.Kind.USER) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED, "Only a person can manage their own sign-in.");
        }
        return actor;
    }
}
