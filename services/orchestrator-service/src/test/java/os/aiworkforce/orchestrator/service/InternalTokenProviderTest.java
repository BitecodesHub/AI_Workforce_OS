// @find: tests for internal token provider, internal token, service token, actor propagation, service-to-service auth
// @what: Unit and integration tests (7 cases) for internal token provider, for example: system context names requested workspace; person carried; other workspace refused; cached per workspace.
package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/** Which workspace a minted service token names, and how many the provider keeps. */
class InternalTokenProviderTest {

    private static final UUID ORG_A = UUID.fromString("00000000-0000-7000-8000-00000000000a");
    private static final UUID ORG_B = UUID.fromString("00000000-0000-7000-8000-00000000000b");

    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> minted = new ArrayList<>();
    private Duration ttl = Duration.ofMinutes(1);

    @BeforeEach
    void reset() {
        minted.clear();
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    private InternalTokenProvider provider() {
        PlatformProperties properties = mock(PlatformProperties.class);
        PlatformProperties.Services services = mock(PlatformProperties.Services.class);
        PlatformProperties.Security security = mock(PlatformProperties.Security.class);
        when(properties.services()).thenReturn(services);
        when(properties.security()).thenReturn(security);
        when(services.identity()).thenReturn("http://identity");
        when(security.internalTokenTtl()).thenReturn(ttl);
        when(security.internalServiceSecret()).thenReturn("test-secret");

        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            JsonNode body = read(request);
            minted.add(body);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body("{\"token\":\"token-" + minted.size() + "\"}")
                    .build());
        });
        return new InternalTokenProvider(builder, properties);
    }

    private JsonNode read(ClientRequest request) {
        MockClientHttpRequest out = new MockClientHttpRequest(request.method(), request.url());
        request.body()
                .insert(out, new BodyInserter.Context() {
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
        try {
            return json.readTree(out.getBodyAsString().block());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("a scheduled run with no workspace in context gets a token naming the workspace asked for")
    void systemContextNamesRequestedWorkspace() {
        InternalTokenProvider tokens = provider();

        String token = tokens.forService("organisation", ORG_A);

        assertThat(token).isEqualTo("token-1");
        assertThat(minted).hasSize(1);
        assertThat(minted.get(0).path("audience").asText()).isEqualTo("organisation");
        assertThat(minted.get(0).path("orgId").asText()).isEqualTo(ORG_A.toString());
        assertThat(minted.get(0).path("actorKind").asText()).isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("the person in context is still carried, so the audit trail keeps them")
    void personCarried() {
        String person = UUID.randomUUID().toString();
        RequestContext.setActor(Actor.user(person, ORG_A.toString(), "owner", Set.of(), 0L));
        InternalTokenProvider tokens = provider();

        tokens.forService("organisation", ORG_A);

        assertThat(minted.get(0).path("orgId").asText()).isEqualTo(ORG_A.toString());
        assertThat(minted.get(0).path("onBehalfOf").asText()).isEqualTo(person);
    }

    @Test
    @DisplayName("a caller acting for one workspace cannot have a token minted for another")
    void otherWorkspaceRefused() {
        RequestContext.setActor(Actor.user(UUID.randomUUID().toString(), ORG_A.toString(), "owner", Set.of(), 0L));
        InternalTokenProvider tokens = provider();

        assertThatThrownBy(() -> tokens.forService("organisation", ORG_B))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.ORGANISATION_MISMATCH));
        assertThat(minted).isEmpty();
    }

    @Test
    @DisplayName("tokens are cached per workspace, so one workspace's token never serves another's call")
    void cachedPerWorkspace() {
        InternalTokenProvider tokens = provider();

        String first = tokens.forService("organisation", ORG_A);
        String again = tokens.forService("organisation", ORG_A);
        String other = tokens.forService("organisation", ORG_B);

        assertThat(again).isEqualTo(first);
        assertThat(other).isNotEqualTo(first);
        assertThat(minted).extracting(node -> node.path("orgId").asText())
                .containsExactly(ORG_A.toString(), ORG_B.toString());
    }

    @Test
    @DisplayName("the single-argument form keeps naming the context's own workspace")
    void contextWorkspaceByDefault() {
        RequestContext.setActor(Actor.user(UUID.randomUUID().toString(), ORG_B.toString(), "owner", Set.of(), 0L));
        InternalTokenProvider tokens = provider();

        tokens.forService("knowledge");

        assertThat(minted.get(0).path("orgId").asText()).isEqualTo(ORG_B.toString());
    }

    @Test
    @DisplayName("the cache never holds more than a thousand tokens")
    void cacheBounded() {
        InternalTokenProvider tokens = provider();

        for (int i = 0; i < InternalTokenProvider.MAX_CACHED + 50; i++) {
            RequestContext.setActor(Actor.user("person-" + i, ORG_A.toString(), "owner", Set.of(), 0L));
            tokens.forService("organisation");
        }

        assertThat(tokens.cachedTokens()).isEqualTo(InternalTokenProvider.MAX_CACHED);
        // Room is made by dropping the oldest, never the token just added.
        int before = minted.size();
        tokens.forService("organisation");
        assertThat(minted).hasSize(before);
    }

    @Test
    @DisplayName("expired tokens are dropped when the next one is added")
    void expiredDropped() {
        ttl = Duration.ZERO;
        InternalTokenProvider tokens = provider();

        tokens.forService("organisation", ORG_A);
        tokens.forService("organisation", ORG_B);
        tokens.forService("knowledge", ORG_A);

        assertThat(tokens.cachedTokens()).isEqualTo(1);
    }
}
