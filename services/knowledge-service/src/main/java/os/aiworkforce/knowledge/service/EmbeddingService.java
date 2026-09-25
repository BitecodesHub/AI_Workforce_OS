package os.aiworkforce.knowledge.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.platform.config.PlatformProperties;
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
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    /** Matches the ceiling the orchestrator accepts; a larger batch is rejected whole. */
    private static final int BATCH_SIZE = 96;

    private final WebClient client;
    private final InternalTokenProvider tokens;

    public EmbeddingService(WebClient.Builder builder, PlatformProperties properties, InternalTokenProvider tokens) {
        this.client = builder.baseUrl(properties.services().orchestrator()).build();
        this.tokens = tokens;
    }

    private record EmbedRequest(String providerId, String modelId, List<String> texts) {}

    private record EmbedResponse(List<float[]> vectors, int dimension) {}

    /** Embeds one query, for retrieval. */
    public float[] embedOne(UUID orgId, String text) {
        List<float[]> vectors = embed(orgId, "sandbox", "sandbox-embed-1", List.of(text));
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
        if (texts.isEmpty()) {
            return List.of();
        }

        List<float[]> all = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += BATCH_SIZE) {
            List<String> batch = texts.subList(start, Math.min(texts.size(), start + BATCH_SIZE));
            EmbedResponse response = client.post()
                    .uri("/internal/embeddings")
                    .header("X-Workspace-Id", orgId.toString())
                    .header("Authorization", "Bearer " + tokens.forService("orchestrator"))
                    .bodyValue(new EmbedRequest(providerId, modelId, batch))
                    .retrieve()
                    .bodyToMono(EmbedResponse.class)
                    .timeout(Duration.ofSeconds(90))
                    .block();

            if (response == null
                    || response.vectors() == null
                    || response.vectors().size() != batch.size()) {
                throw new ApiException(
                        ErrorCode.UPSTREAM_ERROR, "The embedding service returned an unexpected number of vectors.");
            }
            all.addAll(response.vectors());
        }
        log.debug("Embedded {} passage(s) with {}/{}", all.size(), providerId, modelId);
        return all;
    }

    /** Diagnostic detail for the ingestion screen when embedding is unavailable. */
    public Map<String, String> describeFailure(Throwable error) {
        return Map.of("reason", error.getClass().getSimpleName(), "service", "orchestrator");
    }
}

// Week 1 update by fahim0-3

// Week 2 update by fahim0-3

// Week 3 update by fahim0-3

// Week 4 update by fahim0-3
