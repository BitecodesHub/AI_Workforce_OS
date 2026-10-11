// @find: oauth properties, oauth callback path, /api/oauth/callback, redirect uri, base url, state secret, integrations oauth settings, aiwos.integrations.oauth
// @what: Configuration for the OAuth flow, including the redirect address providers send people back to.
// @flow: Read by OAuthService and OAuthController
package os.aiworkforce.integrations.oauth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for signing in to providers.
 *
 * @param publicBaseUrl the address people reach the console at; the redirect address registered
 *     with each provider is this plus {@code /api/oauth/callback}. Set with AIWOS_PUBLIC_BASE_URL.
 * @param stateTtl how long a consent screen stays valid before its state is refused
 * @param refreshMargin how long before expiry a token is refreshed
 */
@ConfigurationProperties(prefix = "aiwos.integrations.oauth")
public record OAuthProperties(
        @DefaultValue("http://localhost:5173") String publicBaseUrl,
        @DefaultValue("10m") Duration stateTtl,
        @DefaultValue("2m") Duration refreshMargin) {

    /**
     * The path providers redirect to after consent. It is served by {@code OAuthController} and is
     * also the path the browser-binding cookie is restricted to.
     */
    public static final String CALLBACK_PATH = "/api/oauth/callback";

    /**
     * The public base address with surrounding whitespace and any trailing slashes removed.
     *
     * @return the cleaned base address, or an empty string when none is configured
     */
    public String baseUrl() {
        String base = publicBaseUrl == null ? "" : publicBaseUrl.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    /**
     * The redirect address to register with each provider and to send in every authorization and token
     * request: the cleaned base address plus {@link #CALLBACK_PATH}.
     *
     * @return the full redirect address
     */
    public String redirectUri() {
        return baseUrl() + CALLBACK_PATH;
    }
}
