package os.aiworkforce.identity.web;

import java.time.Instant;
import java.util.Set;
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
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.identity.domain.User;
import os.aiworkforce.identity.service.AuthService;
import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Sign up, sign in, refresh and sign out.
 *
 * <p>These are the only endpoints in the platform reachable without a token, which is why the
 * gateway gives them their own, much tighter, rate limit.
 *
 * <p>The refresh token is returned in an HttpOnly, SameSite=Strict cookie rather than in the body.
 * A token in the body has to be stored by the client, and every place a browser can store one is a
 * place a cross-site script can read one. The access token does go in the body, because it is
 * short-lived and the client must attach it to each request itself.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
public class AuthController {

    private static final String REFRESH_COOKIE = "aiwos_refresh";

    private final AuthService auth;
    private final PlatformProperties properties;

    public AuthController(AuthService auth, PlatformProperties properties) {
        this.auth = auth;
        this.properties = properties;
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(max = 120) String displayName,
            @NotBlank @Size(min = 12, max = 256) String password) {}

    public record SignInRequest(@NotBlank @Email String email, @NotBlank String password, UUID workspaceId) {}

    /**
     * @param accessToken bearer token for subsequent requests
     * @param expiresAt when to refresh, so a client renews ahead of a failure rather than after
     * @param userId the signed-in account
     * @param workspaceId the workspace in scope; null means the client must choose one
     * @param permissions what this session may do, so the interface hides what it cannot
     */
    public record SessionResponse(
            String accessToken,
            Instant expiresAt,
            UUID userId,
            UUID workspaceId,
            Set<String> permissions,
            String displayName,
            String email,
            String role) {}

    @PostMapping("/register")
    @Operation(summary = "Create an account")
    public ResponseEntity<Void> register(@Valid @RequestBody RegisterRequest request) {
        User user = auth.register(request.email(), request.displayName(), request.password());
        // No session is issued here. Registering and signing in are separate steps so that email
        // verification, when enabled, sits between them.
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("X-User-Id", user.getId().toString())
                .build();
    }

    @PostMapping("/sign-in")
    @Operation(summary = "Sign in and start a session")
    public ResponseEntity<SessionResponse> signIn(@Valid @RequestBody SignInRequest request, HttpServletRequest http) {
        AuthService.AuthResult result = auth.signIn(
                request.email(), request.password(), request.workspaceId(), http.getHeader(HttpHeaders.USER_AGENT));
        return respond(result);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token for a new session")
    public ResponseEntity<SessionResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String cookieToken,
            @RequestParam(required = false) UUID workspaceId,
            HttpServletRequest http) {
        if (cookieToken == null || cookieToken.isBlank()) {
            throw new ApiException(ErrorCode.NOT_AUTHENTICATED);
        }
        AuthService.AuthResult result = auth.refresh(cookieToken, workspaceId, http.getHeader(HttpHeaders.USER_AGENT));
        return respond(result);
    }

    @PostMapping("/sign-out")
    @Operation(summary = "End the session")
    public ResponseEntity<Void> signOut(
            @CookieValue(name = REFRESH_COOKIE, required = false) String cookieToken,
            @RequestParam(defaultValue = "false") boolean allDevices) {
        if (cookieToken != null && !cookieToken.isBlank()) {
            auth.signOut(cookieToken, allDevices);
        }
        // The cookie is cleared even when no session was found, so a stale cookie cannot keep
        // producing failed refreshes.
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearedCookie().toString())
                .build();
    }

    private ResponseEntity<SessionResponse> respond(AuthService.AuthResult result) {
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        refreshCookie(result.refreshToken()).toString())
                .body(new SessionResponse(
                        result.accessToken(),
                        result.expiresAt(),
                        result.userId(),
                        result.orgId(),
                        result.permissions(),
                        result.displayName(),
                        result.email(),
                        result.roleName()));
    }

    private ResponseCookie refreshCookie(String token) {
        return ResponseCookie.from(REFRESH_COOKIE, token)
                .httpOnly(true)
                // Secure is disabled only where there is no HTTPS to be had, which is local
                // development; a deployed environment always sets it.
                .secure(properties.environment().isDeployed())
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(properties.security().refreshTokenTtl())
                .build();
    }

    private ResponseCookie clearedCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(properties.environment().isDeployed())
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(0)
                .build();
    }
}
