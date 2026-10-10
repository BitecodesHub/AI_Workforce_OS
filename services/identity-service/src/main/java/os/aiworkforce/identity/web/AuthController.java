// @find: auth api, sign in, login, register, sign up, refresh, sign out, logout, password reset, /api/auth, refresh cookie, Sign in page, AuthController, authentication
// @what: REST endpoints for register, sign-in, refresh, sign-out and password reset redemption (no token needed).
// @flow: Calls AuthService and PasswordResetService; used by the web Sign in page.
package os.aiworkforce.identity.web;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

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
import os.aiworkforce.identity.service.PasswordResetService;
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

    private static final Pattern IPV4 =
            Pattern.compile("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");

    private final AuthService auth;
    private final PasswordResetService resets;
    private final PlatformProperties properties;

    public AuthController(AuthService auth, PasswordResetService resets, PlatformProperties properties) {
        this.auth = auth;
        this.resets = resets;
        this.properties = properties;
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(max = 120) String displayName,
            @NotBlank @Size(min = 12, max = 256) String password) {}

    public record SignInRequest(@NotBlank @Email String email, @NotBlank String password, UUID workspaceId) {}

    /** A reset link's token, and the password the person chose. Same length rule as registering. */
    public record PasswordResetRequest(
            @NotBlank @Size(max = 128) String token, @NotBlank @Size(min = 12, max = 256) String newPassword) {}

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

    // @find: register, sign up, POST /api/auth/register
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

    // @find: sign in, login, POST /api/auth/sign-in
    @PostMapping("/sign-in")
    @Operation(summary = "Sign in and start a session")
    public ResponseEntity<SessionResponse> signIn(@Valid @RequestBody SignInRequest request, HttpServletRequest http) {
        AuthService.AuthResult result = auth.signIn(
                request.email(),
                request.password(),
                request.workspaceId(),
                http.getHeader(HttpHeaders.USER_AGENT),
                clientAddress(http));
        return respond(result);
    }

    // @find: refresh session, POST /api/auth/refresh
    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token for a new session")
    public ResponseEntity<SessionResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String cookieToken,
            @RequestParam(required = false) UUID workspaceId,
            HttpServletRequest http) {
        if (cookieToken == null || cookieToken.isBlank()) {
            throw new ApiException(ErrorCode.NOT_AUTHENTICATED);
        }
        AuthService.AuthResult result =
                auth.refresh(cookieToken, workspaceId, http.getHeader(HttpHeaders.USER_AGENT), clientAddress(http));
        return respond(result);
    }

    // @find: sign out, logout, POST /api/auth/sign-out
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

    // @find: redeem password reset link, POST /api/auth/password-reset
    /**
     * Sets a new password from a reset link an administrator created.
     *
     * <p>Open, like sign-in: the person has no session, which is the point. The link's own token
     * is the credential, checked as a hash, single use and short-lived. Every session the account
     * had is revoked, so the refresh cookie in this browser, if any, is cleared with it.
     */
    @PostMapping("/password-reset")
    @Operation(summary = "Choose a new password with a reset link")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody PasswordResetRequest request) {
        resets.redeem(request.token(), request.newPassword());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearedCookie().toString())
                .build();
    }

    /**
     * Where the request came from, for the session list, or null when that cannot be told.
     *
     * <p>The first X-Forwarded-For entry when a proxy set one, which is the browser's own address
     * behind the gateway and the web server; otherwise the connection's address. It is shown to
     * the person and decides nothing, so a client that writes its own header only mislabels its
     * own session. Anything that is not an address literal is dropped, because the column is INET
     * and a malformed value would fail the sign-in itself.
     */
    static String clientAddress(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        String candidate = forwarded != null && !forwarded.isBlank()
                ? forwarded.split(",", 2)[0].strip()
                : http.getRemoteAddr();
        return addressLiteral(candidate);
    }

    static String addressLiteral(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String candidate = value.strip();
        if (candidate.startsWith("[") && candidate.endsWith("]")) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        int zone = candidate.indexOf('%');
        if (zone >= 0) {
            candidate = candidate.substring(0, zone);
        }
        boolean v4 = IPV4.matcher(candidate).matches();
        // Only something shaped like an IPv6 literal reaches getByName, which parses a literal
        // without any lookup; a host name never gets that far.
        boolean v6 = !v4 && candidate.indexOf(':') >= 0 && IPV6.matcher(candidate).matches();
        if (!v4 && !v6) {
            return null;
        }
        try {
            return InetAddress.getByName(candidate).getHostAddress();
        } catch (UnknownHostException e) {
            return null;
        }
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
