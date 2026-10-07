package os.aiworkforce.orchestrator.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.HandlerStrategies;

import reactor.core.publisher.Mono;

import os.aiworkforce.platform.config.PlatformProperties;

/**
 * What the knowledge service sends back, read the way the coordinator needs it: each passage with
 * the source that holds its document, and whether the search could only match keywords.
 */
class KnowledgeClientTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final String CHUNK = "11111111-1111-7111-8111-111111111111";
    private static final String DOCUMENT = "22222222-2222-7222-8222-222222222222";
    private static final String SOURCE = "33333333-3333-7333-8333-333333333333";

    private final List<ClientRequest> requests = new ArrayList<>();

    private KnowledgeClient clientAnswering(HttpStatus status, String body) {
        ExchangeFunction exchange = request -> {
            requests.add(request);
            return Mono.just(ClientResponse.create(status)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .build());
        };
        PlatformProperties properties = mock(PlatformProperties.class);
        when(properties.services())
                .thenReturn(new PlatformProperties.Services(
                        "http://gateway", "http://identity", "http://organisation", "http://orchestrator",
                        "http://memory", "http://knowledge", "http://integrations", "http://analytics"));
        return new KnowledgeClient(WebClient.builder().exchangeFunction(exchange), properties);
    }

    private static String passageJson(boolean withSource) {
        return "{\"chunkId\":\"" + CHUNK + "\",\"documentId\":\"" + DOCUMENT + "\","
                + (withSource ? "\"sourceId\":\"" + SOURCE + "\"," : "")
                + "\"documentTitle\":\"Refund policy\",\"uri\":\"https://example.org/refunds\",\"pageNumber\":2,"
                + "\"heading\":\"Timing\",\"content\":\"Refunds are issued within five business days.\",\"score\":0.83}";
    }

    @Test
    @DisplayName("reads each passage's source, and whether the search was keyword-only")
    void readsSourceAndDegraded() {
        KnowledgeClient client = clientAnswering(
                HttpStatus.OK,
                "{\"passages\":[" + passageJson(true) + "],\"grounded\":true,\"degraded\":true}");

        Optional<KnowledgeClient.SearchResult> result = client.search(ORG, "refund policy", "Bearer token");

        assertThat(result).isPresent();
        assertThat(result.get().grounded()).isTrue();
        assertThat(result.get().degraded()).isTrue();
        KnowledgeClient.Passage passage = result.get().passages().getFirst();
        assertThat(passage.sourceId()).isEqualTo(UUID.fromString(SOURCE));
        assertThat(passage.documentId()).isEqualTo(UUID.fromString(DOCUMENT));
        assertThat(passage.chunkId()).isEqualTo(UUID.fromString(CHUNK));
        assertThat(passage.documentTitle()).isEqualTo("Refund policy");
        assertThat(passage.pageNumber()).isEqualTo(2);
        assertThat(passage.heading()).isEqualTo("Timing");
        assertThat(passage.score()).isEqualTo(0.83);
    }

    @Test
    @DisplayName("a response from a knowledge service that does not send them yet has no source and is not degraded")
    void olderResponseStillReads() {
        KnowledgeClient client =
                clientAnswering(HttpStatus.OK, "{\"passages\":[" + passageJson(false) + "],\"grounded\":true}");

        KnowledgeClient.SearchResult result = client.search(ORG, "refund policy", "Bearer token").orElseThrow();

        assertThat(result.degraded()).isFalse();
        assertThat(result.passages().getFirst().sourceId()).isNull();
        assertThat(result.passages().getFirst().documentTitle()).isEqualTo("Refund policy");
    }

    @Test
    @DisplayName("an ungrounded search with nothing found reads as exactly that")
    void ungroundedReads() {
        KnowledgeClient client = clientAnswering(HttpStatus.OK, "{\"passages\":[],\"grounded\":false,\"degraded\":false}");

        KnowledgeClient.SearchResult result = client.search(ORG, "anything", "Bearer token").orElseThrow();

        assertThat(result.grounded()).isFalse();
        assertThat(result.passages()).isEmpty();
    }

    @Test
    @DisplayName("asks on the person's own behalf, in their workspace, with the query cut to what the service accepts")
    void sendsThePersonsOwnAuthority() throws Exception {
        KnowledgeClient client = clientAnswering(HttpStatus.OK, "{\"passages\":[],\"grounded\":false}");

        client.search(ORG, "word ".repeat(400), "Bearer the-person");

        ClientRequest sent = requests.getFirst();
        assertThat(sent.url().getPath()).isEqualTo("/api/knowledge/search");
        assertThat(sent.headers().getFirst("Authorization")).isEqualTo("Bearer the-person");
        assertThat(sent.headers().getFirst("X-Workspace-Id")).isEqualTo(ORG.toString());

        MockClientHttpRequest written = new MockClientHttpRequest(HttpMethod.POST, "/api/knowledge/search");
        sent.body()
                .insert(written, new BodyInserter.Context() {
                    @Override
                    public List<HttpMessageWriter<?>> messageWriters() {
                        return HandlerStrategies.withDefaults().messageWriters();
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
        JsonNode body = new ObjectMapper().readTree(written.getBodyAsString().block());
        assertThat(body.get("query").asText()).hasSizeLessThanOrEqualTo(1_000).startsWith("word word");
        assertThat(body.get("limit").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("with no authorization to forward it does not ask at all")
    void noAuthorizationNoSearch() {
        KnowledgeClient client = clientAnswering(HttpStatus.OK, "{\"passages\":[],\"grounded\":false}");

        assertThat(client.search(ORG, "refunds", null)).isEmpty();
        assertThat(client.search(ORG, "refunds", "  ")).isEmpty();
        assertThat(requests).isEmpty();
    }

    @Test
    @DisplayName("a refusal or a failure is reported as no result, never thrown")
    void failuresAreEmpty() {
        assertThat(clientAnswering(HttpStatus.FORBIDDEN, "{}").search(ORG, "refunds", "Bearer token")).isEmpty();
        assertThat(clientAnswering(HttpStatus.UNAUTHORIZED, "{}").search(ORG, "refunds", "Bearer token")).isEmpty();
        assertThat(clientAnswering(HttpStatus.INTERNAL_SERVER_ERROR, "{}").search(ORG, "refunds", "Bearer token"))
                .isEmpty();
        assertThat(clientAnswering(HttpStatus.OK, "not json at all").search(ORG, "refunds", "Bearer token"))
                .isEmpty();
    }
}
