// @find: memory client, memory service, remember note, recall notes, agent memory http client, never throws, memory failure
// @what: HTTP client that asks the memory service to keep or recall an agent's notes, reporting failures instead of throwing.
// @flow: Called by AgentMemoryTool
package os.aiworkforce.orchestrator.service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import os.aiworkforce.platform.config.PlatformProperties;

/**
 * Asks the memory service to keep and to recall an agent's own notes.
 *
 * <p>Called with a service token for the workspace, as the agent: a note belongs to one agent and
 * is read back only to it. Never throws. A note that cannot be kept or recalled is reported as a
 * failure the model is told about, so the run goes on without it rather than stopping.
 */
@Service
public class MemoryClient {

    private static final Logger log = LoggerFactory.getLogger(MemoryClient.class);

    private final WebClient client;
    private final InternalTokenProvider tokens;

    @Autowired
    public MemoryClient(WebClient.Builder builder, PlatformProperties properties, InternalTokenProvider tokens) {
        this.client = builder.baseUrl(properties.services().memory()).build();
        this.tokens = tokens;
    }

    /** One note as the memory service returns it. */
    public record Note(UUID id, String kind, String content, String source) {}

    /** @param refused why the note was not kept, in words for the model; null when it was kept */
    public record Remembered(Note note, boolean created, String refused, boolean unavailable) {

        static Remembered keptNote(Note note, boolean created) {
            return new Remembered(note, created, null, false);
        }

        static Remembered refusedWith(String why) {
            return new Remembered(null, false, why, false);
        }

        static Remembered notAvailable() {
            return new Remembered(null, false, null, true);
        }
    }

    /** @param failed true when the memory service could not be asked */
    public record Recalled(List<Note> notes, boolean failed) {

        static Recalled couldNotRecall() {
            return new Recalled(List.of(), true);
        }
    }

    private record RememberedBody(Note memory, boolean created) {}

    private record RecalledBody(List<Note> memories) {}

    // @find: save note to memory service
    public Remembered remember(UUID orgId, UUID agentId, UUID runId, String kind, String content) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("content", content);
            if (kind != null) {
                body.put("kind", kind);
            }
            if (runId != null) {
                body.put("runId", runId.toString());
            }
            RememberedBody saved = client.post()
                    .uri("/internal/memory/agents/{agentId}/remember", agentId)
                    .header("Authorization", "Bearer " + tokens.forService("memory", orgId))
                    .header("X-Workspace-Id", orgId.toString())
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(RememberedBody.class)
                    .timeout(Duration.ofSeconds(8))
                    .block();
            return saved == null ? Remembered.notAvailable() : Remembered.keptNote(saved.memory(), saved.created());
        } catch (WebClientResponseException refused) {
            if (refused.getStatusCode().is4xxClientError()) {
                // A validation answer (a secret, too long, memory full) is written for the person
                // or the model to read; it is passed on, not hidden behind "unavailable".
                return Remembered.refusedWith(readableReason(refused));
            }
            log.warn("Memory service refused a note for agent {}: {}", agentId, refused.getStatusCode());
            return Remembered.notAvailable();
        } catch (RuntimeException e) {
            log.warn("Could not keep a note for agent {}: {}", agentId, e.getMessage());
            return Remembered.notAvailable();
        }
    }

    // @find: recall notes from memory service
    public Recalled recall(UUID orgId, UUID agentId, String query, int limit) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", recallQuery(query));
            body.put("limit", limit);
            RecalledBody found = client.post()
                    .uri("/internal/memory/agents/{agentId}/recall", agentId)
                    .header("Authorization", "Bearer " + tokens.forService("memory", orgId))
                    .header("X-Workspace-Id", orgId.toString())
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(RecalledBody.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();
            return found == null || found.memories() == null ? Recalled.couldNotRecall() : new Recalled(found.memories(), false);
        } catch (RuntimeException e) {
            log.warn("Could not recall notes for agent {}: {}", agentId, e.getMessage());
            return Recalled.couldNotRecall();
        }
    }

    /**
     * The memory service refuses a recall query longer than {@link #MAX_RECALL_QUERY} characters (422),
     * which used to cost every long request its remembered notes. A longer one is cut at a word.
     */
    static String recallQuery(String query) {
        if (query == null) {
            return "";
        }
        String text = query.strip();
        if (text.length() <= MAX_RECALL_QUERY) {
            return text;
        }
        int cut = text.lastIndexOf(' ', MAX_RECALL_QUERY);
        return (cut > MAX_RECALL_QUERY / 2 ? text.substring(0, cut) : text.substring(0, MAX_RECALL_QUERY)).strip();
    }

    /** The longest recall query the memory service accepts (its RecallRequest's own limit). */
    static final int MAX_RECALL_QUERY = 500;

    /** The service's own sentence for a refusal, found in its error body, or a plain default. */
    private static String readableReason(WebClientResponseException refused) {
        String body = refused.getResponseBodyAsString();
        int at = body.indexOf("\"detail\":\"");
        if (at >= 0) {
            int start = at + "\"detail\":\"".length();
            int end = body.indexOf('"', start);
            if (end > start) {
                return body.substring(start, end).replace("\\\"", "\"");
            }
        }
        return "That note could not be kept.";
    }
}
