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
    /**
     * Builds a client for the memory service.
     *
     * @param builder Spring's shared WebClient builder
     * @param properties platform configuration: memory service URL
     * @param tokens mints the per-workspace service token
     */
    public MemoryClient(WebClient.Builder builder, PlatformProperties properties, InternalTokenProvider tokens) {
        this.client = builder.baseUrl(properties.services().memory()).build();
        this.tokens = tokens;
    }

    /** One note as the memory service returns it. Carried inside {@link Remembered} and {@link Recalled}, and read by AgentMemoryTool. */
    public record Note(UUID id, String kind, String content, String source) {}

    /**
     * The outcome of keeping a note: kept, refused with a reason, or the service unavailable. Built
     * only through the three factories below and read by AgentMemoryTool, which reports it to the model.
     *
     * @param note the stored note; null unless it was kept
     * @param created the memory service's {@code created} flag: true for a new note, false when the same
     *     note was already stored and only its date moved; false unless it was kept
     * @param refused why the note was not kept, in words for the model; null when it was kept
     * @param unavailable true when the memory service could not be asked or gave no usable answer
     */
    public record Remembered(Note note, boolean created, String refused, boolean unavailable) {

        // The note was stored.
        static Remembered keptNote(Note note, boolean created) {
            return new Remembered(note, created, null, false);
        }

        // The memory service refused the note (validation); the reason is passed to the model.
        static Remembered refusedWith(String why) {
            return new Remembered(null, false, why, false);
        }

        // The memory service could not be asked, or answered with an error that is not the note's fault.
        static Remembered notAvailable() {
            return new Remembered(null, false, null, true);
        }
    }

    /**
     * The outcome of recalling notes. A failure and an empty result are different: {@code failed}
     * tells the caller the service could not be asked, so it does not claim there is nothing to recall.
     *
     * @param notes the notes found, never null
     * @param failed true when the memory service could not be asked
     */
    public record Recalled(List<Note> notes, boolean failed) {

        // An empty result flagged as failed.
        static Recalled couldNotRecall() {
            return new Recalled(List.of(), true);
        }
    }

    // Wire shape of the remember response.
    private record RememberedBody(Note memory, boolean created) {}

    // Wire shape of the recall response.
    private record RecalledBody(List<Note> memories) {}

    // @find: save note to memory service
    /**
     * Asks the memory service to keep a note for one agent.
     *
     * <p>Flow: posts the content (plus kind and run id when given) to the agent's remember endpoint with a
     * service token for the workspace, waiting up to 8 seconds. A 4xx answer is a refusal whose own
     * wording is passed on; a 5xx, a timeout, an empty body or a token failure is reported as
     * unavailable and logged. Side effect: a note is written by the memory service. Never throws.
     *
     * @param orgId the workspace
     * @param agentId the agent the note belongs to
     * @param runId the run that produced the note; null to omit
     * @param kind the note kind; null to let the service choose
     * @param content the note text
     * @return the outcome; never null
     */
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
    /**
     * Asks the memory service for the agent's notes that best match a query.
     *
     * <p>Read only. The query is first cut to the service's length limit by {@link #recallQuery}. Waits
     * up to 5 seconds. Any failure, or an empty body, is returned as a failed result and logged. Never
     * throws.
     *
     * @param orgId the workspace
     * @param agentId the agent whose notes are searched
     * @param query the text to match, for example the user's request; null is treated as empty
     * @param limit the most notes to return
     * @return the notes found, or a failed result; never null
     */
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
