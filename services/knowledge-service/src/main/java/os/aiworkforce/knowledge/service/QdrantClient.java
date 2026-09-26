package os.aiworkforce.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * The vector store, over its REST API.
 *
 * <p>REST rather than the gRPC client: it is one dependency fewer, the payloads are inspectable
 * when something is wrong, and nothing here is on a hot enough path for the protocol overhead to
 * matter.
 *
 * <p>Collections are named with their embedding dimension. That single convention prevents the
 * worst failure this component can have: a workspace changing embedding model, writing 768-wide
 * vectors into a 1536-wide collection, and getting a silently broken index that looks like poor
 * retrieval rather than a configuration error.
 */
@Service
public class QdrantClient {

    private static final Logger log = LoggerFactory.getLogger(QdrantClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final WebClient client;
    private final ObjectMapper json;

    public QdrantClient(
            WebClient.Builder builder,
            @Value("${QDRANT_URL:http://localhost:6333}") String baseUrl,
            ObjectMapper json) {
        this.client = builder.baseUrl(baseUrl).build();
        this.json = json;
    }

    /**
     * @param chunkId the chunk this vector belongs to, so a hit resolves to a citable passage
     * @param vector the embedding
     * @param payload filters and display fields, including the workspace
     */
    public record Point(UUID chunkId, float[] vector, Map<String, Object> payload) {}

    /**
     * @param chunkId which chunk matched
     * @param score similarity, for ranking and for a confidence signal in the interface
     * @param payload what was stored alongside it
     */
    public record Hit(UUID chunkId, double score, Map<String, Object> payload) {}

    /** Collection names carry the dimension, so a mismatched model cannot corrupt an index. */
    public static String collectionFor(String prefix, UUID orgId, int dimension) {
        return prefix + "_" + orgId.toString().replace("-", "") + "_d" + dimension;
    }

    public void ensureCollection(String collection, int dimension) {
        try {
            JsonNode existing = client.get()
                    .uri("/collections/{collection}", collection)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(TIMEOUT)
                    .onErrorReturn(json.createObjectNode())
                    .block();

            int currentSize = existing == null
                    ? 0
                    : existing.path("result").path("config").path("params").path("vectors").path("size").asInt(0);

            if (currentSize > 0) {
                if (currentSize != dimension) {
                    // Refused rather than repaired. Rebuilding an index silently would discard a
                    // corpus somebody spent hours ingesting.
                    throw new ApiException(ErrorCode.EMBEDDING_DIMENSION_MISMATCH)
                            .with("collection", collection)
                            .with("existingDimension", currentSize)
                            .with("requestedDimension", dimension);
                }
                return;
            }

            ObjectNode body = json.createObjectNode();
            ObjectNode vectors = body.putObject("vectors");
            vectors.put("size", dimension);
            // Cosine, because the embeddings are normalised and cosine is what every provider's
            // similarity is defined against.
            vectors.put("distance", "Cosine");

            client.put()
                    .uri("/collections/{collection}", collection)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(TIMEOUT)
                    .block();

            createPayloadIndexes(collection);
            log.info("Created vector collection {} with {} dimensions", collection, dimension);
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "The vector store is unavailable.", e);
        }
    }

    /**
     * Indexes the fields that every query filters on.
     *
     * <p>Without an index on {@code orgId}, the tenant filter degrades to a scan of the whole
     * collection - which is both slow and, at scale, the sort of slow that makes people ask
     * whether the filter is really being applied.
     */
    private void createPayloadIndexes(String collection) {
        for (String field : List.of("orgId", "sourceId", "documentId")) {
            try {
                client.put()
                        .uri("/collections/{collection}/index", collection)
                        .bodyValue(Map.of("field_name", field, "field_schema", "keyword"))
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .timeout(TIMEOUT)
                        .block();
            } catch (RuntimeException e) {
                log.debug("Payload index on {} could not be created: {}", field, e.getMessage());
            }
        }
    }

    public void upsert(String collection, List<Point> points) {
        if (points.isEmpty()) {
            return;
        }
        ObjectNode body = json.createObjectNode();
        ArrayNode array = body.putArray("points");
        for (Point point : points) {
            ObjectNode node = array.addObject();
            node.put("id", point.chunkId().toString());
            ArrayNode vector = node.putArray("vector");
            for (float value : point.vector()) {
                vector.add(value);
            }
            node.set("payload", json.valueToTree(point.payload()));
        }

        try {
            client.put()
                    .uri(builder -> builder.path("/collections/{collection}/points")
                            // Waits for the write to be visible. Returning before then means a
                            // document reports as indexed and is not yet findable.
                            .queryParam("wait", "true")
                            .build(collection))
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(TIMEOUT)
                    .block();
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.INGESTION_FAILED, "The passages could not be indexed.", e);
        }
    }

    /**
     * Searches within one workspace.
     *
     * <p>The workspace filter is applied by the store, not by filtering results afterwards.
     * Post-filtering would mean asking for ten results, discarding the eight belonging to other
     * workspaces, and returning two - and would briefly hold another tenant's text in memory.
     */
    public List<Hit> search(String collection, UUID orgId, float[] queryVector, int limit, Double minScore) {
        ObjectNode body = json.createObjectNode();
        ArrayNode vector = body.putArray("vector");
        for (float value : queryVector) {
            vector.add(value);
        }
        body.put("limit", limit);
        body.put("with_payload", true);
        if (minScore != null) {
            body.put("score_threshold", minScore);
        }

        ObjectNode filter = body.putObject("filter");
        ObjectNode must = filter.putArray("must").addObject();
        must.put("key", "orgId");
        must.putObject("match").put("value", orgId.toString());

        try {
            JsonNode response = client.post()
                    .uri("/collections/{collection}/points/search", collection)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(TIMEOUT)
                    .block();

            List<Hit> hits = new ArrayList<>();
            if (response != null) {
                for (JsonNode node : response.path("result")) {
                    hits.add(new Hit(
                            UUID.fromString(node.path("id").asText()),
                            node.path("score").asDouble(),
                            json.convertValue(node.path("payload"), Map.class)));
                }
            }
            return hits;
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "The knowledge base is unavailable.", e);
        }
    }

    /** Removes every vector for a document, used when a source document is deleted. */
    public void deleteByDocument(String collection, UUID documentId) {
        ObjectNode body = json.createObjectNode();
        ObjectNode filter = body.putObject("filter");
        ObjectNode must = filter.putArray("must").addObject();
        must.put("key", "documentId");
        must.putObject("match").put("value", documentId.toString());

        try {
            client.post()
                    .uri("/collections/{collection}/points/delete", collection)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(TIMEOUT)
                    .block();
        } catch (RuntimeException e) {
            // The document is tombstoned in Postgres regardless, so retrieval already excludes
            // it. This failure delays reclaiming space rather than exposing deleted content.
            log.warn("Vectors for document {} could not be deleted: {}", documentId, e.getMessage());
        }
    }

    public boolean isReachable() {
        try {
            return client.get()
                    .uri("/healthz")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(5))
                    .map(body -> true)
                    .onErrorReturn(false)
                    .block() == Boolean.TRUE;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
