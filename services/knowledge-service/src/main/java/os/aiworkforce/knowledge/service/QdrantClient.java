// @find: knowledge base, knowledge, documents, sources, Qdrant, vector store, vector database, vector search, collection, upsert vectors, delete vectors, delete by document, delete by source, semantic search, search by meaning, reachable, health, QdrantClient
// @what: REST client for the Qdrant vector database: creates collections, writes, searches and deletes vector points.
// @flow: Called by IngestionService (write/delete), RetrievalService (search) and KnowledgeController.health.
package os.aiworkforce.knowledge.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    /** For writes and collection setup, which legitimately take a while on a large document. */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /**
     * For a search, which is on a person's request path. Covers connecting too, so a store that
     * accepts nothing at all is given up on as quickly as one that answers slowly.
     */
    static final Duration SEARCH_TIMEOUT = Duration.ofMillis(2_500);

    /** Points per upsert request, comfortably inside the store's request size limit. */
    static final int UPSERT_BATCH = 256;

    /** Ids per delete request. An id is a few dozen bytes, so this is about bounding the body. */
    static final int DELETE_BATCH = 1_000;

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

    // @find: collection name per workspace
    /** Collection names carry the dimension, so a mismatched model cannot corrupt an index. */
    public static String collectionFor(String prefix, UUID orgId, int dimension) {
        return prefix + "_" + orgId.toString().replace("-", "") + "_d" + dimension;
    }

    // @find: collection name per embedding model
    /**
     * The collection for one embedding model in one workspace. The model is part of the name, not
     * only the width: two models of the same width put text in unrelated spaces, so a query
     * embedded by one compared with passages embedded by the other returns confident nonsense,
     * and a change of model must start an empty collection rather than mix the two.
     */
    public static String collectionFor(String prefix, UUID orgId, String provider, String model, int dimension) {
        String slug = (provider + "_" + model).toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        slug = slug.replaceAll("^_+|_+$", "");
        if (slug.length() > 60) {
            // Long ids are cut, with a hash of the whole id so two long ids cannot meet.
            slug = slug.substring(0, 51) + "_" + Integer.toHexString((provider + "/" + model).hashCode());
        }
        return prefix + "_" + orgId.toString().replace("-", "") + "_" + slug + "_d" + dimension;
    }

    // @find: create qdrant collection, where vectors are created
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
                    : existing.path("result")
                            .path("config")
                            .path("params")
                            .path("vectors")
                            .path("size")
                            .asInt(0);

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

    // @find: write vectors to qdrant, index chunks, update vectors
    /**
     * Writes points in batches of {@value #UPSERT_BATCH}.
     *
     * <p>One request per document worked until a large one: at about 18 KB a point for a
     * 1536-wide vector, a document of 1,700 or more passages went over the store's 32 MB request
     * limit or its timeout, and was reported as "vector store unavailable" when the store was fine.
     * A failed batch fails the call; the batches before it stay written, and they belong to
     * passages that exist, so they are correct rather than orphaned.
     */
    public void upsert(String collection, List<Point> points) {
        for (int start = 0; start < points.size(); start += UPSERT_BATCH) {
            upsertBatch(collection, points.subList(start, Math.min(points.size(), start + UPSERT_BATCH)));
        }
    }

    private void upsertBatch(String collection, List<Point> points) {
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

    // @find: vector search, semantic search qdrant, search by meaning
    /**
     * Searches within one workspace, and within the sources the caller may read.
     *
     * <p>Both filters are applied by the store, not by filtering results afterwards. Post-filtering
     * would mean asking for ten results, discarding the eight belonging to other workspaces or to
     * sources the caller may not see, and returning two - and would briefly hold text the caller may
     * not read in memory. Both conditions sit under {@code must}: a {@code should} clause would make
     * the tenant filter one option among two, so a source match alone could cross workspaces.
     *
     * <p>Bounded by {@link #SEARCH_TIMEOUT}, far shorter than the writes allow. A search is on a
     * person's request path, and a store that hangs must cost the meaning-based half of the answer,
     * not the whole answer.
     *
     * @param sourceIds the sources to search; none means nothing to search, and nothing is sent
     */
    public List<Hit> search(
            String collection,
            UUID orgId,
            Collection<UUID> sourceIds,
            float[] queryVector,
            int limit,
            Double minScore) {
        if (sourceIds.isEmpty()) {
            return List.of();
        }
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

        ArrayNode must = body.putObject("filter").putArray("must");
        ObjectNode tenant = must.addObject();
        tenant.put("key", "orgId");
        tenant.putObject("match").put("value", orgId.toString());
        ObjectNode sources = must.addObject();
        sources.put("key", "sourceId");
        ArrayNode any = sources.putObject("match").putArray("any");
        sourceIds.forEach(id -> any.add(id.toString()));

        try {
            JsonNode response = client.post()
                    .uri("/collections/{collection}/points/search", collection)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(SEARCH_TIMEOUT)
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

    // @find: delete vectors of a document, remove document from search
    /** Removes every vector for a document, used when a document is deleted. */
    public void deleteByDocument(String collection, UUID documentId) {
        deleteMatching(collection, "documentId", documentId, "document");
    }

    // @find: delete vectors of a source, remove source from search
    /**
     * Removes every vector for a source, used when a whole source is deleted.
     *
     * <p>By filter, never by dropping the collection: one collection holds every source of a
     * workspace that shares an embedding width, so dropping it would erase the other sources too.
     */
    public void deleteBySource(String collection, UUID sourceId) {
        deleteMatching(collection, "sourceId", sourceId, "source");
    }

    // @find: delete specific vector points
    /**
     * Removes specific points, used to retire the passages a re-indexed document replaced, or the
     * ones just written for a document whose indexing then failed.
     *
     * <p>A failure is logged rather than thrown. Retrieval resolves every hit against Postgres and
     * drops ids with no passage behind them, so a point left here costs a search slot until the
     * next clean-up, never a citation of text that is gone.
     */
    public void deletePoints(String collection, List<UUID> ids) {
        for (int start = 0; start < ids.size(); start += DELETE_BATCH) {
            List<UUID> batch = ids.subList(start, Math.min(ids.size(), start + DELETE_BATCH));
            ObjectNode body = json.createObjectNode();
            ArrayNode points = body.putArray("points");
            batch.forEach(id -> points.add(id.toString()));
            try {
                postDelete(collection, body);
            } catch (RuntimeException e) {
                log.warn("{} vector(s) could not be deleted from {}: {}", batch.size(), collection, e.getMessage());
            }
        }
    }

    private void deleteMatching(String collection, String key, UUID value, String what) {
        ObjectNode body = json.createObjectNode();
        ObjectNode filter = body.putObject("filter");
        ObjectNode must = filter.putArray("must").addObject();
        must.put("key", key);
        must.putObject("match").put("value", value.toString());

        try {
            postDelete(collection, body);
        } catch (RuntimeException e) {
            // The rows are gone from Postgres regardless, and retrieval resolves every hit there, so
            // this failure delays reclaiming space rather than exposing deleted content.
            log.warn("Vectors for {} {} could not be deleted: {}", what, value, e.getMessage());
        }
    }

    private void postDelete(String collection, ObjectNode body) {
        client.post()
                .uri(builder -> builder.path("/collections/{collection}/points/delete")
                        .queryParam("wait", "true")
                        .build(collection))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(TIMEOUT)
                .block();
    }

    // @find: is qdrant up, vector store health check
    public boolean isReachable() {
        try {
            return client.get()
                            .uri("/healthz")
                            .retrieve()
                            .bodyToMono(String.class)
                            .timeout(Duration.ofSeconds(5))
                            .map(body -> true)
                            .onErrorReturn(false)
                            .block()
                    == Boolean.TRUE;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
