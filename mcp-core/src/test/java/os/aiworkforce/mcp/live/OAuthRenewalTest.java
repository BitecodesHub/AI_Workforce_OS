package os.aiworkforce.mcp.live;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.sandbox.SandboxServerAdapter;
import os.aiworkforce.mcp.sandbox.SandboxServerRegistry;
import os.aiworkforce.mcp.spi.TokenRefresher;

/** A 401 from an OAuth provider: one refresh, one retry, then a plain "reconnect" failure. */
class OAuthRenewalTest {

    private static final String OLD = "old-access-token-not-real";
    private static final String NEW = "new-access-token-not-real";

    private final ObjectMapper json = new ObjectMapper();
    private WireMockServer provider;
    private Recorder store;

    @BeforeEach
    void start() {
        provider = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        provider.start();
        store = new Recorder();
    }

    @AfterEach
    void stop() {
        provider.stop();
    }

    /** Each connector with one read, and its display name. */
    record Case(String name, Supplier<OAuthAdapter> adapter, String tool, String arguments, boolean salesforce) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Case> connectors() {
        return Stream.of(
                new Case("Gmail", null, "list_messages", "{}", false),
                new Case("Google Calendar", null, "list_events", "{}", false),
                new Case("Google Drive", null, "list_files", "{}", false),
                new Case("Google Sheets", null, "list_spreadsheets", "{}", false),
                new Case("Outlook", null, "list_messages", "{}", false),
                new Case("Microsoft Teams", null, "list_channels", "{}", false),
                new Case("Salesforce", null, "search_accounts", "{\"query\":\"x\"}", true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("connectors")
    @DisplayName("a 401 refreshes the token once and retries the call once with the new token")
    void refreshesAndRetries(Case connector) {
        OAuthAdapter adapter = adapter(connector.name());
        String stored = connector.salesforce() ? salesforce(OLD) : OLD;
        String renewed = connector.salesforce() ? salesforce(NEW) : NEW;
        store.refreshed = renewed;
        provider.stubFor(any(anyUrl()).atPriority(5).willReturn(okJson("{}")));
        provider.stubFor(any(anyUrl())
                .atPriority(1)
                .withHeader("Authorization", equalTo("Bearer " + OLD))
                .willReturn(aResponse().withStatus(401).withBody("{}")));

        ToolResult result = adapter.invoke(invocation(adapter, connector), stored).block();

        assertThat(result.status()).as(result.summary()).isEqualTo(ToolResult.Status.SUCCEEDED);
        assertThat(store.refreshes).hasSize(1);
        assertThat(store.refreshes.get(0)).contains(adapter.server()).contains(stored);
        assertThat(store.reconnects).isEmpty();
        assertThat(provider.findAll(anyRequestedFor(anyUrl())).stream()
                        .filter(r -> r.getHeader("Authorization").equals("Bearer " + OLD))
                        .count())
                .isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("connectors")
    @DisplayName("a failed refresh marks the connection and fails with the reconnect sentence")
    void refreshFails(Case connector) {
        OAuthAdapter adapter = adapter(connector.name());
        store.refreshed = null;
        provider.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(401).withBody("{}")));

        ToolResult result = adapter.invoke(invocation(adapter, connector), connector.salesforce() ? salesforce(OLD) : OLD)
                .block();

        assertThat(result.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(result.summary()).isEqualTo(connector.name() + " needs to be reconnected by an administrator.");
        assertThat(store.reconnects).hasSize(1);
        assertThat(provider.findAll(anyRequestedFor(anyUrl()))).hasSize(1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("connectors")
    @DisplayName("a second 401 after the refresh marks the connection and is not retried again")
    void retryRejectedToo(Case connector) {
        OAuthAdapter adapter = adapter(connector.name());
        store.refreshed = connector.salesforce() ? salesforce(NEW) : NEW;
        provider.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(401).withBody("{}")));

        ToolResult result = adapter.invoke(invocation(adapter, connector), connector.salesforce() ? salesforce(OLD) : OLD)
                .block();

        assertThat(result.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(result.summary()).isEqualTo(connector.name() + " needs to be reconnected by an administrator.");
        assertThat(store.refreshes).hasSize(1);
        assertThat(store.reconnects).hasSize(1);
        assertThat(provider.findAll(anyRequestedFor(anyUrl()))).hasSize(2);
    }

    @Test
    @DisplayName("a send that was rejected with 401 is retried once, and arrives once")
    void nonIdempotentSendIsRetriedAfterA401() {
        GmailAdapter gmail = new GmailAdapter(sandbox("gmail"), json, WebClient.builder(), provider.baseUrl());
        gmail.useRefresher(() -> store);
        store.refreshed = NEW;
        provider.stubFor(post("/gmail/v1/users/me/messages/send")
                .atPriority(1)
                .withHeader("Authorization", equalTo("Bearer " + OLD))
                .willReturn(aResponse().withStatus(401).withBody("{}")));
        provider.stubFor(post("/gmail/v1/users/me/messages/send")
                .atPriority(2)
                .withHeader("Authorization", equalTo("Bearer " + NEW))
                .willReturn(okJson("{\"id\":\"sent1\"}")));

        ToolResult result = gmail.invoke(
                        new ToolInvocation(
                                "org-1",
                                "agent-1",
                                "run-1",
                                "gmail",
                                "send_message",
                                "{\"to\":\"jo@example.com\",\"subject\":\"Hi\",\"body\":\"Body\"}",
                                "run-1:call-1",
                                Map.of()),
                        OLD)
                .block();

        assertThat(result.status()).isEqualTo(ToolResult.Status.SUCCEEDED);
        long sentWithNew = provider.findAll(anyRequestedFor(anyUrl())).stream()
                .filter(r -> r.getHeader("Authorization").equals("Bearer " + NEW))
                .count();
        assertThat(sentWithNew).isEqualTo(1);
    }

    @Test
    @DisplayName("a dropped connection on a send is never retried or refreshed")
    void serverErrorIsNotRetried() {
        GmailAdapter gmail = new GmailAdapter(sandbox("gmail"), json, WebClient.builder(), provider.baseUrl());
        gmail.useRefresher(() -> store);
        provider.stubFor(post("/gmail/v1/users/me/messages/send").willReturn(aResponse().withStatus(503)));

        ToolResult result = gmail.invoke(
                        new ToolInvocation(
                                "org-1", "agent-1", "run-1", "gmail", "send_message",
                                "{\"to\":\"jo@example.com\",\"subject\":\"Hi\",\"body\":\"Body\"}", "k", Map.of()),
                        OLD)
                .block();

        assertThat(result.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(store.refreshes).isEmpty();
        assertThat(provider.findAll(anyRequestedFor(anyUrl()))).hasSize(1);
    }

    @Test
    @DisplayName("without a connection store a 401 keeps the plain reconnect advice and makes one request")
    void noStore() {
        GmailAdapter gmail = new GmailAdapter(sandbox("gmail"), json, WebClient.builder(), provider.baseUrl());
        provider.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(401).withBody("{}")));

        ToolResult result = gmail.invoke(
                        new ToolInvocation("org-1", "a", "r", "gmail", "list_messages", "{}", "k", Map.of()), OLD)
                .block();

        assertThat(result.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(result.summary()).contains("connected again");
        assertThat(provider.findAll(anyRequestedFor(anyUrl()))).hasSize(1);
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private static final class Recorder implements TokenRefresher {
        String refreshed;
        final List<String> refreshes = new ArrayList<>();
        final List<String> reconnects = new ArrayList<>();

        @Override
        public Mono<String> refresh(String orgId, String server, String rejected) {
            refreshes.add(orgId + "/" + server + "/" + rejected);
            return refreshed == null ? Mono.empty() : Mono.just(refreshed);
        }

        @Override
        public Mono<Void> markReconnectRequired(String orgId, String server, String reason) {
            reconnects.add(orgId + "/" + server + "/" + reason);
            return Mono.empty();
        }
    }

    private static String salesforce(String token) {
        return "{\"accessToken\":\"" + token + "\",\"instanceUrl\":\"https://acme.my.salesforce.com\"}";
    }

    private ToolInvocation invocation(OAuthAdapter adapter, Case connector) {
        return new ToolInvocation(
                "org-1", "agent-1", "run-1", adapter.server(), connector.tool(), connector.arguments(), "k", Map.of());
    }

    private OAuthAdapter adapter(String vendor) {
        WebClient.Builder web = WebClient.builder();
        String base = provider.baseUrl();
        OAuthAdapter adapter =
                switch (vendor) {
                    case "Gmail" -> new GmailAdapter(sandbox("gmail"), json, web, base);
                    case "Google Calendar" -> new CalendarAdapter(sandbox("calendar"), json, web, base + "/calendar/v3");
                    case "Google Drive" -> new DriveAdapter(sandbox("drive"), json, web, base);
                    case "Google Sheets" -> new SheetsAdapter(sandbox("sheets"), json, web, base, base);
                    case "Outlook" -> new OutlookAdapter(sandbox("outlook"), json, web, base + "/v1.0");
                    case "Microsoft Teams" -> new TeamsAdapter(sandbox("teams"), json, web, base + "/v1.0");
                    default -> new SalesforceAdapter(sandbox("salesforce"), json, web, base);
                };
        adapter.useRefresher(() -> store);
        return adapter;
    }

    private static SandboxServerAdapter sandbox(String server) {
        return new SandboxServerAdapter(server, SandboxServerRegistry.definitions().get(server), new ObjectMapper());
    }
}
