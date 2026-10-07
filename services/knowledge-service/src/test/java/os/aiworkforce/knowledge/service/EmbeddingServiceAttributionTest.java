package os.aiworkforce.knowledge.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;

/**
 * Embedding is spending, and the orchestrator books it against the agent and the run the request
 * names. The knowledge service sends them whenever it has them, and says nothing when it has not,
 * so ingesting a document is booked to the workspace alone.
 */
class EmbeddingServiceAttributionTest {

    private static final String PATH = "/internal/embeddings";
    private static final String ONE_VECTOR = "{\"vectors\":[[0.5,0.25]],\"dimension\":2}";
    private static final UUID ORG = UUID.randomUUID();

    private final ObjectMapper json = new ObjectMapper();
    private WireMockServer server;
    private EmbeddingService embeddings;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        server.stubFor(post(urlPathEqualTo(PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ONE_VECTOR)));
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
        RequestContext.clear();
        server.stop();
    }

    private JsonNode lastBody() throws Exception {
        var requests = server.findAll(postRequestedFor(urlPathEqualTo(PATH)));
        return json.readTree(requests.get(requests.size() - 1).getBodyAsString());
    }

    private static Actor agent(UUID agentId) {
        return new Actor(
                agentId.toString(),
                Actor.Kind.AGENT,
                ORG.toString(),
                null,
                Set.of(),
                0L,
                UUID.randomUUID().toString(),
                null,
                agentId.toString(),
                Map.of());
    }

    @Test
    @DisplayName("a caller that knows the agent and the run has both sent with the query")
    void explicitAttributionIsSent() throws Exception {
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        embeddings.embedQuery(ORG, "gemini", "m", "annual leave", agentId, runId);

        JsonNode body = lastBody();
        assertThat(body.path("agentId").asText()).isEqualTo(agentId.toString());
        assertThat(body.path("runId").asText()).isEqualTo(runId.toString());
    }

    @Test
    @DisplayName("every batch of passages carries the attribution, not only the first")
    void everyBatchIsAttributed() throws Exception {
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        // 100 passages go as a batch of 96 and a batch of 4; each is answered with as many vectors
        // as it held, so neither is refused as a size mismatch.
        server.stubFor(post(urlPathEqualTo(PATH))
                .withRequestBody(matchingJsonPath("$.texts[3]"))
                .willReturn(vectorsOf(4)));
        server.stubFor(post(urlPathEqualTo(PATH))
                .withRequestBody(matchingJsonPath("$.texts[95]"))
                .willReturn(vectorsOf(96)));
        List<String> passages = IntStream.range(0, 100).mapToObj(i -> "passage " + i).toList();

        List<float[]> embedded = embeddings.embed(ORG, "gemini", "m", passages, agentId, runId);

        assertThat(embedded).hasSize(100);
        var requests = server.findAll(postRequestedFor(urlPathEqualTo(PATH)));
        assertThat(requests).hasSize(2);
        for (var request : requests) {
            JsonNode body = json.readTree(request.getBodyAsString());
            assertThat(body.path("agentId").asText()).isEqualTo(agentId.toString());
            assertThat(body.path("runId").asText()).isEqualTo(runId.toString());
        }
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder vectorsOf(int count) {
        String vectors = IntStream.range(0, count).mapToObj(i -> "[0.5,0.25]").collect(Collectors.joining(","));
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"vectors\":[" + vectors + "],\"dimension\":2}");
    }

    @Test
    @DisplayName("with nothing to attribute, the body names neither, as ingesting a document does")
    void nothingToAttributeSendsNeither() throws Exception {
        embeddings.embedQuery(ORG, "gemini", "m", "annual leave");

        JsonNode body = lastBody();
        assertThat(body.has("agentId")).isFalse();
        assertThat(body.has("runId")).isFalse();
    }

    @Test
    @DisplayName("an agent's own token attributes a search to that agent without being told")
    void actingAgentIsTakenFromTheRequest() throws Exception {
        UUID agentId = UUID.randomUUID();
        RequestContext.setActor(agent(agentId));

        embeddings.embedQuery(ORG, "gemini", "m", "annual leave");

        JsonNode body = lastBody();
        assertThat(body.path("agentId").asText()).isEqualTo(agentId.toString());
        assertThat(body.has("runId")).isFalse();
    }

    @Test
    @DisplayName("a run named in the request context is sent, and one that is not an id is dropped")
    void runIsTakenFromTheRequestContext() throws Exception {
        UUID runId = UUID.randomUUID();
        RequestContext.setAttribute(EmbeddingService.RUN_ATTRIBUTE, runId.toString());
        embeddings.embedQuery(ORG, "gemini", "m", "annual leave");
        assertThat(lastBody().path("runId").asText()).isEqualTo(runId.toString());

        RequestContext.setAttribute(EmbeddingService.RUN_ATTRIBUTE, "not-an-id");
        embeddings.embedQuery(ORG, "gemini", "m", "annual leave");
        assertThat(lastBody().has("runId")).isFalse();
    }

    @Test
    @DisplayName("a person's own token has no agent to attribute")
    void aPersonIsNotAnAgent() throws Exception {
        RequestContext.setActor(Actor.user(UUID.randomUUID().toString(), ORG.toString(), null, Set.of(), 0L));

        embeddings.embedQuery(ORG, "gemini", "m", "annual leave");

        assertThat(lastBody().has("agentId")).isFalse();
    }
}
