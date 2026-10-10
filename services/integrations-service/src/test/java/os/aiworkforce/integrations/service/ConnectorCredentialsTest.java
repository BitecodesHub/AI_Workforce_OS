// @find: tests for connector credentials, multi-part credential, jira, confluence, zendesk, zoom, site email token, stored encrypted, checked before saving
// @what: Checks connectors with several credential fields are validated and stored as one encrypted value.
package os.aiworkforce.integrations.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.integrations.domain.Connection;
import os.aiworkforce.integrations.oauth.OAuthProperties;
import os.aiworkforce.integrations.oauth.OAuthService;
import os.aiworkforce.integrations.repository.Connections;
import os.aiworkforce.integrations.repository.OAuthApps;
import os.aiworkforce.integrations.repository.OAuthStates;
import os.aiworkforce.integrations.web.IntegrationController;
import os.aiworkforce.mcp.catalog.ConnectorCatalog;
import os.aiworkforce.mcp.live.JiraAdapter;
import os.aiworkforce.mcp.live.ZendeskAdapter;
import os.aiworkforce.mcp.oauth.OAuthProviders;
import os.aiworkforce.mcp.policy.ArgumentValidator;
import os.aiworkforce.mcp.policy.ToolGateway;
import os.aiworkforce.mcp.sandbox.SandboxServerAdapter;
import os.aiworkforce.mcp.sandbox.SandboxServerRegistry;
import os.aiworkforce.mcp.spi.McpServerAdapter;
import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.crypto.EnvelopeEncryptionService;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/** Connectors whose credential has several parts: stored as one encrypted value, checked first. */
class ConnectorCredentialsTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-0000000000aa");
    private static final String TOKEN = "atlassian-api-token-not-real";

    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Connection> rows = new HashMap<>();
    private WireMockServer provider;
    private EnvelopeEncryptionService encryption;
    private AuditClient audit;
    private Connections connections;
    private ConnectorService service;

    @BeforeEach
    void setUp() {
        provider = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        provider.start();
        PlatformProperties properties = TestProperties.properties();
        encryption = new EnvelopeEncryptionService(properties);
        audit = mock(AuditClient.class);
        ConnectorCatalog catalog = new ConnectorCatalog();

        // The real registry, with Jira and Zendesk pointed at the stand-in provider.
        List<McpServerAdapter> servers = new ArrayList<>();
        SandboxServerRegistry.definitions().forEach((server, tools) -> {
            SandboxServerAdapter sandbox = new SandboxServerAdapter(server, tools, json);
            servers.add(
                    switch (server) {
                        case "jira" -> new JiraAdapter(sandbox, json, WebClient.builder(), provider.baseUrl());
                        case "zendesk" -> new ZendeskAdapter(sandbox, json, WebClient.builder(), provider.baseUrl());
                        default -> sandbox;
                    });
        });
        ToolGateway gateway = new ToolGateway(servers, new ArgumentValidator(json), new os.aiworkforce.platform.resilience.ResiliencePresets(properties));

        connections = mock(Connections.class);
        when(connections.findByOrgIdAndServer(eq(ORG), anyString()))
                .thenAnswer(call -> Optional.ofNullable(rows.get(call.getArgument(1, String.class))));
        when(connections.save(any(Connection.class))).thenAnswer(call -> {
            Connection saved = call.getArgument(0);
            rows.put(saved.getServer(), saved);
            return saved;
        });
        OAuthService oauth = new OAuthService(
                connections, mock(OAuthApps.class), mock(OAuthStates.class), encryption, audit, WebClient.builder(), json,
                new OAuthProperties("http://localhost:5173", Duration.ofMinutes(10), Duration.ofMinutes(2)), catalog,
                "state-secret", OAuthProviders.all());
        service = new ConnectorService(connections, gateway, catalog, encryption, audit, oauth);
        RequestContext.setActor(Actor.user(UUID.randomUUID().toString(), ORG.toString(), "owner", Set.of(), 0L));
    }

    @AfterEach
    void tearDown() {
        provider.stop();
        RequestContext.clear();
    }

    private static Map<String, String> jiraFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("site", "https://acme.atlassian.net");
        fields.put("email", "admin@acme.example");
        fields.put("token", TOKEN);
        return fields;
    }

    @Test
    @DisplayName("Jira: the parts are checked with Atlassian, then stored together as one encrypted value")
    void jiraStoresOneEncryptedValue() throws Exception {
        provider.stubFor(get(urlPathEqualTo("/rest/api/3/myself"))
                .willReturn(okJson("{\"displayName\":\"Alex Admin\",\"emailAddress\":\"admin@acme.example\"}")));

        Connection saved = service.connect(ORG, "jira", null, jiraFields(), null);

        assertThat(saved.getStatus()).isEqualTo("connected");
        assertThat(saved.isSandbox()).isFalse();
        assertThat(saved.getAccountLabel()).contains("Alex Admin");
        assertThat(saved.getCredentialRef()).startsWith("v1:").doesNotContain(TOKEN).doesNotContain("acme");
        provider.verify(getRequestedFor(urlPathEqualTo("/rest/api/3/myself"))
                .withHeader("Authorization", equalTo("Basic "
                        + java.util.Base64.getEncoder().encodeToString(("admin@acme.example:" + TOKEN).getBytes()))));

        RequestContext.setActor(Actor.SYSTEM);
        String credential = service.credential(ORG, "jira").orElseThrow();
        assertThat(json.readTree(credential).path("site").asText()).isEqualTo("https://acme.atlassian.net");
        assertThat(json.readTree(credential).path("email").asText()).isEqualTo("admin@acme.example");
        assertThat(json.readTree(credential).path("token").asText()).isEqualTo(TOKEN);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> detail = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(ORG), any(), eq("integration.connect"), eq("integration"), eq("jira"), detail.capture());
        assertThat(detail.getValue().toString()).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("a missing part is refused by name, and nothing is sent to the provider or stored")
    void missingPartRefused() {
        Map<String, String> fields = jiraFields();
        fields.put("email", " ");

        assertThatThrownBy(() -> service.connect(ORG, "jira", null, fields, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.connect(ORG, "jira", TOKEN, Map.of(), null)).isInstanceOf(ApiException.class);
        assertThat(provider.getAllServeEvents()).isEmpty();
        verify(connections, never()).save(any());
    }

    @Test
    @DisplayName("an account email that is not an email is refused under its field and never sent")
    void malformedEmailRefused() {
        Map<String, String> fields = jiraFields();
        fields.put("email", "notanemail");

        assertThatThrownBy(() -> service.connect(ORG, "jira", null, fields, null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getMessage()).contains("you@company.com");
                    assertThat(e.details()).containsEntry("field", "email");
                    assertThat(e.status()).isEqualTo(422);
                });
        assertThat(provider.getAllServeEvents()).isEmpty();
        verify(connections, never()).save(any());
    }

    @Test
    @DisplayName("a site that is not an Atlassian address is refused with a plain sentence and never contacted")
    void foreignSiteRefused() {
        Map<String, String> fields = jiraFields();
        fields.put("site", "https://internal.example.com");

        assertThatThrownBy(() -> service.connect(ORG, "jira", null, fields, null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getMessage()).contains("Atlassian site");
                    // Named, so the form shows it beside the site box rather than above the form.
                    assertThat(e.details()).containsEntry("field", "site");
                    assertThat(e.status()).isEqualTo(422);
                });
        assertThat(provider.getAllServeEvents()).isEmpty();
        verify(connections, never()).save(any());
    }

    @Test
    @DisplayName("a stored token the provider later rejects puts the connector in Needs attention, in plain words,"
            + " and agents are still handed the token so they fail plainly rather than read practice data")
    void revokedTokenNeedsAttention() {
        provider.stubFor(get(urlPathEqualTo("/rest/api/3/myself"))
                .willReturn(okJson("{\"displayName\":\"Alex Admin\"}")));
        service.connect(ORG, "jira", null, jiraFields(), null);
        provider.stubFor(get(urlPathEqualTo("/rest/api/3/myself")).willReturn(aResponse().withStatus(401)));

        ConnectorService.CheckOutcome outcome = service.test(ORG, "jira");

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.message()).isNotBlank().doesNotContain(TOKEN).doesNotContain("Exception").doesNotContain("401 ");
        Connection stored = rows.get("jira");
        assertThat(stored.getStatus()).isEqualTo("error");
        assertThat(stored.getLastError()).isEqualTo(outcome.message());
        RequestContext.setActor(Actor.SYSTEM);
        assertThat(service.credential(ORG, "jira")).isPresent();

        // Once the provider accepts it again, a check clears the warning.
        provider.stubFor(get(urlPathEqualTo("/rest/api/3/myself"))
                .willReturn(okJson("{\"displayName\":\"Alex Admin\"}")));
        assertThat(service.test(ORG, "jira").ok()).isTrue();
        assertThat(rows.get("jira").getStatus()).isEqualTo("connected");
        assertThat(rows.get("jira").getLastError()).isNull();
    }

    @Test
    @DisplayName("a token the provider rejects is not stored")
    void rejectedTokenNotStored() {
        provider.stubFor(get(urlPathEqualTo("/api/v2/users/me")).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> service.connect(
                        ORG, "zendesk", null, Map.of("subdomain", "acme", "email", "a@b.example", "token", TOKEN), null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).doesNotContain(TOKEN));
        verify(connections, never()).save(any());
    }

    @Test
    @DisplayName("setting up and connecting need integration:connect; disconnecting needs integration:disconnect")
    void permissions() throws Exception {
        Map<String, String> required = new HashMap<>();
        for (Method method : IntegrationController.class.getDeclaredMethods()) {
            RequiresPermission annotation = method.getAnnotation(RequiresPermission.class);
            if (annotation != null) {
                required.put(method.getName(), annotation.value()[0]);
            }
        }
        assertThat(required)
                .containsEntry("connect", Permission.Codes.INTEGRATION_CONNECT)
                .containsEntry("oauthApp", Permission.Codes.INTEGRATION_CONNECT)
                .containsEntry("saveOauthApp", Permission.Codes.INTEGRATION_CONNECT)
                .containsEntry("oauthStart", Permission.Codes.INTEGRATION_CONNECT)
                .containsEntry("disconnect", Permission.Codes.INTEGRATION_DISCONNECT)
                .containsEntry("list", Permission.Codes.INTEGRATION_READ);
    }

    @Test
    @DisplayName("the internal lookup says none, connected or reconnect_required, and never hands over a refused token")
    void lookupStates() {
        assertThat(service.lookup(ORG, "jira").state()).isEqualTo("none");

        Connection connection = new Connection();
        connection.setId(UUID.randomUUID());
        connection.setOrgId(ORG);
        connection.setServer("jira");
        connection.connectWithToken(encryption.encrypt(ORG.toString(), "stored-token-value").serialise(), "x", null);
        rows.put("jira", connection);

        ConnectorService.CredentialLookup live = service.lookup(ORG, "jira");
        assertThat(live.state()).isEqualTo("connected");
        assertThat(live.value()).isEqualTo("stored-token-value");

        connection.requireReconnect("Jira needs to be reconnected by an administrator.");
        ConnectorService.CredentialLookup refused = service.lookup(ORG, "jira");
        assertThat(refused.state()).isEqualTo("reconnect_required");
        assertThat(refused.value()).isNull();
        assertThat(refused.message()).isEqualTo("Jira needs to be reconnected by an administrator.");

        connection.disconnect();
        assertThat(service.lookup(ORG, "jira").state()).isEqualTo("none");
    }
}
