// @find: oauth service, connect with google, connect with microsoft, connect with salesforce, sign in, oauth start, oauth callback, save oauth app, PKCE, state, refresh token, access token renewal, reconnect required, gmail, calendar, drive, sheets, outlook, teams, salesforce, GET /api/oauth/callback
// @what: Runs the OAuth sign-in for connectors: saves the app, builds the consent address, handles the callback, stores tokens and renews them.
// @flow: Called by IntegrationController and OAuthController; uses OAuthProviders and OAuthStateCodec
package os.aiworkforce.integrations.oauth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import os.aiworkforce.integrations.domain.Connection;
import os.aiworkforce.integrations.domain.OAuthApp;
import os.aiworkforce.integrations.domain.OAuthState;
import os.aiworkforce.integrations.repository.Connections;
import os.aiworkforce.integrations.repository.OAuthApps;
import os.aiworkforce.integrations.repository.OAuthStates;
import os.aiworkforce.integrations.service.AuditClient;
import os.aiworkforce.mcp.catalog.ConnectorCatalog;
import os.aiworkforce.mcp.live.Hosts;
import os.aiworkforce.mcp.oauth.OAuthProvider;
import os.aiworkforce.mcp.oauth.OAuthProviders;
import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.crypto.EnvelopeEncryptionService;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * Signing in to Google, Microsoft and Salesforce on a workspace's behalf: authorization code with
 * PKCE, tokens held encrypted, refreshed before they expire.
 *
 * <p>Nothing here is returned to a browser except the provider's consent address. The client secret,
 * the access token and the refresh token are only ever decrypted to call the provider, and they are
 * never written to a log or an audit entry: failures are logged by connector and by the provider's
 * short error code only.
 */
@Service
public class OAuthService {

    private static final Logger log = LoggerFactory.getLogger(OAuthService.class);
    private static final Duration PROVIDER_TIMEOUT = Duration.ofSeconds(15);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    public static final String GENERIC_LINK_PROBLEM =
            "That sign-in link is not valid, has expired or was already used. Start again from the Connectors page.";

    /** What the console shows about the saved app. Never the secret. */
    public record AppView(
            boolean configured,
            String provider,
            String clientId,
            boolean clientSecretStored,
            Map<String, String> settings,
            String redirectUri) {}

    /** The consent address, and the value for the cookie that ties the sign-in to this browser. */
    public record Start(String authorizeUrl, String browserNonce, Duration validFor) {}

    /** The tokens as stored (encrypted), and as the provider's token endpoint gave them. */
    record Stored(String accessToken, String refreshToken, Instant expiresAt, String instanceUrl, String scope) {}

    private final Connections connections;
    private final OAuthApps apps;
    private final OAuthStates states;
    private final EnvelopeEncryptionService encryption;
    private final AuditClient audit;
    private final WebClient http;
    private final ObjectMapper json;
    private final OAuthProperties properties;
    private final OAuthStateCodec codec;
    private final ConnectorCatalog catalog;
    private final Map<String, OAuthProvider> providers;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    @Autowired
    public OAuthService(
            Connections connections,
            OAuthApps apps,
            OAuthStates states,
            EnvelopeEncryptionService encryption,
            AuditClient audit,
            WebClient.Builder http,
            ObjectMapper json,
            OAuthProperties properties,
            ConnectorCatalog catalog,
            PlatformProperties platform) {
        this(connections, apps, states, encryption, audit, http, json, properties, catalog,
                platform.security().internalServiceSecret(), OAuthProviders.all());
    }

    /** With the providers' endpoints replaced, for tests that stand in for them. */
    public OAuthService(
            Connections connections,
            OAuthApps apps,
            OAuthStates states,
            EnvelopeEncryptionService encryption,
            AuditClient audit,
            WebClient.Builder http,
            ObjectMapper json,
            OAuthProperties properties,
            ConnectorCatalog catalog,
            String stateSecret,
            Map<String, OAuthProvider> providers) {
        this.connections = connections;
        this.apps = apps;
        this.states = states;
        this.encryption = encryption;
        this.audit = audit;
        this.http = http.clone().build();
        this.json = json;
        this.properties = properties;
        this.catalog = catalog;
        this.codec = new OAuthStateCodec(stateSecret);
        this.providers = Map.copyOf(providers);
    }

    public OAuthProperties properties() {
        return properties;
    }

    // ---- The app --------------------------------------------------------------------------------

    public boolean isOAuth(String server) {
        return OAuthProviders.forServer(server).isPresent();
    }

    // @find: get oauth app status, GET /api/integrations/{server}/oauth/app
    public AppView app(UUID orgId, String server) {
        OAuthProvider provider = providerFor(server);
        Optional<OAuthApp> stored = apps.findByOrgIdAndProvider(orgId, provider.id());
        return view(provider, stored.orElse(null));
    }

    /** The providers this workspace has saved an app for. */
    public java.util.Set<String> configuredProviders(UUID orgId) {
        return apps.findByOrgId(orgId).stream().map(OAuthApp::getProvider).collect(java.util.stream.Collectors.toSet());
    }

    // @find: save oauth app, register client id and secret, set up the app step, PUT /api/integrations/{server}/oauth/app
    /** Saves the app. A blank secret keeps the one already stored, so a setting can change without retyping it. */
    public AppView saveApp(UUID orgId, Actor actor, String server, String clientId, String clientSecret, Map<String, String> settings) {
        OAuthProvider provider = providerFor(server);
        if (clientId == null || clientId.isBlank()) {
            throw ApiException.validation("clientId", "must not be empty");
        }
        if (clientId.length() > 512) {
            throw ApiException.validation("clientId", "is too long");
        }
        Map<String, String> clean;
        try {
            clean = provider.validatedSettings(settings);
        } catch (OAuthProvider.SettingProblem e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage())
                    .with("field", e.field())
                    .with("problem", e.getMessage());
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("settings", e.getMessage());
        }
        OAuthApp app = apps.findByOrgIdAndProvider(orgId, provider.id()).orElse(null);
        boolean blankSecret = clientSecret == null || clientSecret.isBlank();
        if (app == null && blankSecret) {
            throw ApiException.validation("clientSecret", "must not be empty");
        }
        if (!blankSecret && clientSecret.length() > 2048) {
            throw ApiException.validation("clientSecret", "is too long");
        }
        if (app == null) {
            app = new OAuthApp();
            app.setId(UuidV7.generate());
            app.setOrgId(orgId);
            app.setProvider(provider.id());
        }
        app.setClientId(clientId.strip());
        if (!blankSecret) {
            app.setClientSecretRef(encryption.encrypt(orgId.toString(), clientSecret.strip()).serialise());
        }
        app.setSettings(write(clean));
        OAuthApp saved = apps.save(app);
        audit.record(orgId, actor, "integration.oauth_app.save", "integration", provider.id(),
                Map.of("provider", provider.id(), "server", server));
        return view(provider, saved);
    }

    // ---- Start ----------------------------------------------------------------------------------

    // @find: start oauth sign in, build consent url, GET /api/integrations/{server}/oauth/start, connect with Google
    /**
     * Opens a consent screen: stores the PKCE verifier, signs a state for it, and builds the address.
     *
     * @throws ApiException when the workspace has not saved an app for this provider yet
     */
    public Start start(UUID orgId, UUID userId, String server) {
        OAuthProvider provider = providerFor(server);
        OAuthApp app = apps.findByOrgIdAndProvider(orgId, provider.id())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.INTEGRATION_NOT_CONNECTED,
                        "Save the " + provider.label() + " app settings first, then connect."));
        Map<String, String> settings = read(app.getSettings());

        String verifier = random(48);
        String nonce = random(24);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.stateTtl());

        OAuthState row = new OAuthState();
        row.setId(UuidV7.generate());
        row.setOrgId(orgId);
        row.setUserId(userId);
        row.setServer(server);
        row.setVerifierRef(encryption.encrypt(orgId.toString(), verifier).serialise());
        row.setExpiresAt(expiresAt);
        states.save(row);
        // Old rows are cleared in passing, so the table never needs a job of its own.
        try {
            states.deleteExpired(now.minus(Duration.ofDays(1)));
        } catch (RuntimeException e) {
            log.debug("Could not clear expired sign-in states: {}", e.getClass().getSimpleName());
        }

        String state = codec.sign(new OAuthStateCodec.Claims(row.getId(), orgId, userId, server, expiresAt, hash(nonce)));
        UriComponentsBuilder url = UriComponentsBuilder.fromUriString(OAuthProvider.fill(provider.authorizeUrl(), settings))
                .queryParam("response_type", "code")
                .queryParam("client_id", app.getClientId())
                .queryParam("redirect_uri", properties.redirectUri())
                .queryParam("scope", String.join(" ", provider.scopesFor(server)))
                .queryParam("state", state)
                .queryParam("code_challenge", challenge(verifier))
                .queryParam("code_challenge_method", "S256");
        provider.authorizeParams().forEach(url::queryParam);
        return new Start(url.build().encode().toUriString(), nonce, properties.stateTtl());
    }

    // ---- Callback -------------------------------------------------------------------------------

    // @find: oauth callback, exchange code for tokens, store connection, GET /api/oauth/callback
    /**
     * Completes a sign-in from the provider's redirect and says where to send the browser next.
     *
     * <p>Never throws: every outcome is an address on the console, with a plain message when it did
     * not work. Nothing about why a state was refused is revealed beyond one generic sentence.
     */
    public String callback(String code, String state, String providerError, String browserNonce) {
        Optional<OAuthStateCodec.Claims> verified = codec.verify(state, Instant.now());
        if (verified.isEmpty()) {
            return failure(GENERIC_LINK_PROBLEM);
        }
        OAuthStateCodec.Claims claims = verified.get();
        if (browserNonce == null || claims.binding() == null || !MessageDigest.isEqual(
                hash(browserNonce).getBytes(StandardCharsets.UTF_8), claims.binding().getBytes(StandardCharsets.UTF_8))) {
            log.warn("OAuth callback for {} refused: not the browser that started the sign-in", claims.server());
            return failure("Open the sign-in link in the same browser you started it in, then try again.");
        }
        Optional<OAuthState> row = states.findById(claims.stateId());
        if (row.isEmpty()
                || !claims.orgId().equals(row.get().getOrgId())
                || !claims.server().equals(row.get().getServer())
                || !java.util.Objects.equals(claims.userId(), row.get().getUserId())) {
            return failure(GENERIC_LINK_PROBLEM);
        }
        // Claimed before anything else is done with it: a second request with this state gets nothing.
        if (states.claim(claims.stateId(), Instant.now()) != 1) {
            return failure(GENERIC_LINK_PROBLEM);
        }

        String server = claims.server();
        OAuthProvider provider = OAuthProviders.forServer(server).map(p -> providers.get(p.id())).orElse(null);
        if (provider == null) {
            return failure(GENERIC_LINK_PROBLEM);
        }
        String name = name(server);
        if (providerError != null && !providerError.isBlank()) {
            log.info("OAuth sign-in for {} in workspace {} ended at the provider: {}", server, claims.orgId(), safeCode(providerError));
            return failure("access_denied".equals(providerError)
                    ? "You did not approve the request, so " + name + " was not connected."
                    : provider.label() + " stopped the sign-in, so " + name + " was not connected. Try again.");
        }
        if (code == null || code.isBlank() || code.length() > 4096) {
            return failure(provider.label() + " did not send back a sign-in code. Try connecting " + name + " again.");
        }

        try {
            UUID orgId = claims.orgId();
            OAuthApp app = apps.findByOrgIdAndProvider(orgId, provider.id()).orElse(null);
            if (app == null) {
                return failure("The " + provider.label() + " app settings are no longer saved. Save them and try again.");
            }
            Map<String, String> settings = read(app.getSettings());
            String verifier = encryption.decrypt(orgId.toString(), row.get().getVerifierRef());

            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "authorization_code");
            form.put("code", code);
            form.put("redirect_uri", properties.redirectUri());
            form.put("client_id", app.getClientId());
            form.put("client_secret", encryption.decrypt(orgId.toString(), app.getClientSecretRef()));
            form.put("code_verifier", verifier);
            JsonNode answer = tokenCall(provider, settings, form);

            String accessToken = answer.path("access_token").asText("");
            if (accessToken.isBlank()) {
                return failure(provider.label() + " did not return an access token. Try connecting " + name + " again.");
            }
            String refresh = answer.path("refresh_token").asText(null);
            Instant expiresAt = answer.hasNonNull("expires_in")
                    ? Instant.now().plusSeconds(Math.max(60, answer.path("expires_in").asLong(3600)))
                    : null;
            String instanceUrl = null;
            if (OAuthProviders.SALESFORCE.equals(provider.id())) {
                instanceUrl = Hosts.salesforceInstance(answer.path("instance_url").asText(""));
            }
            List<String> requested = provider.checkedScopes(server);
            List<String> granted = granted(provider, server, answer.path("scope").asText(null));
            String scope = String.join(" ", granted);
            Stored stored = new Stored(accessToken, refresh, expiresAt, instanceUrl, scope);
            String label = accountLabel(provider, settings, adapterCredential(server, stored));

            Connection connection = connections.findByOrgIdAndServer(orgId, server).orElseGet(() -> fresh(orgId, server));
            connection.setDisplayName(catalog.find(server).map(info -> info.displayName()).orElse(server));
            connection.connectWithOAuth(
                    encryption.encrypt(orgId.toString(), write(stored)).serialise(),
                    label,
                    claims.userId(),
                    requested,
                    granted,
                    expiresAt);
            connections.save(connection);

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("server", server);
            detail.put("method", "oauth");
            if (label != null) {
                detail.put("accountLabel", label);
            }
            audit.record(orgId, actorFor(claims), "integration.connect", "integration", server, detail);
            log.info("Connected {} in workspace {} through {}", server, orgId, provider.label());
            return success(server);
        } catch (IllegalArgumentException e) {
            return failure(e.getMessage());
        } catch (ProviderRefusal e) {
            return failure(e.getMessage());
        } catch (RuntimeException e) {
            log.warn("OAuth sign-in for {} failed: {}", server, e.getClass().getSimpleName());
            return failure("Something went wrong while connecting " + name + ". Try again.");
        }
    }

    // ---- Using and refreshing tokens -------------------------------------------------------------

    // @find: get current oauth credential, renew when near expiry
    /**
     * The credential an adapter should use for this connection, refreshed first when it is about to
     * expire. When the refresh fails because the provider refused it, the connection is marked as
     * needing a reconnect and the stale token is returned, so the agent's call fails with a plain
     * sentence from the provider rather than quietly falling back to practice data.
     */
    public Optional<String> credentialFor(UUID orgId, Connection connection) {
        if (connection.getCredentialRef() == null || connection.isSandbox()) {
            return Optional.empty();
        }
        String server = connection.getServer();
        // One refresh at a time per connection: the second caller finds the first's result.
        synchronized (locks.computeIfAbsent(orgId + ":" + server, key -> new Object())) {
            Connection current = connections.findByOrgIdAndServer(orgId, server).orElse(connection);
            Stored stored = decrypt(orgId, current);
            if (stored == null) {
                return Optional.empty();
            }
            if (!current.isReconnectRequired() && needsRefresh(stored)) {
                stored = refresh(orgId, current, stored);
            }
            return Optional.of(adapterCredential(server, stored));
        }
    }

    // @find: refresh after provider rejected token, renew access token
    /**
     * A new credential after the provider rejected {@code rejected} with 401, whatever the stored
     * expiry says. Empty when the sign-in cannot be renewed; the connection is then marked as
     * needing a reconnect.
     *
     * <p>If another call has already stored a different token since {@code rejected} was handed
     * out, that newer token is returned without asking the provider again, so a burst of rejected
     * calls costs one refresh, not one each.
     */
    public Optional<String> refreshRejected(UUID orgId, Connection connection, String rejected) {
        String server = connection.getServer();
        synchronized (locks.computeIfAbsent(orgId + ":" + server, key -> new Object())) {
            Connection current = connections.findByOrgIdAndServer(orgId, server).orElse(connection);
            if (current.isSandbox() || current.isReconnectRequired()) {
                return Optional.empty();
            }
            Stored stored = decrypt(orgId, current);
            if (stored == null) {
                return Optional.empty();
            }
            String held = adapterCredential(server, stored);
            if (rejected != null && !rejected.equals(held)) {
                return Optional.of(held);
            }
            Stored renewed = refresh(orgId, current, stored);
            // The same object back means the refresh did not happen: the provider refused it
            // (already marked) or could not be reached. Either way this token is no use.
            if (renewed == stored || current.isReconnectRequired()) {
                if (!current.isReconnectRequired()) {
                    markReconnect(current, stored, provider(server) + " did not renew the sign-in. Connect "
                            + name(server) + " again.");
                }
                return Optional.empty();
            }
            return Optional.of(adapterCredential(server, renewed));
        }
    }

    // @find: flag connection to reconnect
    /** Records that only a new consent will fix this connection. */
    public void requireReconnect(UUID orgId, Connection connection, String reason) {
        synchronized (locks.computeIfAbsent(orgId + ":" + connection.getServer(), key -> new Object())) {
            Connection current =
                    connections.findByOrgIdAndServer(orgId, connection.getServer()).orElse(connection);
            if (current.isSandbox() || current.isReconnectRequired()) {
                return;
            }
            current.requireReconnect(reason);
            connections.save(current);
        }
    }

    private String provider(String server) {
        return OAuthProviders.forServer(server).map(p -> providers.get(p.id()).label()).orElse("The provider");
    }

    private boolean needsRefresh(Stored stored) {
        return stored.expiresAt() != null
                && stored.expiresAt().minus(properties.refreshMargin()).isBefore(Instant.now());
    }

    private Stored refresh(UUID orgId, Connection connection, Stored stored) {
        String server = connection.getServer();
        String name = name(server);
        OAuthProvider provider = OAuthProviders.forServer(server).map(p -> providers.get(p.id())).orElse(null);
        OAuthApp app = provider == null ? null : apps.findByOrgIdAndProvider(orgId, provider.id()).orElse(null);
        if (provider == null || app == null) {
            return markReconnect(connection, stored, "The " + (provider == null ? "" : provider.label() + " ")
                    + "app settings are no longer saved. Save them and connect " + name + " again.");
        }
        if (stored.refreshToken() == null || stored.refreshToken().isBlank()) {
            return markReconnect(connection, stored,
                    provider.label() + " did not give a way to renew the sign-in. Connect " + name + " again.");
        }
        try {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "refresh_token");
            form.put("refresh_token", stored.refreshToken());
            form.put("client_id", app.getClientId());
            form.put("client_secret", encryption.decrypt(orgId.toString(), app.getClientSecretRef()));
            JsonNode answer = tokenCall(provider, read(app.getSettings()), form);
            String accessToken = answer.path("access_token").asText("");
            if (accessToken.isBlank()) {
                return markReconnect(connection, stored,
                        provider.label() + " did not renew the sign-in. Connect " + name + " again.");
            }
            Instant expiresAt = answer.hasNonNull("expires_in")
                    ? Instant.now().plusSeconds(Math.max(60, answer.path("expires_in").asLong(3600)))
                    : null;
            String refreshToken = answer.path("refresh_token").asText(stored.refreshToken());
            String instanceUrl = stored.instanceUrl();
            if (OAuthProviders.SALESFORCE.equals(provider.id()) && answer.hasNonNull("instance_url")) {
                instanceUrl = Hosts.salesforceInstance(answer.path("instance_url").asText(""));
            }
            Stored renewed = new Stored(accessToken, refreshToken, expiresAt, instanceUrl, stored.scope());
            connection.recordRefresh(encryption.encrypt(orgId.toString(), write(renewed)).serialise(), expiresAt);
            connections.save(connection);
            return renewed;
        } catch (ProviderRefusal refusal) {
            // The provider said no: the grant was withdrawn or expired. Only a new consent fixes it.
            return markReconnect(connection, stored,
                    provider.label() + " no longer accepts the saved sign-in. Connect " + name + " again.");
        } catch (RuntimeException e) {
            // Not a refusal (the network, a 5xx): keep the connection and let the call try the current token.
            log.warn("Could not refresh the {} token in workspace {}: {}", server, orgId, e.getClass().getSimpleName());
            return stored;
        }
    }

    private Stored markReconnect(Connection connection, Stored stored, String reason) {
        connection.requireReconnect(reason);
        connections.save(connection);
        return stored;
    }

    /** What an adapter is given: the access token, or for Salesforce the token with its instance address. */
    static String adapterCredential(String server, Stored stored) {
        if ("salesforce".equals(server)) {
            ObjectNode node = new ObjectMapper().createObjectNode();
            node.put("accessToken", stored.accessToken());
            node.put("instanceUrl", stored.instanceUrl());
            return node.toString();
        }
        return stored.accessToken();
    }

    // ---- Provider calls ---------------------------------------------------------------------------

    /** The provider said no, in a way that retrying will not fix. */
    private static final class ProviderRefusal extends RuntimeException {
        ProviderRefusal(String message) {
            super(message, null, false, false);
        }
    }

    private JsonNode tokenCall(OAuthProvider provider, Map<String, String> settings, Map<String, String> form) {
        org.springframework.util.LinkedMultiValueMap<String, String> body = new org.springframework.util.LinkedMultiValueMap<>();
        form.forEach(body::add);
        try {
            JsonNode answer = http.post()
                    .uri(OAuthProvider.fill(provider.tokenUrl(), settings))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromFormData(body))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(PROVIDER_TIMEOUT);
            if (answer == null) {
                throw new ProviderRefusal(provider.label() + " sent an empty answer. Try again.");
            }
            return answer;
        } catch (WebClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status >= 500 || status == 429) {
                throw new IllegalStateException("provider unavailable");
            }
            String code = errorCode(e.getResponseBodyAsString());
            log.info("{} token endpoint refused a request: status {} code {}", provider.label(), status, code);
            throw new ProviderRefusal(refusalMessage(provider, code));
        } catch (WebClientRequestException e) {
            throw new IllegalStateException("provider unreachable");
        }
    }

    private static String refusalMessage(OAuthProvider provider, String code) {
        return switch (code == null ? "" : code) {
            case "invalid_client", "unauthorized_client" ->
                provider.label() + " did not accept the client ID or client secret saved here. Check them against the app registered with "
                        + provider.label() + ", then try again.";
            case "redirect_uri_mismatch", "invalid_request" ->
                provider.label() + " did not accept the request. Check that the redirect address registered with "
                        + provider.label() + " is exactly the one shown under Set up the app.";
            case "invalid_grant" ->
                provider.label() + " did not accept the sign-in code. It may have expired; start again.";
            default -> provider.label() + " did not accept the sign-in. Try connecting again.";
        };
    }

    private String errorCode(String body) {
        try {
            String code = json.readTree(body == null ? "{}" : body).path("error").asText("");
            return safeCode(code);
        } catch (Exception e) {
            return null;
        }
    }

    private static String safeCode(String code) {
        return code != null && code.matches("[A-Za-z0-9_.-]{1,64}") ? code : "unknown";
    }

    private String accountLabel(OAuthProvider provider, Map<String, String> settings, String credential) {
        try {
            String token = credential;
            if (OAuthProviders.SALESFORCE.equals(provider.id())) {
                token = json.readTree(credential).path("accessToken").asText();
            }
            String profileToken = token;
            JsonNode profile = http.get()
                    .uri(OAuthProvider.fill(provider.profileUrl(), settings))
                    .headers(headers -> headers.setBearerAuth(profileToken))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(PROVIDER_TIMEOUT);
            if (profile == null) {
                return null;
            }
            for (String field : List.of("email", "mail", "userPrincipalName", "preferred_username", "name", "displayName")) {
                String value = profile.path(field).asText("");
                if (!value.isBlank()) {
                    return value.length() > 120 ? value.substring(0, 120) : value;
                }
            }
        } catch (Exception e) {
            log.debug("No account label from {}: {}", provider.label(), e.getClass().getSimpleName());
        }
        return null;
    }

    // ---- Helpers ----------------------------------------------------------------------------------

    private OAuthProvider providerFor(String server) {
        return OAuthProviders.forServer(server)
                .map(provider -> providers.get(provider.id()))
                .orElseThrow(() -> ApiException.notFound("oauth connector", server));
    }

    private AppView view(OAuthProvider provider, OAuthApp app) {
        return new AppView(
                app != null,
                provider.id(),
                app == null ? null : app.getClientId(),
                app != null,
                app == null ? Map.of() : read(app.getSettings()),
                properties.redirectUri());
    }

    /** The scopes as approved, spelled the way they were requested so they compare equal. */
    private List<String> granted(OAuthProvider provider, String server, String reported) {
        List<String> requested = provider.scopesFor(server);
        if (reported == null || reported.isBlank()) {
            // Some providers only report scope when it differs from what was asked for.
            return requested;
        }
        List<String> given = new ArrayList<>();
        for (String scope : reported.split("[\\s,]+")) {
            if (scope.isBlank()) {
                continue;
            }
            String match = requested.stream().filter(scope::equalsIgnoreCase).findFirst().orElse(scope);
            if (!given.contains(match)) {
                given.add(match);
            }
        }
        // Salesforce's "full" covers "api".
        if (given.contains("full") && !given.contains("api")) {
            given.add("api");
        }
        return given;
    }

    private Stored decrypt(UUID orgId, Connection connection) {
        try {
            JsonNode node = json.readTree(encryption.decrypt(orgId.toString(), connection.getCredentialRef()));
            return new Stored(
                    node.path("accessToken").asText(""),
                    node.path("refreshToken").asText(null),
                    node.hasNonNull("expiresAt") ? Instant.ofEpochSecond(node.path("expiresAt").asLong()) : null,
                    node.path("instanceUrl").asText(null),
                    node.path("scope").asText(""));
        } catch (Exception e) {
            log.warn("Stored sign-in for {} in workspace {} could not be read", connection.getServer(), orgId);
            return null;
        }
    }

    private String write(Stored stored) {
        ObjectNode node = json.createObjectNode();
        node.put("accessToken", stored.accessToken());
        if (stored.refreshToken() != null) {
            node.put("refreshToken", stored.refreshToken());
        }
        if (stored.expiresAt() != null) {
            node.put("expiresAt", stored.expiresAt().getEpochSecond());
        }
        if (stored.instanceUrl() != null) {
            node.put("instanceUrl", stored.instanceUrl());
        }
        node.put("scope", stored.scope() == null ? "" : stored.scope());
        return node.toString();
    }

    private String write(Map<String, String> values) {
        try {
            return json.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Settings could not be written", e);
        }
    }

    private Map<String, String> read(String text) {
        try {
            Map<String, String> values = new LinkedHashMap<>();
            json.readTree(text == null || text.isBlank() ? "{}" : text)
                    .fields()
                    .forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText()));
            return values;
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private String name(String server) {
        return catalog.find(server).map(info -> info.displayName()).orElse(server);
    }

    private String success(String server) {
        return properties.baseUrl() + "/connectors?connected=" + URLEncoder.encode(server, StandardCharsets.UTF_8);
    }

    private String failure(String message) {
        String shortened = message == null ? "" : message.length() > 240 ? message.substring(0, 240) : message;
        return properties.baseUrl() + "/connectors?error=" + URLEncoder.encode(shortened, StandardCharsets.UTF_8);
    }

    private static Actor actorFor(OAuthStateCodec.Claims claims) {
        return Actor.user(
                claims.userId() == null ? "oauth-callback" : claims.userId().toString(),
                claims.orgId().toString(),
                "",
                java.util.Set.of(),
                0L);
    }

    private static Connection fresh(UUID orgId, String server) {
        Connection connection = new Connection();
        connection.setId(UuidV7.generate());
        connection.setOrgId(orgId);
        connection.setServer(server);
        connection.setDisplayName(server);
        return connection;
    }

    private static String random(int bytes) {
        byte[] data = new byte[bytes];
        RANDOM.nextBytes(data);
        return B64.encodeToString(data);
    }

    static String challenge(String verifier) {
        return B64.encodeToString(sha256(verifier));
    }

    private static String hash(String value) {
        return B64.encodeToString(sha256(value));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
