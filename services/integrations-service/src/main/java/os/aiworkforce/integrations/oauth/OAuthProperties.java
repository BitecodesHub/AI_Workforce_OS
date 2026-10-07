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

    public static final String CALLBACK_PATH = "/api/oauth/callback";

    public String baseUrl() {
        String base = publicBaseUrl == null ? "" : publicBaseUrl.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    public String redirectUri() {
        return baseUrl() + CALLBACK_PATH;
    }
}
