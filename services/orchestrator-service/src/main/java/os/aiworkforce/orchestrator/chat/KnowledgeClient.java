// @find: knowledge client, search documents from chat, knowledge search, passages, grounded, indexed sources, per-agent knowledge, KnowledgeClient, forward authorization, RAG search from orchestrator
// @what: Asks the knowledge service for passages that support a person's question, using the caller's own authorization.
// @flow: Called by CoordinatorService and PassageRelevance users; calls the knowledge service search and status endpoints.
package os.aiworkforce.orchestrator.chat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import os.aiworkforce.orchestrator.service.InternalTokenProvider;
import os.aiworkforce.platform.config.PlatformProperties;

/**
 * Asks the knowledge service what supports a person's question, on their own behalf.
 *
 * <p>The caller's own {@code Authorization} header is forwarded rather than an internal service
 * token: a document search is something the person themselves is allowed to do or not, and the
 * knowledge service is the one place that decides it, exactly as it would for a request that came
 * to it directly. A search this cannot complete - no permission, the service is down, a timeout -
 * is reported as empty. For a question about documents the coordinator turns that into an honest
 * "unavailable" reply rather than a stack trace; for any other piece of work it simply goes ahead
 * without passages.
 *
 * <p>Work an agent does has no such header: a scheduled run, a task from a goal and a search the
 * agent makes mid-run happen with nobody's request in flight. Those ask the knowledge service's
 * internal endpoint with a service token that names the person the work is for ({@link
 * #searchFor}), and the knowledge service decides what that person may read exactly as it would
 * for their own request. They are never answered from a token that stands for the platform itself.
 */
@Service
public class KnowledgeClient {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeClient.class);
    private static final int DEFAULT_LIMIT = 5;
    /** The knowledge service caps the query at 1,000 characters; a longer one is trimmed before it is sent. */
    private static final int MAX_QUERY_CHARS = 1_000;

    private final WebClient client;
    /** Mints the token an agent's search is made with; null where only a person's own header is used. */
    private final InternalTokenProvider tokens;

    public KnowledgeClient(WebClient.Builder builder, PlatformProperties properties) {
        this(builder, properties, null);
    }

    @Autowired
    public KnowledgeClient(WebClient.Builder builder, PlatformProperties properties, InternalTokenProvider tokens) {
        this.client = builder.baseUrl(properties.services().knowledge()).build();
        this.tokens = tokens;
    }

    /**
     * One passage that matched, with what a person needs to check it.
     *
     * @param sourceId the knowledge source holding the document, for a link to it; null from a
     *     knowledge service that does not send it yet
     * @param restricted whether the source is open only to people who manage knowledge, so a
     *     caller that records this passage where others can read it keeps its text out of that
     *     record; false from a knowledge service that does not send it yet
     * @param similarity how close in meaning the search by meaning found it, from 0 to 1; null when
     *     only the keyword search found it, or from a knowledge service that does not send it yet
     */
    public record Passage(
            UUID chunkId,
            UUID documentId,
            UUID sourceId,
            String documentTitle,
            String uri,
            Integer pageNumber,
            String heading,
            String content,
            double score,
            boolean restricted,
            Double similarity) {

        public Passage(
                UUID chunkId,
                UUID documentId,
                UUID sourceId,
                String documentTitle,
                String uri,
                Integer pageNumber,
                String heading,
                String content,
                double score,
                boolean restricted) {
            this(chunkId, documentId, sourceId, documentTitle, uri, pageNumber, heading, content, score, restricted, null);
        }

        /** A passage from a source everyone in the workspace may read. */
        public Passage(
                UUID chunkId,
                UUID documentId,
                UUID sourceId,
                String documentTitle,
                String uri,
                Integer pageNumber,
                String heading,
                String content,
                double score) {
            this(chunkId, documentId, sourceId, documentTitle, uri, pageNumber, heading, content, score, false);
        }
    }

    /**
     * @param grounded false when nothing matched well enough to support an answer
     * @param degraded true when only part of the search could run - keyword matching without the
     *     search by meaning - so fewer or weaker passages may have come back than usual
     */
    public record SearchResult(List<Passage> passages, boolean grounded, boolean degraded) {}

    // @find: search knowledge for a question, find passages
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
        } catch (WebClientResponseException.Forbidden | WebClientResponseException.Unauthorized refused) {
            // Searching is something this person may simply not be allowed to do. Every piece of
            // work is searched for, so a role without the permission would otherwise fill the log.
            log.debug("Document search refused for workspace {}: {}", orgId, refused.getStatusCode());
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("Document search failed for workspace {}: {}", orgId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Why an agent's search came back with nothing to use. */
    public enum Failure {
        /** Nobody the work is for can be named, or they may not search documents. */
        NOT_ALLOWED,
        /** The search could not be made: the service is down, slow, or could not check who it is for. */
        UNAVAILABLE
    }

    /**
     * What an agent's search came to: passages, or the reason there are none to use.
     *
     * @param result what was found; null when the search could not run
     * @param failure why it could not; null when it ran
     */
    public record AgentSearch(SearchResult result, Failure failure) {

        public static AgentSearch found(SearchResult result) {
            return new AgentSearch(result, null);
        }

        public static AgentSearch failed(Failure failure) {
            return new AgentSearch(null, failure);
        }
    }

    /**
     * Searches the workspace's documents for an agent, as the person the work is for.
     *
     * <p>Never throws. The knowledge service answers 403 when that person may not search - or may
     * not be searched for, as with work that has nobody behind it - and anything else that goes
     * wrong is reported as unavailable, so a model is told the truth either way and the run goes on.
     *
     * @param requestedBy the person the work is for: the goal's requester, or a schedule's owner
     * @param agentId the agent searching, so the knowledge service can record who asked
     * @param runId the run searching
     * @param limit how many passages at most
     */
    // @find: search knowledge for an agent, agent-specific sources
    public AgentSearch searchFor(
            UUID orgId, UUID requestedBy, String query, int limit, UUID agentId, UUID runId) {
        if (tokens == null || requestedBy == null) {
            return AgentSearch.failed(Failure.NOT_ALLOWED);
        }
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("query", truncate(query));
            body.put("limit", limit);
            if (agentId != null) {
                body.put("agentId", agentId.toString());
            }
            if (runId != null) {
                body.put("runId", runId.toString());
            }
            SearchResult result = client.post()
                    .uri("/internal/knowledge/search")
                    .header("Authorization", "Bearer " + tokens.forRequester("knowledge", orgId, requestedBy))
                    .header("X-Workspace-Id", orgId.toString())
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(SearchResult.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();
            return result == null ? AgentSearch.failed(Failure.UNAVAILABLE) : AgentSearch.found(result);
        } catch (WebClientResponseException.Forbidden | WebClientResponseException.Unauthorized refused) {
            log.debug("Agent document search refused for workspace {}: {}", orgId, refused.getStatusCode());
            return AgentSearch.failed(Failure.NOT_ALLOWED);
        } catch (RuntimeException e) {
            log.warn("Agent document search failed for workspace {}: {}", orgId, e.getMessage());
            return AgentSearch.failed(Failure.UNAVAILABLE);
        }
    }

    /** @param indexedSources how many sources in the workspace hold at least one passage */
    private record Status(int indexedSources) {}

    /**
     * Whether the workspace has any indexed documents, or empty when that could not be found out.
     * A fact about the workspace, so no person is named; the caller holds the answer for a short
     * while rather than asking before every run.
     */
    // @find: has indexed sources, workspace has documents
    public Optional<Boolean> hasIndexedSources(UUID orgId) {
        return hasIndexedSources(orgId, null);
    }

    /** As {@link #hasIndexedSources(UUID)}, counting the documents this agent owns as well. */
    // @find: has indexed sources for agent
    public Optional<Boolean> hasIndexedSources(UUID orgId, UUID agentId) {
        if (tokens == null) {
            return Optional.empty();
        }
        try {
            Status status = client.get()
                    .uri(agentId == null
                            ? "/internal/knowledge/status"
                            : "/internal/knowledge/status?agentId=" + agentId)
                    .header("Authorization", "Bearer " + tokens.forService("knowledge", orgId))
                    .header("X-Workspace-Id", orgId.toString())
                    .retrieve()
                    .bodyToMono(Status.class)
                    .timeout(Duration.ofSeconds(3))
                    .block();
            return status == null ? Optional.empty() : Optional.of(status.indexedSources() > 0);
        } catch (RuntimeException e) {
            log.debug("Could not tell whether workspace {} has indexed documents: {}", orgId, e.getMessage());
            return Optional.empty();
        }
    }

    private static String truncate(String query) {
        return CoordinatorService.truncateAtWord(query, MAX_QUERY_CHARS);
    }
}
