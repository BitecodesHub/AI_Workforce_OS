package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import os.aiworkforce.mcp.policy.ToolGateway;
import os.aiworkforce.mcp.spi.McpServerAdapter;
import os.aiworkforce.platform.config.PlatformProperties;

/**
 * "No token is stored" and "the store could not be asked" call for opposite behaviour, so the
 * resolver answers one of three things. A live connector whose token could not be read must never
 * be mistaken for one nobody connected.
 */
class ToolCredentialResolverTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private final List<ClientRequest> requests = new ArrayList<>();
    /** What the store answers next; each call takes one. */
    private final Queue<Supplier<Mono<ClientResponse>>> answers = new ArrayDeque<>();

    private ToolGateway gateway;
    private InternalTokenProvider tokens;
    private ToolCredentialResolver resolver;

    @BeforeEach
    void setUp() {
        ExchangeFunction exchange = request -> {
            requests.add(request);
            Supplier<Mono<ClientResponse>> next = answers.poll();
            return next == null ? Mono.error(new IllegalStateException("No answer queued")) : next.get();
        };
        PlatformProperties properties = mock(PlatformProperties.class);
        when(properties.services())
                .thenReturn(new PlatformProperties.Services(
                        "http://gateway",
                        "http://identity",
                        "http://organisation",
                        "http://orchestrator",
                        "http://memory",
                        "http://knowledge",
                        "http://integrations",
                        "http://analytics"));
        tokens = mock(InternalTokenProvider.class);
        when(tokens.forService("integrations")).thenReturn("internal-service-token");
        gateway = mock(ToolGateway.class);
        liveServer("github");
        resolver = new ToolCredentialResolver(
                WebClient.builder().exchangeFunction(exchange), properties, tokens, gateway);
        resolver.setRetryDelay(Duration.ZERO);
    }

    private void liveServer(String server) {
        McpServerAdapter adapter = mock(McpServerAdapter.class);
        when(adapter.isSandbox()).thenReturn(false);
        when(gateway.adapter(server)).thenReturn(Optional.of(adapter));
    }

    private void sandboxServer(String server) {
        McpServerAdapter adapter = mock(McpServerAdapter.class);
        when(adapter.isSandbox()).thenReturn(true);
        when(gateway.adapter(server)).thenReturn(Optional.of(adapter));
    }

    private static Supplier<Mono<ClientResponse>> json(String body) {
        return () -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build());
    }

    private static Supplier<Mono<ClientResponse>> status(HttpStatus status) {
        return () -> Mono.just(ClientResponse.create(status).build());
    }

    @Test
    @DisplayName("a stored token is Connected, fetched for this workspace and server with the internal token")
    void storedTokenIsConnected() {
        answers.add(json("{\"value\":\"ghp_realtoken\"}"));

        ToolCredentialResolver.Lookup lookup = resolver.resolve(ORG, "github");

        assertThat(lookup).isEqualTo(new ToolCredentialResolver.Connected("ghp_realtoken"));
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.url().getPath()).isEqualTo("/internal/connections/github/credential");
            assertThat(request.headers().getFirst("X-Workspace-Id")).isEqualTo(ORG.toString());
            assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer internal-service-token");
        });
    }

    @Test
    @DisplayName("a connection that needs reconnecting is ReconnectRequired with its message, and is not retried")
    void reconnectRequired() {
        answers.add(json("{\"value\":null,\"state\":\"reconnect_required\",\"message\":\"Sign in again.\"}"));

        ToolCredentialResolver.Lookup lookup = resolver.resolve(ORG, "github");

        assertThat(lookup).isEqualTo(new ToolCredentialResolver.ReconnectRequired("Sign in again."));
        assertThat(requests).hasSize(1);
    }

    @Test
    @DisplayName("a stored credential the store could not read is Unavailable, never practice data")
    void unreadableIsUnavailable() {
        answers.add(json("{\"value\":null,\"state\":\"unreadable\"}"));
        answers.add(json("{\"value\":null,\"state\":\"unreadable\"}"));

        assertThat(resolver.resolve(ORG, "github")).isInstanceOf(ToolCredentialResolver.Unavailable.class);
    }

    @Test
    @DisplayName("a 200 with no value, or a blank one, is NotConnected and is not retried")
    void noValueIsNotConnected() {
        for (String body : List.of("{\"value\":null}", "{\"value\":\"   \"}", "{}")) {
            requests.clear();
            answers.add(json(body));

            assertThat(resolver.resolve(ORG, "github")).as(body).isEqualTo(ToolCredentialResolver.NOT_CONNECTED);
            assertThat(requests).as(body).hasSize(1);
        }
    }

    @Test
    @DisplayName("an empty body is NotConnected too")
    void emptyBodyIsNotConnected() {
        answers.add(status(HttpStatus.NO_CONTENT));

        assertThat(resolver.resolve(ORG, "github")).isEqualTo(ToolCredentialResolver.NOT_CONNECTED);
    }

    @Test
    @DisplayName("a server that only ever answers from practice data is not looked up at all")
    void sandboxServerIsNotLookedUp() {
        sandboxServer("gmail");

        assertThat(resolver.resolve(ORG, "gmail")).isEqualTo(ToolCredentialResolver.NOT_CONNECTED);
        assertThat(requests).isEmpty();
    }

    @Test
    @DisplayName("a server the gateway does not know is not looked up either")
    void unknownServerIsNotLookedUp() {
        when(gateway.adapter("mystery")).thenReturn(Optional.empty());

        assertThat(resolver.resolve(ORG, "mystery")).isEqualTo(ToolCredentialResolver.NOT_CONNECTED);
        assertThat(requests).isEmpty();
    }

    @Test
    @DisplayName("a failure that clears on the retry is answered as if it had not happened")
    void oneRetryRidesOutABriefOutage() {
        answers.add(status(HttpStatus.SERVICE_UNAVAILABLE));
        answers.add(json("{\"value\":\"ghp_realtoken\"}"));

        assertThat(resolver.resolve(ORG, "github")).isEqualTo(new ToolCredentialResolver.Connected("ghp_realtoken"));
        assertThat(requests).hasSize(2);
    }

    @Test
    @DisplayName("a 5xx twice, a 404 twice, and a timeout twice are each Unavailable, after exactly one retry")
    void failuresAreUnavailableAfterOneRetry() {
        List<Supplier<Mono<ClientResponse>>> failures = List.of(
                status(HttpStatus.INTERNAL_SERVER_ERROR),
                status(HttpStatus.NOT_FOUND),
                status(HttpStatus.FORBIDDEN),
                () -> Mono.error(new TimeoutException("no answer in 5s")));
        for (Supplier<Mono<ClientResponse>> failure : failures) {
            requests.clear();
            answers.add(failure);
            answers.add(failure);

            ToolCredentialResolver.Lookup lookup = resolver.resolve(ORG, "github");

            assertThat(lookup).isInstanceOf(ToolCredentialResolver.Unavailable.class);
            assertThat(((ToolCredentialResolver.Unavailable) lookup).reason()).isNotBlank();
            assertThat(requests).hasSize(2);
        }
    }

    @Test
    @DisplayName("a token that could not be minted is Unavailable, and the reason does not carry any secret")
    void tokenFailureIsUnavailable() {
        when(tokens.forService("integrations")).thenThrow(new IllegalStateException("signing key not ready"));

        ToolCredentialResolver.Lookup lookup = resolver.resolve(ORG, "github");

        assertThat(lookup).isInstanceOf(ToolCredentialResolver.Unavailable.class);
        assertThat(((ToolCredentialResolver.Unavailable) lookup).reason()).contains("signing key not ready");
        assertThat(requests).isEmpty();
    }

    @Test
    @DisplayName("a lookup that reaches a log line shows no token")
    void connectedDoesNotPrintTheToken() {
        assertThat(new ToolCredentialResolver.Connected("ghp_realtoken").toString()).doesNotContain("ghp_realtoken");
    }

    @Test
    @DisplayName("what the model and trace are told when the store cannot be asked says nothing was sent")
    void unavailableMessageIsPlain() {
        assertThat(ToolCredentialResolver.UNAVAILABLE_MESSAGE)
                .isEqualTo("Could not reach the connection store; nothing was read or sent. Try again shortly.");
    }
}
