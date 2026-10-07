package os.aiworkforce.integrations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.integrations.domain.Connection;
import os.aiworkforce.integrations.oauth.OAuthProperties;
import os.aiworkforce.integrations.oauth.OAuthService;
import os.aiworkforce.integrations.repository.Connections;
import os.aiworkforce.integrations.repository.OAuthApps;
import os.aiworkforce.integrations.repository.OAuthStates;
import os.aiworkforce.mcp.oauth.OAuthProviders;
import os.aiworkforce.integrations.web.IntegrationController;
import os.aiworkforce.mcp.catalog.ConnectorCatalog;
import os.aiworkforce.mcp.policy.ArgumentValidator;
import os.aiworkforce.mcp.policy.ToolGateway;
import os.aiworkforce.mcp.sandbox.SandboxServerRegistry;
import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.crypto.EnvelopeEncryptionService;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.resilience.ResiliencePresets;

/** Connecting a connector: refused when it cannot go live, checked first, stored encrypted. */
class ConnectorServiceTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-0000000000aa");
    private static final String HOOK = "https://hooks.example.com/services/secret-path";

    private final Map<String, Connection> rows = new HashMap<>();
    private Connections connections;
    private AuditClient audit;
    private ConnectorService service;
    private IntegrationController controller;

    @BeforeEach
    void setUp() {
        PlatformProperties properties = TestProperties.properties();
        ObjectMapper json = new ObjectMapper();
        ToolGateway gateway = new ToolGateway(
                SandboxServerRegistry.servers(json, WebClient.builder(), true),
                new ArgumentValidator(json),
                new ResiliencePresets(properties));
        connections = mock(Connections.class);
        when(connections.findByOrgIdAndServer(eq(ORG), anyString()))
                .thenAnswer(call -> Optional.ofNullable(rows.get(call.getArgument(1, String.class))));
        when(connections.findByOrgId(ORG)).thenAnswer(call -> List.copyOf(rows.values()));
        when(connections.save(any(Connection.class))).thenAnswer(call -> {
            Connection saved = call.getArgument(0);
            rows.put(saved.getServer(), saved);
            return saved;
        });
        audit = mock(AuditClient.class);
        ConnectorCatalog catalog = new ConnectorCatalog();
        EnvelopeEncryptionService encryption = new EnvelopeEncryptionService(properties);
        OAuthService oauth = new OAuthService(
                connections,
                mock(OAuthApps.class),
                mock(OAuthStates.class),
                encryption,
                audit,
                WebClient.builder(),
                json,
                new OAuthProperties("http://localhost:5173", Duration.ofMinutes(10), Duration.ofMinutes(2)),
                catalog,
                "test-state-secret",
                OAuthProviders.all());
        service = new ConnectorService(connections, gateway, catalog, encryption, audit, oauth);
        controller = new IntegrationController(connections, gateway, catalog, service, oauth);
        RequestContext.setActor(Actor.user(UUID.randomUUID().toString(), ORG.toString(), "owner", Set.of(), 0L));
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("Voice has nothing to connect, so it is refused with connector_not_live")
    void notLive() {
        assertThatThrownBy(() -> service.connect(ORG, "voice", "anything", null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.CONNECTOR_NOT_LIVE);
                    assertThat(e.status()).isEqualTo(422);
                    assertThat(e.code().wire()).isEqualTo("connector_not_live");
                });
        verify(connections, never()).save(any());
    }

    @Test
    @DisplayName("a sign-in connector cannot be connected by pasting a token")
    void signInConnectorsRefuseTokens() {
        assertThatThrownBy(() -> service.connect(ORG, "gmail", "anything", null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("Connect");
                });
        verify(connections, never()).save(any());
    }

    @Test
    @DisplayName("a token the check refuses is not stored, and the reason is a plain sentence")
    void failedCheck() {
        assertThatThrownBy(() -> service.connect(ORG, "webhook", "ftp://hooks.example.com/x", null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.CONNECTOR_CHECK_FAILED);
                    assertThat(e.status()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("https://");
                });
        assertThatThrownBy(() -> service.connect(ORG, "webhook", "https://10.1.2.3/hook", null))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getMessage()).contains("private network"));
        verify(connections, never()).save(any());
    }

    @Test
    @DisplayName("an accepted token is stored encrypted, audited without the value, and decrypted for services")
    void connectStoresEncrypted() {
        IntegrationController.ConnectionView view =
                controller.connect("webhook", new IntegrationController.ConnectRequest(HOOK, null, null));

        Connection stored = rows.get("webhook");
        assertThat(stored.getCredentialRef()).startsWith("v1:").doesNotContain("secret-path");
        assertThat(view.status()).isEqualTo("connected");
        assertThat(view.sandbox()).isFalse();
        assertThat(view.accountLabel()).isEqualTo("hooks.example.com");
        assertThat(view.connectedAt()).isNotNull();
        assertThat(view.lastCheckedAt()).isNotNull();
        assertThat(view.liveAvailable()).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(ORG), any(), eq("integration.connect"), eq("integration"), eq("webhook"), detail.capture());
        assertThat(detail.getValue().toString()).doesNotContain("secret-path");

        RequestContext.setActor(Actor.SYSTEM);
        assertThat(controller.credential("webhook", ORG).value()).isEqualTo(HOOK);
    }

    @Test
    @DisplayName("a person's own session can never read a stored credential")
    void internalEndpointRefusesPeople() {
        assertThatThrownBy(() -> controller.credential("webhook", ORG))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PERMISSION_DENIED));
    }

    @Test
    @DisplayName("disconnecting forgets the token, so agents fall back to practice data")
    void disconnect() {
        service.connect(ORG, "webhook", HOOK, "Order system");

        controller.disconnect("webhook");

        Connection stored = rows.get("webhook");
        assertThat(stored.getCredentialRef()).isNull();
        assertThat(stored.isSandbox()).isTrue();
        assertThat(stored.getStatus()).isEqualTo("sandbox");
        verify(audit).record(eq(ORG), any(), eq("integration.disconnect"), eq("integration"), eq("webhook"), anyMap());
        RequestContext.setActor(Actor.SYSTEM);
        assertThat(controller.credential("webhook", ORG).value()).isNull();
    }

    @Test
    @DisplayName("testing re-checks the stored token and records when; with none stored it says so")
    void test() {
        ConnectorService.CheckOutcome nothingStored = controller.test("github");
        assertThat(nothingStored.ok()).isTrue();
        assertThat(nothingStored.message()).contains("practice data");

        service.connect(ORG, "webhook", HOOK, null);
        ConnectorService.CheckOutcome checked = controller.test("webhook");

        assertThat(checked.ok()).isTrue();
        assertThat(checked.message()).isEqualTo("Connected as hooks.example.com.");
        assertThat(checked.checkedAt()).isNotNull();
        assertThat(rows.get("webhook").getLastError()).isNull();
    }

    @Test
    @DisplayName("the list carries catalog fields for every connector, connected or not")
    void listCarriesCatalog() {
        service.connect(ORG, "webhook", HOOK, null);

        List<IntegrationController.ConnectionView> views = controller.list();

        assertThat(views).extracting(IntegrationController.ConnectionView::server).contains("hubspot", "stripe", "gmail");
        IntegrationController.ConnectionView github = views.stream()
                .filter(view -> view.server().equals("github"))
                .findFirst()
                .orElseThrow();
        assertThat(github.displayName()).isEqualTo("GitHub");
        assertThat(github.status()).isEqualTo("sandbox");
        assertThat(github.category()).isEqualTo("engineering");
        assertThat(github.authType()).isEqualTo("token");
        assertThat(github.liveAvailable()).isTrue();
        assertThat(github.tokenLabel()).isEqualTo("Personal access token");
        assertThat(github.setupSteps()).isNotEmpty();
        assertThat(github.tools()).isNotEmpty();
        assertThat(views.stream().filter(view -> view.server().equals("webhook")).findFirst().orElseThrow().status())
                .isEqualTo("connected");
        IntegrationController.ConnectionView gmail =
                views.stream().filter(view -> view.server().equals("gmail")).findFirst().orElseThrow();
        assertThat(gmail.liveAvailable()).isTrue();
        assertThat(gmail.authType()).isEqualTo("oauth");
        assertThat(gmail.oauthAppConfigured()).isFalse();
        assertThat(gmail.oauth().provider()).isEqualTo("google");
        assertThat(gmail.oauth().redirectUri()).isEqualTo("http://localhost:5173/api/oauth/callback");
        assertThat(gmail.oauth().scopes()).contains("https://www.googleapis.com/auth/gmail.send");
        IntegrationController.ConnectionView jira =
                views.stream().filter(view -> view.server().equals("jira")).findFirst().orElseThrow();
        assertThat(jira.credentialFields()).extracting(field -> field.key()).containsExactly("site", "email", "token");
        assertThat(jira.oauth()).isNull();
        assertThat(views.stream().filter(view -> view.server().equals("voice")).findFirst().orElseThrow().liveAvailable())
                .isFalse();
    }

    @Test
    @DisplayName("two connects at once both succeed, the later token winning, instead of one answering 409")
    void concurrentConnectRetries() {
        java.util.concurrent.atomic.AtomicInteger saves = new java.util.concurrent.atomic.AtomicInteger();
        when(connections.save(any(Connection.class))).thenAnswer(call -> {
            Connection saved = call.getArgument(0);
            // The first save loses a race with another request's insert, the second with an update.
            int attempt = saves.incrementAndGet();
            if (attempt == 1) {
                throw new org.springframework.dao.DataIntegrityViolationException("connections_org_server_unique");
            }
            if (attempt == 2) {
                throw new org.springframework.orm.ObjectOptimisticLockingFailureException(Connection.class, saved.getId());
            }
            rows.put(saved.getServer(), saved);
            return saved;
        });

        Connection connected = service.connect(ORG, "webhook", HOOK, null);

        assertThat(saves).hasValue(3);
        assertThat(connected.getStatus()).isEqualTo("connected");
        assertThat(rows.get("webhook").getCredentialRef()).startsWith("v1:");
    }

    @Test
    @DisplayName("a check that finishes after the connector was disconnected says so, instead of answering 409")
    void checkRacingDisconnect() {
        service.connect(ORG, "webhook", HOOK, null);
        Connection disconnected = new Connection();
        disconnected.setServer("webhook");
        disconnected.setOrgId(ORG);
        disconnected.disconnect();
        when(connections.save(any(Connection.class))).thenAnswer(call -> {
            rows.put("webhook", disconnected);
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(Connection.class, UUID.randomUUID());
        });

        ConnectorService.CheckOutcome outcome = service.test(ORG, "webhook");

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.message()).isEqualTo(
                "Webhook was disconnected while it was being checked, so agents use practice data. Nothing leaves the workspace.");
    }

    @Test
    @DisplayName("the practice-data-only refusal reads as a sentence whatever the connector is called")
    void notLiveMessage() {
        assertThatThrownBy(() -> service.connect(ORG, "voice", "anything", null))
                .hasMessage("There is no live connection for Voice notes yet, so agents use practice data for it.");
    }

    @Test
    @DisplayName("an unknown connector is not found")
    void unknownConnector() {
        assertThatThrownBy(() -> service.connect(ORG, "nonesuch", "token", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(404));
    }
}
