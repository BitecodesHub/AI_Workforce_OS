// @find: tests for org credential resolver, provider credential, API key lookup, organisation service, credential resolver
// @what: Unit and integration tests (12 cases) for org credential resolver, for example: mints for requested workspace; malformed workspace; blank reference; not found.
package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import os.aiworkforce.llm.spi.CredentialResolver;
import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.resilience.ResiliencePresets;

/**
 * A provider key is fetched with a token minted for the workspace it belongs to.
 *
 * <p>The organisation service refuses a reveal whose token names a different workspace from the
 * request, so a token taken from the calling context - which for a scheduled run names none -
 * would leave every scheduled run without a model key.
 *
 * <p>And a store that did not answer is not a store with no key: only a 404 or an empty value is
 * "not found", while an error, a 5xx or a timeout is retried once and then reported as unavailable.
 */
class OrgCredentialResolverTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private static final String OK = "{\"value\":\"sk-live\"}";

    private final List<ClientRequest> sent = new ArrayList<>();
    /** What the store answers, one entry per request; the last one repeats when they run out. */
    private final Deque<Supplier<Mono<ClientResponse>>> answers = new ArrayDeque<>();
    private InternalTokenProvider tokens;
    private CircuitBreaker breaker;
    private OrgCredentialResolver resolver;

    @BeforeEach
    void setUp() {
        PlatformProperties properties = mock(PlatformProperties.class);
        PlatformProperties.Services services = mock(PlatformProperties.Services.class);
        when(properties.services()).thenReturn(services);
        when(services.organisation()).thenReturn("http://organisation");
        tokens = mock(InternalTokenProvider.class);
        when(tokens.forService("organisation", ORG)).thenReturn("token-for-org");

        breaker = CircuitBreaker.of(
                "test-organisation",
                CircuitBreakerConfig.custom()
                        .minimumNumberOfCalls(4)
                        .slidingWindowSize(4)
                        .failureRateThreshold(50)
                        .build());
        ResiliencePresets resilience = mock(ResiliencePresets.class);
        when(resilience.circuitBreaker("service.organisation")).thenReturn(breaker);
        when(resilience.backoff(anyString(), anyInt())).thenReturn(Duration.ofMillis(1));

        answers.add(() -> respond(HttpStatus.OK, OK));
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            sent.add(request);
            Supplier<Mono<ClientResponse>> next = answers.size() > 1 ? answers.poll() : answers.peek();
            return next.get();
        });
        resolver = new OrgCredentialResolver(builder, properties, tokens, resilience);
    }

    private static Mono<ClientResponse> respond(HttpStatus status, String body) {
        return Mono.just(ClientResponse.create(status)
                .header("Content-Type", "application/json")
                .body(body)
                .build());
    }

    /** The store answers with these, in order, and then with the last one for ever. */
    @SafeVarargs
    private void storeAnswers(Supplier<Mono<ClientResponse>>... next) {
        answers.clear();
        answers.addAll(List.of(next));
    }

    @Test
    @DisplayName("the token is minted for the workspace being resolved and the header names the same one")
    void mintsForRequestedWorkspace() {
        assertThat(resolver.resolve(ORG.toString(), "openai")).contains("sk-live");

        assertThat(sent).hasSize(1);
        ClientRequest request = sent.get(0);
        assertThat(request.url().getPath()).isEqualTo("/internal/credentials/openai");
        assertThat(request.headers().getFirst("X-Workspace-Id")).isEqualTo(ORG.toString());
        assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer token-for-org");
        verify(tokens).forService("organisation", ORG);
        verify(tokens, never()).forService(anyString());
    }

    @Test
    @DisplayName("a workspace id that is not one resolves to nothing rather than calling out")
    void malformedWorkspace() {
        assertThat(resolver.resolve("not-a-workspace", "openai")).isEmpty();

        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("no reference means no call at all")
    void blankReference() {
        assertThat(resolver.resolve(ORG.toString(), " ")).isEmpty();

        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("a 404 means nothing is stored, and is not retried")
    void notFound() {
        storeAnswers(() -> respond(HttpStatus.NOT_FOUND, "{}"));

        assertThat(resolver.lookup(ORG.toString(), "openai")).isSameAs(CredentialResolver.NotFound.INSTANCE);

        assertThat(sent).hasSize(1);
        // A healthy answer of "no key" is not a failure of the store.
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("a blank value means nothing is stored")
    void blankValue() {
        storeAnswers(() -> respond(HttpStatus.OK, "{\"value\":\"  \"}"));

        assertThat(resolver.lookup(ORG.toString(), "openai")).isSameAs(CredentialResolver.NotFound.INSTANCE);
        assertThat(resolver.resolve(ORG.toString(), "openai")).isEmpty();
    }

    @Test
    @DisplayName("an empty answer ({} with no value) means nothing is stored and is not a failure of the store")
    void emptyAnswerIsNotFound() {
        storeAnswers(() -> respond(HttpStatus.OK, "{}"));

        for (int i = 0; i < 20; i++) {
            assertThat(resolver.lookup(ORG.toString(), "elevenlabs")).isSameAs(CredentialResolver.NotFound.INSTANCE);
        }

        // Polling an unset key must never open the breaker that every model call also goes through.
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(breaker.getState()).isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("a 503 is tried once more, and the second answer is used")
    void retriesAServerError() {
        storeAnswers(() -> respond(HttpStatus.SERVICE_UNAVAILABLE, "{}"), () -> respond(HttpStatus.OK, OK));

        CredentialResolver.Lookup lookup = resolver.lookup(ORG.toString(), "openai");

        assertThat(lookup).isEqualTo(new CredentialResolver.Found("sk-live"));
        assertThat(sent).hasSize(2);
    }

    @Test
    @DisplayName("a store that keeps failing is reported as unavailable, after one retry, not as a missing key")
    void unavailableAfterOneRetry() {
        storeAnswers(() -> respond(HttpStatus.BAD_GATEWAY, "{}"));

        CredentialResolver.Lookup lookup = resolver.lookup(ORG.toString(), "openai");

        assertThat(lookup).isInstanceOf(CredentialResolver.Unavailable.class);
        assertThat(((CredentialResolver.Unavailable) lookup).reason()).contains("502");
        assertThat(sent).hasSize(2);
        assertThat(resolver.resolve(ORG.toString(), "openai")).isEmpty();
    }

    @Test
    @DisplayName("a connection that fails is unavailable too")
    void networkErrorIsUnavailable() {
        storeAnswers(() -> Mono.error(new java.io.UncheckedIOException(new java.io.IOException("connection reset"))));

        assertThat(resolver.lookup(ORG.toString(), "openai")).isInstanceOf(CredentialResolver.Unavailable.class);
        assertThat(sent).hasSize(2);
    }

    @Test
    @DisplayName("failing to get a token from the identity service is unavailable, not a missing key")
    void tokenFailureIsUnavailable() {
        when(tokens.forService("organisation", ORG)).thenThrow(new IllegalStateException("identity is down"));

        CredentialResolver.Lookup lookup = resolver.lookup(ORG.toString(), "openai");

        assertThat(lookup).isInstanceOf(CredentialResolver.Unavailable.class);
        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("a refusal is reported as unavailable without asking again")
    void refusalIsNotRetried() {
        storeAnswers(() -> respond(HttpStatus.FORBIDDEN, "{}"));

        assertThat(resolver.lookup(ORG.toString(), "openai")).isInstanceOf(CredentialResolver.Unavailable.class);

        assertThat(sent).hasSize(1);
    }

    @Test
    @DisplayName("an open breaker answers unavailable without calling the store")
    void openBreaker() {
        breaker.transitionToOpenState();

        CredentialResolver.Lookup lookup = resolver.lookup(ORG.toString(), "openai");

        assertThat(lookup).isInstanceOf(CredentialResolver.Unavailable.class);
        assertThat(sent).isEmpty();
    }
}
