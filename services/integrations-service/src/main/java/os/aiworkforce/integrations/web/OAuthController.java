// @find: oauth controller, oauth callback, GET /api/oauth/callback, redirect after sign in, browser nonce cookie, connect with google microsoft salesforce, return to console
// @what: Endpoint the provider redirects to after sign-in; completes the connection and returns the browser to the console.
// @flow: Calls OAuthService.callback
package os.aiworkforce.integrations.web;

import java.net.URI;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.integrations.oauth.OAuthProperties;
import os.aiworkforce.integrations.oauth.OAuthService;

/**
 * Where a provider sends the browser after the consent screen.
 *
 * <p>Open to the network on purpose: the provider's redirect carries no bearer token. It is not an
 * open door. The request is honoured only with a state this service signed, that has not expired
 * or been used, and a cookie set in the browser that started the sign-in. The reply is always a
 * redirect back to the console, with a plain message when something went wrong.
 */
@RestController
@Tag(name = "Integrations")
public class OAuthController {

    /** Name of the cookie that binds a sign-in to the browser that started it. Set by {@code IntegrationController.oauthStart} and cleared by {@link #callback}. */
    public static final String COOKIE = "aiwos_oauth";

    /** The service that validates the callback and stores the tokens. */
    private final OAuthService oauth;

    /** Creates the controller with the OAuth service it delegates to. */
    public OAuthController(OAuthService oauth) {
        this.oauth = oauth;
    }

    // @find: oauth callback endpoint, GET /api/oauth/callback
    /**
     * Handles {@code GET /api/oauth/callback}, the address each provider redirects the browser to.
     *
     * <p>Permission: none, because the redirect carries no bearer token; trust comes from the signed
     * state and the browser cookie, which {@code OAuthService.callback} verifies. Flow: delegates to
     * that method (which exchanges the code, stores the encrypted tokens and writes an audit entry
     * on success), then clears the cookie.
     *
     * <p>Response: always {@code 302} to the console address the service returned, a success or an
     * error page, with {@code no-store} caching and no referrer. It does not throw for a bad sign-in.
     *
     * @param code the authorization code, absent when the provider reported an error
     * @param state the signed state issued by {@code start}
     * @param error the provider's error code, for example when the person declined
     * @param nonce the browser-binding cookie value, absent when the cookie was not sent
     */
    @GetMapping(OAuthProperties.CALLBACK_PATH)
    @Operation(summary = "OAuth redirect target: exchanges the code and returns the browser to the console")
    public ResponseEntity<Void> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @CookieValue(name = COOKIE, required = false) String nonce) {
        String destination = oauth.callback(code, state, error, nonce);
        // The cookie did its job whatever the outcome; it is not left behind.
        ResponseCookie clear = ResponseCookie.from(COOKIE, "")
                .httpOnly(true)
                .secure(oauth.properties().baseUrl().startsWith("https://"))
                .sameSite("Lax")
                .path(OAuthProperties.CALLBACK_PATH)
                .maxAge(0)
                .build();
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(destination))
                .header(HttpHeaders.SET_COOKIE, clear.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Referrer-Policy", "no-referrer")
                .build();
    }
}
