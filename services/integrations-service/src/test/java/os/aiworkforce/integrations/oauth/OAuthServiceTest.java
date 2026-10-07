package os.aiworkforce.integrations.oauth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import os.aiworkforce.integrations.domain.Connection;
import os.aiworkforce.integrations.domain.OAuthApp;
import os.aiworkforce.integrations.domain.OAuthState;
import os.aiworkforce.integrations.repository.Connections;
import os.aiworkforce.integrations.repository.OAuthApps;
import os.aiworkforce.integrations.repository.OAuthStates;
import os.aiworkforce.integrations.service.AuditClient;
import os.aiworkforce.integrations.service.TestPropertiesAccess;
import os.aiworkforce.mcp.catalog.ConnectorCatalog;
import os.aiworkforce.mcp.oauth.OAuthProvider;
import os.aiworkforce.mcp.oauth.OAuthProviders;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.crypto.EnvelopeEncryptionService;
import os.aiworkforce.platform.error.ApiException;

/**
 * Signing in, against a stand-in provider: the consent address, the state's protection, PKCE, what is
 * stored, and refreshing. No real Google, Microsoft or Salesforce account is involved.
 */
class OAuthServiceTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-0000000000aa");
    private static final UUID USER = UUID.fromString("00000000-0000-7000-8000-0000000000bb");
    private static final String SECRET = "client-secret-value-123";
    private static final String BASE = "http://localhost:5173";
    private static final String GMAIL = "https://www.googleapis.com/auth/gmail.";

    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Connection> rows = new HashMap<>();
    private final Map<String, OAuthApp> appRows = new HashMap<>();
    private final Map<UUID, OAuthState> stateRows = new HashMap<>();
    private WireMockServer provider;
    private EnvelopeEncryptionService encryption;
    private AuditClient audit;
    private OAuthService service;
    private Duration stateTtl = Duration.ofMinutes(10);

    @BeforeEach
    void setUp() {
        provider = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        provider.start();
        encryption = new EnvelopeEncryptionService(TestPropertiesAccess.properties());
        audit = mock(AuditClient.class);
        service = newService();
    }

    @AfterEach
    void stop() {
        provider.stop();
    }

    private OAuthService newService() {
        Connections connections = mock(Connections.class);
        when(connections.findByOrgIdAndServer(eq(ORG), anyString()))
                .thenAnswer(call -> Optional.ofNullable(rows.get(call.getArgument(1, String.class))));
        when(connections.save(any(Connection.class))).thenAnswer(call -> {
            Connection saved = call.getArgument(0);
            rows.put(saved.getServer(), saved);
            return saved;
        });
        OAuthApps apps = mock(OAuthApps.class);
        when(apps.findByOrgIdAndProvider(eq(ORG), anyString()))
                .thenAnswer(call -> Optional.ofNullable(appRows.get(call.getArgument(1, String.class))));
        when(apps.findByOrgId(ORG)).thenAnswer(call -> List.copyOf(appRows.values()));
        when(apps.save(any(OAuthApp.class))).thenAnswer(call -> {
            OAuthApp saved = call.getArgument(0);
            appRows.put(saved.getProvider(), saved);
            return saved;
        });
        OAuthStates states = mock(OAuthStates.class);
        when(states.save(any(OAuthState.class))).thenAnswer(call -> {
            OAuthState saved = call.getArgument(0);
            stateRows.put(saved.getId(), saved);
            return saved;
        });
        when(states.findById(any(UUID.class)))
                .thenAnswer(call -> Optional.ofNullable(stateRows.get(call.getArgument(0, UUID.class))));
        when(states.claim(any(UUID.class), any(Instant.class))).thenAnswer(call -> {
            OAuthState row = stateRows.get(call.getArgument(0, UUID.class));
            Instant now = call.getArgument(1, Instant.class);
            if (row == null || row.getUsedAt() != null || !row.getExpiresAt().isAfter(now)) {
                return 0;
            }
            row.setUsedAt(now);
            return 1;
        });

        Map<String, OAuthProvider> stubbed = new HashMap<>();
        OAuthProviders.all().forEach((id, real) -> stubbed.put(
                id,
                real.withEndpoints(
                        provider.baseUrl() + "/authorize/{tenant}{loginDomain}",
                        provider.baseUrl() + "/token",
                        provider.baseUrl() + "/profile")));
        return new OAuthService(
                connections,
                apps,
                states,
                encryption,
                audit,
                WebClient.builder(),
                json,
                new OAuthProperties(BASE, stateTtl, Duration.ofMinutes(2)),
                new ConnectorCatalog(),
                "state-secret-for-tests",
                stubbed);
    }

    private void saveGoogleApp() {
        service.saveApp(ORG, Actor.user(USER.toString(), ORG.toString(), "owner", java.util.Set.of(), 0L),
                "gmail", "client-id-1", SECRET, Map.of());
    }

    private static Map<String, String> query(String url) {
        Map<String, String> values = new HashMap<>();
        UriComponentsBuilder.fromUriString(url).build().getQueryParams().forEach((key, list) ->
                values.put(key, URLDecoder.decode(list.get(0), StandardCharsets.UTF_8)));
        return values;
    }

    private static String stateOf(OAuthService.Start start) {
        return query(start.authorizeUrl()).get("state");
    }

    private void tokenAnswers(String body) {
        provider.stubFor(post("/token").willReturn(okJson(body)));
        provider.stubFor(get("/profile").willReturn(okJson("{\"email\":\"admin@acme.example\"}")));
    }

    private static String callbackQuery(String redirect, String name) {
        return query(redirect).get(name);
    }

    // ---- The app ---------------------------------------------------------------------------------

    @Test
    @DisplayName("the saved app never exposes its secret, and a blank secret keeps the stored one")
    void appSecretStaysHidden() throws Exception {
        saveGoogleApp();
        OAuthService.AppView view = service.app(ORG, "calendar");

        assertThat(view.configured()).isTrue();
        assertThat(view.clientId()).isEqualTo("client-id-1");
        assertThat(view.clientSecretStored()).isTrue();
        assertThat(view.redirectUri()).isEqualTo(BASE + "/api/oauth/callback");
        assertThat(json.writeValueAsString(view)).doesNotContain(SECRET);
        String stored = appRows.get("google").getClientSecretRef();
        assertThat(stored).startsWith("v1:").doesNotContain(SECRET);

        service.saveApp(ORG, Actor.SYSTEM, "drive", "client-id-2", "  ", Map.of());

        assertThat(appRows.get("google").getClientSecretRef()).isEqualTo(stored);
        assertThat(appRows.get("google").getClientId()).isEqualTo("client-id-2");
        verify(audit, org.mockito.Mockito.times(2))
                .record(eq(ORG), any(), eq("integration.oauth_app.save"), eq("integration"), eq("google"), any());
    }

    @Test
    @DisplayName("the first save needs a secret, and Microsoft and Salesforce settings are checked")
    void appValidation() {
        assertThatThrownBy(() -> service.saveApp(ORG, Actor.SYSTEM, "gmail", "id", "", Map.of()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.saveApp(ORG, Actor.SYSTEM, "outlook", "id", "s", Map.of()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.saveApp(ORG, Actor.SYSTEM, "outlook", "id", "s", Map.of("tenant", "a/../b")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    // The setting is named, so the form shows the problem beside the tenant box.
                    assertThat(e.details()).containsEntry("field", "tenant");
                    assertThat(e.getMessage()).isEqualTo(e.details().get("problem"));
                });
        assertThatThrownBy(() -> service.saveApp(ORG, Actor.SYSTEM, "salesforce", "id", "s", Map.of("loginDomain", "evil.com")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.saveApp(ORG, Actor.SYSTEM, "github", "id", "s", Map.of()))
                .isInstanceOf(ApiException.class);

        service.saveApp(ORG, Actor.SYSTEM, "outlook", "id", "s", Map.of("tenant", "Acme.onmicrosoft.com"));
        service.saveApp(ORG, Actor.SYSTEM, "salesforce", "id", "s", Map.of("loginDomain", "acme.my.salesforce.com"));
        assertThat(service.app(ORG, "teams").settings()).containsEntry("tenant", "acme.onmicrosoft.com");
        assertThat(service.app(ORG, "salesforce").settings()).containsEntry("loginDomain", "acme.my.salesforce.com");
        assertThat(service.configuredProviders(ORG)).containsExactlyInAnyOrder("microsoft", "salesforce");
    }

    // ---- Start -----------------------------------------------------------------------------------

    @Test
    @DisplayName("starting without a saved app says to set it up first")
    void startNeedsApp() {
        assertThatThrownBy(() -> service.start(ORG, USER, "gmail"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("Save the Google app"));
    }

    @Test
    @DisplayName("the consent address carries the app, the redirect, only this connector's scopes, PKCE and a signed state")
    void startBuildsConsentAddress() {
        saveGoogleApp();

        OAuthService.Start start = service.start(ORG, USER, "gmail");
        Map<String, String> q = query(start.authorizeUrl());

        assertThat(start.authorizeUrl()).startsWith(provider.baseUrl() + "/authorize/");
        assertThat(q).containsEntry("response_type", "code")
                .containsEntry("client_id", "client-id-1")
                .containsEntry("redirect_uri", BASE + "/api/oauth/callback")
                .containsEntry("code_challenge_method", "S256")
                .containsEntry("access_type", "offline");
        assertThat(q.get("scope").split(" "))
                .contains(GMAIL + "readonly", GMAIL + "compose", GMAIL + "send")
                .doesNotContain("https://www.googleapis.com/auth/drive.readonly");
        assertThat(q.get("code_challenge")).isNotBlank().doesNotContain("=");
        assertThat(start.authorizeUrl()).doesNotContain(SECRET);

        OAuthStateCodec codec = new OAuthStateCodec("state-secret-for-tests");
        OAuthStateCodec.Claims claims = codec.verify(q.get("state"), Instant.now()).orElseThrow();
        assertThat(claims.orgId()).isEqualTo(ORG);
        assertThat(claims.userId()).isEqualTo(USER);
        assertThat(claims.server()).isEqualTo("gmail");
        assertThat(claims.expiresAt()).isBetween(Instant.now().plusSeconds(9 * 60), Instant.now().plusSeconds(10 * 60 + 5));
        OAuthState row = stateRows.get(claims.stateId());
        assertThat(row.getVerifierRef()).startsWith("v1:");
        assertThat(row.getUsedAt()).isNull();
    }

    // ---- Callback --------------------------------------------------------------------------------

    @Test
    @DisplayName("a good callback exchanges the code with the PKCE verifier and stores the tokens encrypted")
    void callbackConnects() throws Exception {
        saveGoogleApp();
        OAuthService.Start start = service.start(ORG, USER, "gmail");
        tokenAnswers("{\"access_token\":\"ya29.access-token-value\",\"refresh_token\":\"1//refresh-token-value\","
                + "\"expires_in\":3599,\"scope\":\"openid https://www.googleapis.com/auth/userinfo.email "
                + GMAIL + "readonly " + GMAIL + "compose\"}");

        String redirect = service.callback("auth-code-1", stateOf(start), null, start.browserNonce());

        assertThat(redirect).isEqualTo(BASE + "/connectors?connected=gmail");
        provider.verify(postRequestedFor(urlPathEqualTo("/token"))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(containing("grant_type=authorization_code"))
                .withRequestBody(containing("code=auth-code-1"))
                .withRequestBody(containing("client_id=client-id-1"))
                .withRequestBody(containing("client_secret=" + SECRET))
                .withRequestBody(containing("redirect_uri=http%3A%2F%2Flocalhost%3A5173%2Fapi%2Foauth%2Fcallback")));
        provider.verify(com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlPathEqualTo("/profile"))
                .withHeader("Authorization", equalTo("Bearer ya29.access-token-value")));

        Connection stored = rows.get("gmail");
        assertThat(stored.isSandbox()).isFalse();
        assertThat(stored.getStatus()).isEqualTo("connected");
        assertThat(stored.getAccountLabel()).isEqualTo("admin@acme.example");
        assertThat(stored.getConnectedBy()).isEqualTo(USER);
        assertThat(stored.getTokenExpiresAt()).isAfter(Instant.now().plusSeconds(3000));
        assertThat(stored.getCredentialRef()).startsWith("v1:").doesNotContain("ya29").doesNotContain("refresh-token");
        // The send permission was not approved, and the console says so.
        assertThat(stored.getGrantedScopes()).contains(GMAIL + "readonly", GMAIL + "compose");
        assertThat(stored.missingScopes()).containsExactly(GMAIL + "send");

        String decrypted = encryption.decrypt(ORG.toString(), stored.getCredentialRef());
        assertThat(json.readTree(decrypted).path("refreshToken").asText()).isEqualTo("1//refresh-token-value");

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> detail = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(ORG), any(), eq("integration.connect"), eq("integration"), eq("gmail"), detail.capture());
        assertThat(detail.getValue().toString()).doesNotContain("ya29").doesNotContain("refresh").doesNotContain(SECRET);
    }

    @Test
    @DisplayName("PKCE: the verifier sent to the token endpoint hashes to the challenge in the consent address")
    void pkceVerifierMatchesChallenge() throws Exception {
        saveGoogleApp();
        OAuthService.Start start = service.start(ORG, USER, "gmail");
        tokenAnswers("{\"access_token\":\"a\",\"expires_in\":3600}");

        service.callback("code", stateOf(start), null, start.browserNonce());

        String body = provider.getAllServeEvents().stream()
                .filter(event -> event.getRequest().getUrl().equals("/token"))
                .findFirst()
                .orElseThrow()
                .getRequest()
                .getBodyAsString();
        String verifier = URLDecoder.decode(
                java.util.Arrays.stream(body.split("&"))
                        .filter(pair -> pair.startsWith("code_verifier="))
                        .findFirst()
                        .orElseThrow()
                        .substring("code_verifier=".length()),
                StandardCharsets.UTF_8);
        String challenge = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        assertThat(verifier).hasSizeGreaterThanOrEqualTo(43);
        assertThat(challenge).isEqualTo(query(start.authorizeUrl()).get("code_challenge"));
    }

    @Test
    @DisplayName("a state that was altered is refused before anything is sent to the provider")
    void tamperedStateRefused() {
        saveGoogleApp();
        OAuthService.Start start = service.start(ORG, USER, "gmail");
        String state = stateOf(start);
        int dot = state.indexOf('.');
        String forgedBody = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(state.substring(0, dot)), StandardCharsets.UTF_8)
                        .replace("gmail", "drive")
                        .getBytes(StandardCharsets.UTF_8));
        String badSignature = state.substring(0, state.length() - 2) + (state.endsWith("AA") ? "BB" : "AA");

        for (String forged : new String[] {forgedBody + state.substring(dot), badSignature, "garbage", "", "a.b", state + "x"}) {
            String redirect = service.callback("code", forged, null, start.browserNonce());
            assertThat(redirect).startsWith(BASE + "/connectors?error=");
            assertThat(callbackQuery(redirect, "error")).isEqualTo(OAuthService.GENERIC_LINK_PROBLEM);
        }
        assertThat(callbackQuery(service.callback("code", null, null, start.browserNonce()), "error"))
                .isEqualTo(OAuthService.GENERIC_LINK_PROBLEM);
        assertThat(provider.getAllServeEvents()).isEmpty();
        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("a state older than ten minutes is refused")
    void expiredStateRefused() {
        stateTtl = Duration.ofSeconds(-5);
        service = newService();
        saveGoogleApp();
        OAuthService.Start start = service.start(ORG, USER, "gmail");

        String redirect = service.callback("code", stateOf(start), null, start.browserNonce());

        assertThat(callbackQuery(redirect, "error")).isEqualTo(OAuthService.GENERIC_LINK_PROBLEM);
        assertThat(provider.getAllServeEvents()).isEmpty();
        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("a state works once: the same callback a second time is refused and the provider is asked only once")
    void stateIsSingleUse() {
        saveGoogleApp();
        OAuthService.Start start = service.start(ORG, USER, "gmail");
        tokenAnswers("{\"access_token\":\"a\",\"expires_in\":3600}");
        String state = stateOf(start);

        String first = service.callback("code", state, null, start.browserNonce());
        String second = service.callback("code", state, null, start.browserNonce());

        assertThat(first).isEqualTo(BASE + "/connectors?connected=gmail");
        assertThat(callbackQuery(second, "error")).isEqualTo(OAuthService.GENERIC_LINK_PROBLEM);
        provider.verify(1, postRequestedFor(urlPathEqualTo("/token")));
    }

    @Test
    @DisplayName("a callback from another browser is refused, and does not use up the state")
    void otherBrowserRefused() {
        saveGoogleApp();
        OAuthService.Start start = service.start(ORG, USER, "gmail");
        tokenAnswers("{\"access_token\":\"a\",\"expires_in\":3600}");
        String state = stateOf(start);

        String noCookie = service.callback("code", state, null, null);
        String wrongCookie = service.callback("code", state, null, "someone-elses-cookie");

        assertThat(callbackQuery(noCookie, "error")).contains("same browser");
        assertThat(callbackQuery(wrongCookie, "error")).contains("same browser");
        assertThat(provider.getAllServeEvents()).isEmpty();
        assertThat(service.callback("code", state, null, start.browserNonce()))
                .isEqualTo(BASE + "/connectors?connected=gmail");
    }

    @Test
    @DisplayName("declining at the provider is explained in plain words and connects nothing")
    void declined() {
        saveGoogleApp();
        OAuthService.Start start = service.start(ORG, USER, "gmail");

        String redirect = service.callback(null, stateOf(start), "access_denied", start.browserNonce());

        assertThat(callbackQuery(redirect, "error")).isEqualTo("You did not approve the request, so Gmail was not connected.");
        assertThat(rows).isEmpty();
        assertThat(provider.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("a wrong client secret is explained without repeating it")
    void providerRejectsClient() {
        saveGoogleApp();
        OAuthService.Start start = service.start(ORG, USER, "gmail");
        provider.stubFor(post("/token").willReturn(aResponse().withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"invalid_client\",\"error_description\":\"Unauthorized " + SECRET + "\"}")));

        String redirect = service.callback("code", stateOf(start), null, start.browserNonce());

        assertThat(callbackQuery(redirect, "error"))
                .contains("did not accept the client ID or client secret")
                .doesNotContain(SECRET);
        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("Salesforce: the instance address must be a Salesforce domain, and the adapter gets it with the token")
    void salesforceInstance() throws Exception {
        service.saveApp(ORG, Actor.SYSTEM, "salesforce", "key", "secret", Map.of("loginDomain", "login.salesforce.com"));
        OAuthService.Start start = service.start(ORG, USER, "salesforce");
        tokenAnswers("{\"access_token\":\"00D!token\",\"refresh_token\":\"r\",\"instance_url\":\"https://evil.example.com\","
                + "\"scope\":\"api refresh_token\"}");

        String refused = service.callback("code", stateOf(start), null, start.browserNonce());

        assertThat(callbackQuery(refused, "error")).contains("not a Salesforce domain");
        assertThat(rows).isEmpty();

        OAuthService.Start again = service.start(ORG, USER, "salesforce");
        tokenAnswers("{\"access_token\":\"00D!token\",\"refresh_token\":\"r\",\"instance_url\":\"https://acme.my.salesforce.com\","
                + "\"scope\":\"api refresh_token\"}");
        assertThat(service.callback("code", stateOf(again), null, again.browserNonce()))
                .isEqualTo(BASE + "/connectors?connected=salesforce");

        String credential = service.credentialFor(ORG, rows.get("salesforce")).orElseThrow();
        assertThat(json.readTree(credential).path("accessToken").asText()).isEqualTo("00D!token");
        assertThat(json.readTree(credential).path("instanceUrl").asText()).isEqualTo("https://acme.my.salesforce.com");
        assertThat(rows.get("salesforce").missingScopes()).isEmpty();
    }

    // ---- Refresh ---------------------------------------------------------------------------------

    private void connectedGmail(Instant expiresAt) throws Exception {
        saveGoogleApp();
        Connection connection = new Connection();
        connection.setId(UUID.randomUUID());
        connection.setOrgId(ORG);
        connection.setServer("gmail");
        connection.setDisplayName("Gmail");
        String blob = json.createObjectNode()
                .put("accessToken", "old-access")
                .put("refreshToken", "the-refresh-token")
                .put("expiresAt", expiresAt.getEpochSecond())
                .put("scope", "x")
                .toString();
        connection.connectWithOAuth(
                encryption.encrypt(ORG.toString(), blob).serialise(), "admin@acme.example", USER, List.of(), List.of(), expiresAt);
        rows.put("gmail", connection);
    }

    @Test
    @DisplayName("a token that is still good is used as it is, with no call to the provider")
    void freshTokenNotRefreshed() throws Exception {
        connectedGmail(Instant.now().plusSeconds(3000));

        assertThat(service.credentialFor(ORG, rows.get("gmail"))).contains("old-access");
        assertThat(provider.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("a token about to expire is refreshed on use, and the new one is stored encrypted")
    void refreshOnUse() throws Exception {
        connectedGmail(Instant.now().plusSeconds(30));
        provider.stubFor(post("/token").willReturn(okJson("{\"access_token\":\"new-access\",\"expires_in\":3600}")));

        Optional<String> credential = service.credentialFor(ORG, rows.get("gmail"));

        assertThat(credential).contains("new-access");
        provider.verify(postRequestedFor(urlPathEqualTo("/token"))
                .withRequestBody(containing("grant_type=refresh_token"))
                .withRequestBody(containing("refresh_token=the-refresh-token"))
                .withRequestBody(containing("client_id=client-id-1"))
                .withRequestBody(containing("client_secret=" + SECRET)));
        Connection stored = rows.get("gmail");
        assertThat(stored.getTokenExpiresAt()).isAfter(Instant.now().plusSeconds(3000));
        assertThat(stored.isReconnectRequired()).isFalse();
        assertThat(stored.getCredentialRef()).doesNotContain("new-access");
        String decrypted = encryption.decrypt(ORG.toString(), stored.getCredentialRef());
        assertThat(json.readTree(decrypted).path("accessToken").asText()).isEqualTo("new-access");
        // The provider did not rotate the refresh token, so the old one is kept.
        assertThat(json.readTree(decrypted).path("refreshToken").asText()).isEqualTo("the-refresh-token");
        // A second use needs no second refresh.
        assertThat(service.credentialFor(ORG, stored)).contains("new-access");
        provider.verify(1, postRequestedFor(urlPathEqualTo("/token")));
    }

    @Test
    @DisplayName("a refresh the provider refuses marks the connection as needing a reconnect, in plain words")
    void refreshRefused() throws Exception {
        connectedGmail(Instant.now().minusSeconds(10));
        provider.stubFor(post("/token").willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"invalid_grant\",\"error_description\":\"Token has been expired or revoked.\"}")));

        Optional<String> credential = service.credentialFor(ORG, rows.get("gmail"));

        Connection stored = rows.get("gmail");
        assertThat(stored.isReconnectRequired()).isTrue();
        assertThat(stored.getStatus()).isEqualTo("error");
        assertThat(stored.getLastError()).isEqualTo("Google no longer accepts the saved sign-in. Connect Gmail again.");
        // The stale token is still handed over, so the call fails with the provider's own refusal
        // instead of agents quietly receiving practice data.
        assertThat(credential).contains("old-access");
        // And it does not keep asking the provider once a reconnect is needed.
        service.credentialFor(ORG, stored);
        provider.verify(1, postRequestedFor(urlPathEqualTo("/token")));
    }

    @Test
    @DisplayName("a refresh that fails for a passing reason keeps the connection as it was")
    void refreshTransientFailure() throws Exception {
        connectedGmail(Instant.now().plusSeconds(30));
        provider.stubFor(post("/token").willReturn(aResponse().withStatus(503)));

        Optional<String> credential = service.credentialFor(ORG, rows.get("gmail"));

        assertThat(credential).contains("old-access");
        assertThat(rows.get("gmail").isReconnectRequired()).isFalse();
        assertThat(rows.get("gmail").getStatus()).isEqualTo("connected");
    }

    @Test
    @DisplayName("without a refresh token the connection asks for a reconnect once the token lapses")
    void noRefreshToken() throws Exception {
        saveGoogleApp();
        Connection connection = new Connection();
        connection.setId(UUID.randomUUID());
        connection.setOrgId(ORG);
        connection.setServer("gmail");
        String blob = json.createObjectNode().put("accessToken", "t").put("expiresAt", Instant.now().minusSeconds(5).getEpochSecond()).toString();
        connection.connectWithOAuth(encryption.encrypt(ORG.toString(), blob).serialise(), null, USER, List.of(), List.of(), Instant.now().minusSeconds(5));
        rows.put("gmail", connection);

        service.credentialFor(ORG, connection);

        assertThat(rows.get("gmail").isReconnectRequired()).isTrue();
        assertThat(rows.get("gmail").getLastError()).contains("Connect Gmail again");
        assertThat(provider.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("a token the provider rejected is refreshed at once, even before its stored expiry")
    void refreshAfterRejection() throws Exception {
        connectedGmail(Instant.now().plusSeconds(3000));
        provider.stubFor(post("/token").willReturn(okJson("{\"access_token\":\"new-access\",\"expires_in\":3600}")));

        Optional<String> credential = service.refreshRejected(ORG, rows.get("gmail"), "old-access");

        assertThat(credential).contains("new-access");
        provider.verify(1, postRequestedFor(urlPathEqualTo("/token")).withRequestBody(containing("grant_type=refresh_token")));
        assertThat(rows.get("gmail").isReconnectRequired()).isFalse();
    }

    @Test
    @DisplayName("a second rejected call is given the token the first one already renewed")
    void refreshAfterRejectionIsNotRepeated() throws Exception {
        connectedGmail(Instant.now().plusSeconds(3000));
        provider.stubFor(post("/token").willReturn(okJson("{\"access_token\":\"new-access\",\"expires_in\":3600}")));

        service.refreshRejected(ORG, rows.get("gmail"), "old-access");
        Optional<String> second = service.refreshRejected(ORG, rows.get("gmail"), "old-access");

        assertThat(second).contains("new-access");
        provider.verify(1, postRequestedFor(urlPathEqualTo("/token")));
    }

    @Test
    @DisplayName("a refusal after a rejected call marks the connection as needing a reconnect and gives no token")
    void refreshAfterRejectionRefused() throws Exception {
        connectedGmail(Instant.now().plusSeconds(3000));
        provider.stubFor(post("/token").willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"invalid_grant\"}")));

        Optional<String> credential = service.refreshRejected(ORG, rows.get("gmail"), "old-access");

        assertThat(credential).isEmpty();
        assertThat(rows.get("gmail").isReconnectRequired()).isTrue();
        assertThat(rows.get("gmail").getLastError()).contains("Connect Gmail again");
    }

    @Test
    @DisplayName("a provider outage during the refresh also ends in a reconnect request, not a stale token")
    void refreshAfterRejectionUnreachable() throws Exception {
        connectedGmail(Instant.now().plusSeconds(3000));
        provider.stubFor(post("/token").willReturn(aResponse().withStatus(503)));

        assertThat(service.refreshRejected(ORG, rows.get("gmail"), "old-access")).isEmpty();
        assertThat(rows.get("gmail").isReconnectRequired()).isTrue();
        assertThat(rows.get("gmail").getStatus()).isEqualTo("error");
    }

    @Test
    @DisplayName("a connection already needing a reconnect is not refreshed again, and a reconnect can be recorded")
    void reconnectRequiredIsFinal() throws Exception {
        connectedGmail(Instant.now().plusSeconds(3000));
        service.requireReconnect(ORG, rows.get("gmail"), "Gmail needs to be reconnected by an administrator.");

        assertThat(rows.get("gmail").isReconnectRequired()).isTrue();
        assertThat(rows.get("gmail").getLastError()).isEqualTo("Gmail needs to be reconnected by an administrator.");
        assertThat(service.refreshRejected(ORG, rows.get("gmail"), "old-access")).isEmpty();
        assertThat(provider.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("the state codec rejects tampering and expiry")
    void codec() {
        OAuthStateCodec codec = new OAuthStateCodec("a-secret");
        OAuthStateCodec other = new OAuthStateCodec("another-secret");
        OAuthStateCodec.Claims claims = new OAuthStateCodec.Claims(
                UUID.randomUUID(), ORG, USER, "gmail", Instant.now().plusSeconds(60), "binding");

        String signed = codec.sign(claims);

        assertThat(codec.verify(signed, Instant.now())).isPresent();
        assertThat(codec.verify(signed, Instant.now()).orElseThrow().server()).isEqualTo("gmail");
        assertThat(other.verify(signed, Instant.now())).isEmpty();
        assertThat(codec.verify(signed, Instant.now().plusSeconds(120))).isEmpty();
        assertThat(codec.verify(signed.replace('.', ':'), Instant.now())).isEmpty();
        assertThat(codec.verify(null, Instant.now())).isEmpty();
        assertThat(URI.create("https://x.example/?state=" + signed).getQuery()).isNotBlank();
    }
}
