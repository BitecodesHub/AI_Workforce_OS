package os.aiworkforce.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.context.Actor;

/** Telling the orchestrator about a departure: the right call, after commit, never in the way. */
class OrchestratorClientTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-0000000000a1");
    private static final UUID USER = UUID.fromString("00000000-0000-7000-8000-0000000000c3");

    private final List<String> calls = new ArrayList<>();
    private HttpStatus answer = HttpStatus.OK;
    private TokenService tokens;
    private OrchestratorClient client;

    @BeforeEach
    void setUp() {
        tokens = mock(TokenService.class);
        when(tokens.issueInternalToken(any(), any()))
                .thenReturn(new TokenService.IssuedToken("service-token", Instant.now().plusSeconds(60)));
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            calls.add(request.method() + " " + request.url() + " " + request.headers().getFirst("Authorization")
                    + " " + bodyOf(request));
            if (answer != HttpStatus.OK) {
                return Mono.just(ClientResponse.create(answer).build());
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body("{\"paused\":2}")
                    .build());
        });
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
        client = new OrchestratorClient(builder, properties, tokens);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("posts the workspace and the person to owner-removed with a service token, and reads the count")
    void callsOwnerRemoved() {
        Integer paused = client.memberLeft(ORG, USER, "admin-id").block();

        assertThat(paused).isEqualTo(2);
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0))
                .startsWith("POST http://orchestrator/internal/schedules/owner-removed Bearer service-token")
                .contains("\"orgId\":\"" + ORG + "\"")
                .contains("\"userId\":\"" + USER + "\"");

        ArgumentCaptor<Actor> onBehalf = ArgumentCaptor.forClass(Actor.class);
        verify(tokens).issueInternalToken(eq("orchestrator"), onBehalf.capture());
        assertThat(onBehalf.getValue().kind()).isEqualTo(Actor.Kind.SYSTEM);
        assertThat(onBehalf.getValue().orgId()).isEqualTo(ORG.toString());
        assertThat(onBehalf.getValue().onBehalfOf()).isEqualTo("admin-id");
    }

    @Test
    @DisplayName("a failed call is logged, never thrown at the removal that triggered it")
    void failureDoesNotPropagate() {
        answer = HttpStatus.SERVICE_UNAVAILABLE;

        assertThatCode(() -> client.memberLeftAfterCommit(ORG, USER, "admin-id")).doesNotThrowAnyException();
        assertThat(calls).hasSize(1);
    }

    @Test
    @DisplayName("inside a transaction, nothing is sent until it commits, and nothing at all if it rolls back")
    void waitsForCommit() {
        TransactionSynchronizationManager.initSynchronization();
        client.memberLeftAfterCommit(ORG, USER, "admin-id");
        assertThat(calls).isEmpty();

        List<TransactionSynchronization> pending = TransactionSynchronizationManager.getSynchronizations();
        assertThat(pending).hasSize(1);
        pending.get(0).afterCommit();
        assertThat(calls).hasSize(1);

        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        client.memberLeftAfterCommit(ORG, USER, "admin-id");
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertThat(calls).hasSize(1);
    }

    /** The JSON body a request would send, written into a mock request to read it back. */
    private static String bodyOf(ClientRequest request) {
        MockClientHttpRequest written = new MockClientHttpRequest(request.method(), request.url());
        request.body()
                .insert(written, new BodyInserter.Context() {
                    @Override
                    public List<HttpMessageWriter<?>> messageWriters() {
                        return ExchangeStrategies.withDefaults().messageWriters();
                    }

                    @Override
                    public Optional<ServerHttpRequest> serverRequest() {
                        return Optional.empty();
                    }

                    @Override
                    public Map<String, Object> hints() {
                        return Map.of();
                    }
                })
                .block();
        return written.getBodyAsString().block();
    }
}
