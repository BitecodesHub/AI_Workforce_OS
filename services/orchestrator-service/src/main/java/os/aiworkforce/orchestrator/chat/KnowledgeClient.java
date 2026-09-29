package os.aiworkforce.orchestrator.chat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.platform.config.PlatformProperties;

/**
 * Asks the knowledge service what supports a person's question, on their own behalf.
 *
 * <p>The caller's own {@code Authorization} header is forwarded rather than an internal service
 * token: a document search is something the person themselves is allowed to do or not, and the
 * knowledge service is the one place that decides it, exactly as it would for a request that came
 * to it directly. A search this cannot complete - no permission, the service is down, a timeout -
 * is reported as empty; the coordinator turns that into an honest "unavailable" reply rather than
 * a stack trace.
 */
@Service
public class KnowledgeClient {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeClient.class);
    private static final int DEFAULT_LIMIT = 5;
    /** The knowledge service caps the query at 1,000 characters; a longer one is trimmed before it is sent. */
    private static final int MAX_QUERY_CHARS = 1_000;

    private final WebClient client;

    public KnowledgeClient(WebClient.Builder builder, PlatformProperties properties) {
        this.client = builder.baseUrl(properties.services().knowledge()).build();
    }

    public record Passage(
            UUID chunkId,
            UUID documentId,
            String documentTitle,
            String uri,
            Integer pageNumber,
            String heading,
            String content,
            double score) {}

    public record SearchResult(List<Passage> passages, boolean grounded) {}

    public Optional<SearchResult> search(UUID orgId, String query, String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return Optional.empty();
        }
        try {
            SearchResult result = client.post()
                    .uri("/api/knowledge/search")
                    .header("Authorization", authorizationHeader)
                    .header("X-Workspace-Id", orgId.toString())
                    .bodyValue(Map.of("query", truncate(query), "limit", DEFAULT_LIMIT))
                    .retrieve()
                    .bodyToMono(SearchResult.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();
            return Optional.ofNullable(result);
        } catch (RuntimeException e) {
            log.warn("Document search failed for workspace {}: {}", orgId, e.getMessage());
            return Optional.empty();
        }
    }

    private static String truncate(String query) {
        return CoordinatorService.truncateAtWord(query, MAX_QUERY_CHARS);
    }
}
