// @find: tests for Qdrant client, vector store requests, batched upserts, delete by document, delete by source, collection names, search timeout, knowledge base vectors
// @what: Checks what is actually sent to the vector store: bounded batches and deletions that stay inside their source.
package os.aiworkforce.knowledge.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.platform.error.ApiException;

/**
 * What the vector store is actually sent: bounded batches, deletions that never reach past their
 * target, and searches that filter by workspace and source and give up quickly.
 */
class QdrantClientTest {

    private static final String COLLECTION = "aiwos_test_d4";
    private static final String OK = "{\"result\":{\"status\":\"completed\"},\"status\":\"ok\"}";

    private final ObjectMapper json = new ObjectMapper();
    private WireMockServer server;
    private QdrantClient client;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        server.stubFor(put(anyUrl()).willReturn(okJson()));
        server.stubFor(post(anyUrl()).willReturn(okJson()));
        client = new QdrantClient(WebClient.builder(), server.baseUrl(), json);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder okJson() {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(OK);
    }

    private static List<QdrantClient.Point> points(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new QdrantClient.Point(
                        UUID.randomUUID(), new float[] {0.1f, 0.2f, 0.3f, 0.4f}, Map.of("position", i)))
                .toList();
    }

    private List<JsonNode> bodies(List<LoggedRequest> requests) {
        List<JsonNode> bodies = new ArrayList<>();
        for (LoggedRequest request : requests) {
            try {
                bodies.add(json.readTree(request.getBodyAsString()));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
        return bodies;
    }

    @Test
    @DisplayName("a large document is written in batches of 256 points, every point once")
    void upsertIsBatched() {
        List<QdrantClient.Point> points = points(600);

        client.upsert(COLLECTION, points);

        List<LoggedRequest> requests =
                server.findAll(putRequestedFor(urlPathEqualTo("/collections/" + COLLECTION + "/points")));
        List<Integer> sizes = bodies(requests).stream()
                .map(body -> body.path("points").size())
                .toList();
        assertThat(sizes).containsExactly(256, 256, 88);
        assertThat(requests).allSatisfy(request -> assertThat(request.queryParameter("wait").firstValue())
                .isEqualTo("true"));

        List<String> sent = bodies(requests).stream()
                .flatMap(body -> java.util.stream.StreamSupport.stream(body.path("points").spliterator(), false))
                .map(point -> point.path("id").asText())
                .toList();
        assertThat(sent).containsExactlyElementsOf(points.stream()
                .map(point -> point.chunkId().toString())
                .toList());
    }

    @Test
    @DisplayName("nothing to write sends nothing")
    void emptyUpsert() {
        client.upsert(COLLECTION, List.of());

        assertThat(server.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("a failed batch fails the write, so the caller can report it")
    void failedBatch() {
        server.stubFor(put(anyUrl()).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.upsert(COLLECTION, points(3))).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("retiring replaced passages deletes exactly their ids")
    void deletePointsById() {
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());

        client.deletePoints(COLLECTION, ids);

        List<LoggedRequest> requests =
                server.findAll(postRequestedFor(urlPathEqualTo("/collections/" + COLLECTION + "/points/delete")));
        assertThat(requests).hasSize(1);
        JsonNode body = bodies(requests).get(0);
        assertThat(body.has("filter")).isFalse();
        assertThat(body.path("points")).extracting(JsonNode::asText)
                .containsExactly(ids.get(0).toString(), ids.get(1).toString());
    }

    @Test
    @DisplayName("no ids, no request; and a store that is down is logged rather than thrown")
    void deletePointsQuietly() {
        client.deletePoints(COLLECTION, List.of());
        assertThat(server.getAllServeEvents()).isEmpty();

        server.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(503)));
        client.deletePoints(COLLECTION, List.of(UUID.randomUUID()));
    }

    @Test
    @DisplayName("deleting a source filters on that source, and never drops the shared collection")
    void deleteBySourceFilters() {
        UUID sourceId = UUID.randomUUID();

        client.deleteBySource(COLLECTION, sourceId);

        assertThat(server.getAllServeEvents())
                .allSatisfy(event -> assertThat(event.getRequest().getMethod().getName()).isNotEqualTo("DELETE"));
        List<LoggedRequest> requests =
                server.findAll(postRequestedFor(urlPathEqualTo("/collections/" + COLLECTION + "/points/delete")));
        assertThat(requests).hasSize(1);
        JsonNode must = bodies(requests).get(0).path("filter").path("must").get(0);
        assertThat(must.path("key").asText()).isEqualTo("sourceId");
        assertThat(must.path("match").path("value").asText()).isEqualTo(sourceId.toString());
    }

    @Test
    @DisplayName("deleting a document filters on that document")
    void deleteByDocumentFilters() {
        UUID documentId = UUID.randomUUID();

        client.deleteByDocument(COLLECTION, documentId);

        JsonNode must = bodies(server.findAll(
                                postRequestedFor(urlPathEqualTo("/collections/" + COLLECTION + "/points/delete"))))
                .get(0)
                .path("filter")
                .path("must")
                .get(0);
        assertThat(must.path("key").asText()).isEqualTo("documentId");
        assertThat(must.path("match").path("value").asText()).isEqualTo(documentId.toString());
    }

    @Test
    @DisplayName("a search filters by workspace and by source, both under must, never should")
    void searchFiltersBySource() {
        server.stubFor(post(urlPathEqualTo("/collections/" + COLLECTION + "/points/search"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"result\":[],\"status\":\"ok\"}")));
        UUID org = UUID.randomUUID();
        List<UUID> sources = List.of(UUID.randomUUID(), UUID.randomUUID());

        client.search(COLLECTION, org, sources, new float[] {0.1f, 0.2f, 0.3f, 0.4f}, 12, 0.25);

        JsonNode filter = bodies(server.findAll(
                                postRequestedFor(urlPathEqualTo("/collections/" + COLLECTION + "/points/search"))))
                .get(0)
                .path("filter");
        assertThat(filter.has("should")).isFalse();
        JsonNode must = filter.path("must");
        assertThat(must).hasSize(2);
        assertThat(must.get(0).path("key").asText()).isEqualTo("orgId");
        assertThat(must.get(0).path("match").path("value").asText()).isEqualTo(org.toString());
        assertThat(must.get(1).path("key").asText()).isEqualTo("sourceId");
        assertThat(must.get(1).path("match").path("any"))
                .extracting(JsonNode::asText)
                .containsExactly(sources.get(0).toString(), sources.get(1).toString());
    }

    @Test
    @DisplayName("a search over no sources sends nothing")
    void searchOverNoSources() {
        assertThat(client.search(COLLECTION, UUID.randomUUID(), List.of(), new float[] {0.1f}, 5, null)).isEmpty();
        assertThat(server.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("a store that hangs fails a search after 2.5 s, not after the 30 s a write is given")
    void slowSearchTimesOut() {
        server.stubFor(post(urlPathEqualTo("/collections/" + COLLECTION + "/points/search"))
                .willReturn(okJson().withFixedDelay(10_000)));

        long started = System.nanoTime();
        assertThatThrownBy(() -> client.search(
                        COLLECTION, UUID.randomUUID(), List.of(UUID.randomUUID()), new float[] {0.1f}, 5, null))
                .isInstanceOf(ApiException.class);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(took).isGreaterThanOrEqualTo(QdrantClient.SEARCH_TIMEOUT).isLessThan(Duration.ofSeconds(5));
    }
}
