// @find: tests for embedding timeouts, query embedding, which embedding model, embedding service, knowledge base embeddings
// @what: Checks how long embedding is allowed and which model it uses.
package os.aiworkforce.knowledge.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.platform.config.PlatformProperties;

/**
 * How long embedding is given, and with which model. A query is on a person's request path and
 * gets three seconds; a batch of passages during ingestion keeps the minute and a half a slow
 * provider needs.
 */
class EmbeddingServiceTest {

    private static final String PATH = "/internal/embeddings";
    private static final String ONE_VECTOR = "{\"vectors\":[[0.5,0.25]],\"dimension\":2}";

    private final ObjectMapper json = new ObjectMapper();
    private WireMockServer server;
    private EmbeddingService embeddings;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        PlatformProperties properties = mock(PlatformProperties.class);
        PlatformProperties.Services services = mock(PlatformProperties.Services.class);
        when(properties.services()).thenReturn(services);
        when(services.orchestrator()).thenReturn(server.baseUrl());
        InternalTokenProvider tokens = mock(InternalTokenProvider.class);
        when(tokens.forService("orchestrator")).thenReturn("service-token");
        embeddings = new EmbeddingService(WebClient.builder(), properties, tokens);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder vectors() {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(ONE_VECTOR);
    }

    @Test
    @DisplayName("a query is embedded with the provider and model it was asked for, not a fixed one")
    void queryUsesTheGivenModel() throws Exception {
        server.stubFor(post(urlPathEqualTo(PATH)).willReturn(vectors()));
        UUID org = UUID.randomUUID();

        float[] vector = embeddings.embedQuery(org, "gemini", "text-embedding-004", "annual leave");

        assertThat(vector).containsExactly(0.5f, 0.25f);
        var request = server.findAll(postRequestedFor(urlPathEqualTo(PATH))).get(0);
        JsonNode body = json.readTree(request.getBodyAsString());
        assertThat(body.path("providerId").asText()).isEqualTo("gemini");
        assertThat(body.path("modelId").asText()).isEqualTo("text-embedding-004");
        assertThat(body.path("texts")).extracting(JsonNode::asText).containsExactly("annual leave");
        assertThat(request.getHeader("X-Workspace-Id")).isEqualTo(org.toString());
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer service-token");
    }

    @Test
    @DisplayName("a query gives up after three seconds when the embedding call hangs")
    void slowQueryTimesOut() {
        server.stubFor(post(urlPathEqualTo(PATH)).willReturn(vectors().withFixedDelay(10_000)));

        long started = System.nanoTime();
        assertThatThrownBy(() -> embeddings.embedQuery(UUID.randomUUID(), "gemini", "m", "annual leave"))
                .isInstanceOf(RuntimeException.class);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(took).isGreaterThanOrEqualTo(EmbeddingService.QUERY_TIMEOUT).isLessThan(Duration.ofSeconds(6));
    }

    @Test
    @DisplayName("a batch of passages is not held to the query's three seconds")
    void batchKeepsItsLongerBudget() {
        // Slower than a query is allowed to be, well inside what ingestion allows.
        server.stubFor(post(urlPathEqualTo(PATH)).willReturn(vectors().withFixedDelay(3_500)));

        List<float[]> embedded = embeddings.embed(UUID.randomUUID(), "gemini", "m", List.of("a passage"));

        assertThat(embedded).hasSize(1);
        assertThat(embedded.get(0)).containsExactly(0.5f, 0.25f);
    }
}
