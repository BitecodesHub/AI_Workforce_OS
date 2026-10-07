package os.aiworkforce.knowledge.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Turns text into vectors, by asking the orchestrator.
 *
 * <p>The knowledge service deliberately does not talk to model providers itself. The orchestrator
 * owns provider configuration, credentials and spend accounting, and a second service reaching
 * providers directly would mean two copies of all three - and a workspace that swapped vendors in
 * one place and not the other.
 *
 * <p>Batching matters here in a way it does not for chat: ingesting a corpus is tens of thousands
 * of short passages, and one request each would be both slow and, on a metered provider,
 * needlessly expensive.
 *
 * <p>Embedding is spending, and the orchestrator books it against whatever agent and run the
 * request names. This service sends them whenever it has them, so a search made during a run
 * shows up in that run's cost and in its agent's spend instead of in the workspace's unowned
 * total. A caller that knows them passes them; otherwise they are taken from the request being
 * served: the agent is the acting agent of the token, and the run is the {@value #RUN_ATTRIBUTE}
 * attribute of the request context, when something earlier in the request set one. Ingesting a
 * document is no agent's work, so it has neither and is booked to the workspace alone. The
 * orchestrator checks both belong to the workspace of the token before it books anything, so
 * naming one here cannot spend against another workspace.
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    /** Matches the ceiling the orchestrator accepts; a larger batch is rejected whole. */
    private static final int BATCH_SIZE = 96;

    /** For a batch of passages during ingestion, which a slow provider can take a minute over. */
    private static final Duration BATCH_TIMEOUT = Duration.ofSeconds(90);

    /**
     * For one search query, which is on a person's request path. A query that cannot be embedded
     * in this time costs the meaning-based half of the search, never the keyword half.
     */
    static final Duration QUERY_TIMEOUT = Duration.ofSeconds(3);

    /** The request-context attribute that names the run a request is being served for. */
    public static final String RUN_ATTRIBUTE = "runId";

    private final WebClient client;
    private final InternalTokenProvider tokens;

    public EmbeddingService(WebClient.Builder builder, PlatformProperties properties, InternalTokenProvider tokens) {
        this.client = builder.baseUrl(properties.services().orchestrator()).build();
        this.tokens = tokens;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record EmbedRequest(String providerId, String modelId, List<String> texts, UUID agentId, UUID runId) {}

    private record EmbedResponse(List<float[]> vectors, int dimension) {}

    /**
     * Embeds one search query with the provider and model a collection was built with.
     *
     * <p>The query must be embedded the same way as the passages it is compared with: vectors from
     * two models live in different spaces, and comparing them returns confident nonsense.
     */
    public float[] embedQuery(UUID orgId, String providerId, String modelId, String text) {
        return embedQuery(orgId, providerId, modelId, text, ambientAgent(), ambientRun());
    }

    /** As {@link #embedQuery(UUID, String, String, String)}, booked to the agent and run named; either may be null. */
    public float[] embedQuery(UUID orgId, String providerId, String modelId, String text, UUID agentId, UUID runId) {
        List<float[]> vectors = embedBatch(orgId, providerId, modelId, List.of(text), QUERY_TIMEOUT, agentId, runId);
        if (vectors.isEmpty()) {
            throw new ApiException(ErrorCode.UPSTREAM_ERROR, "The text could not be embedded.");
        }
        return vectors.get(0);
    }

    /**
     * Embeds a list of passages, in batches.
     *
     * <p>A failed batch fails the documents in it rather than the whole ingestion run, so one
     * oversized document does not cost a corpus that was otherwise indexing correctly.
     */
    public List<float[]> embed(UUID orgId, String providerId, String modelId, List<String> texts) {
        return embed(orgId, providerId, modelId, texts, ambientAgent(), ambientRun());
    }

    /** As {@link #embed(UUID, String, String, List)}, booked to the agent and run named; either may be null. */
    public List<float[]> embed(
            UUID orgId, String providerId, String modelId, List<String> texts, UUID agentId, UUID runId) {
        if (texts.isEmpty()) {
            return List.of();
        }

        List<float[]> all = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += BATCH_SIZE) {
            List<String> batch = texts.subList(start, Math.min(texts.size(), start + BATCH_SIZE));
            all.addAll(embedBatch(orgId, providerId, modelId, batch, BATCH_TIMEOUT, agentId, runId));
        }
        log.debug("Embedded {} passage(s) with {}/{}", all.size(), providerId, modelId);
        return all;
    }

    private List<float[]> embedBatch(
            UUID orgId,
            String providerId,
            String modelId,
            List<String> batch,
            Duration timeout,
            UUID agentId,
            UUID runId) {
        EmbedResponse response = client.post()
                .uri("/internal/embeddings")
                .header("X-Workspace-Id", orgId.toString())
                .header("Authorization", "Bearer " + tokens.forService("orchestrator"))
                .bodyValue(new EmbedRequest(providerId, modelId, batch, agentId, runId))
                .retrieve()
                .bodyToMono(EmbedResponse.class)
                .timeout(timeout)
                .block();

        if (response == null || response.vectors() == null || response.vectors().size() != batch.size()) {
            throw new ApiException(
                    ErrorCode.UPSTREAM_ERROR, "The embedding service returned an unexpected number of vectors.");
        }
        return response.vectors();
    }

    /** The agent the current request is acting as, or null when it is a person's or the system's. */
    private static UUID ambientAgent() {
        return RequestContext.actor()
                .filter(Actor::isAgent)
                .map(actor -> uuid(actor.agentId()))
                .orElse(null);
    }

    /** The run the current request is being served for, or null when nothing named one. */
    private static UUID ambientRun() {
        return RequestContext.attribute(RUN_ATTRIBUTE).map(EmbeddingService::uuid).orElse(null);
    }

    /** A UUID in text, or null for anything that is not one: attribution is never worth failing a search. */
    private static UUID uuid(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(text.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Diagnostic detail for the ingestion screen when embedding is unavailable. */
    public Map<String, String> describeFailure(Throwable error) {
        return Map.of("reason", error.getClass().getSimpleName(), "service", "orchestrator");
    }
}
