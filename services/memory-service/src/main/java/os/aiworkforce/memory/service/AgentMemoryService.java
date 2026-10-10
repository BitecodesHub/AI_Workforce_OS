// @find: agent memory service, add note, update note, delete note, pin note, forget all, recall notes, remember, memory cap per agent, duplicate note, refuse secrets
// @what: Creates, edits, pins, deletes and recalls an AI employee's memory notes within one workspace.
// @flow: Called by AgentMemoryController and InternalAgentMemoryController; uses SecretGuard
package os.aiworkforce.memory.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.memory.domain.AgentMemory;
import os.aiworkforce.memory.repository.AgentMemories;
import os.aiworkforce.platform.error.ApiException;

/**
 * What one AI employee remembers: short notes it keeps while working and people write or correct
 * from its page.
 *
 * <p>Every note belongs to one agent in one workspace, and the workspace always comes from the
 * caller's token. A note that looks like a password, key or card number is refused, because notes
 * are read back into prompts and shown to readers. There is a cap on how many one agent keeps, so a
 * chatty agent cannot bury its useful notes, and the same note written twice is one note.
 */
@Service
public class AgentMemoryService {

    /** The most notes one agent keeps; beyond this it must be tidied by a person. */
    public static final int MAX_PER_AGENT = 200;

    public static final String SECRET_REFUSED =
            "That looks like a password, key or card number. Memory is read back into prompts and shown to people,"
                    + " so it must not hold secrets. Say what it is for, without the value.";

    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9]{3,}");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");

    private final AgentMemories memories;

    public AgentMemoryService(AgentMemories memories) {
        this.memories = memories;
    }

    /** @param created false when the same note was already there and only its date moved */
    public record Saved(AgentMemory memory, boolean created) {}

    @Transactional(readOnly = true)
    public List<AgentMemory> list(UUID orgId, UUID agentId, int limit) {
        int capped = Math.min(Math.max(limit, 1), MAX_PER_AGENT);
        return memories.findByOrgIdAndAgentIdOrderByPinnedDescUpdatedAtDesc(orgId, agentId, PageRequest.of(0, capped));
    }

    @Transactional(readOnly = true)
    public long count(UUID orgId, UUID agentId) {
        return memories.countByOrgIdAndAgentId(orgId, agentId);
    }

    @Transactional
    // @find: add agent memory note, remember, create note
    public Saved add(UUID orgId, UUID agentId, String kind, String content, String source, String by, UUID runId) {
        String clean = cleanContent(content);
        String chosenKind = cleanKind(kind);
        if (!AgentMemory.SOURCES.contains(source)) {
            throw ApiException.validation("source", "must be agent or person");
        }
        List<AgentMemory> same = memories.findSame(orgId, agentId, clean);
        if (!same.isEmpty()) {
            AgentMemory existing = same.get(0);
            existing.touch();
            return new Saved(memories.save(existing), false);
        }
        if (memories.countByOrgIdAndAgentId(orgId, agentId) >= MAX_PER_AGENT) {
            throw ApiException.conflict("This agent's memory is full (" + MAX_PER_AGENT
                    + " notes). Remove or combine some of them from its page first.");
        }
        AgentMemory memory = AgentMemory.of(orgId, agentId, chosenKind, clean, source, by);
        memory.setRunId(runId);
        return new Saved(memories.save(memory), true);
    }

    @Transactional
    // @find: update agent memory note, edit note
    public AgentMemory update(UUID orgId, UUID agentId, UUID id, String kind, String content, String by) {
        AgentMemory memory = memories
                .findByIdAndOrgIdAndAgentId(id, orgId, agentId)
                .orElseThrow(() -> ApiException.notFound("memory", id));
        memory.change(kind == null ? memory.getKind() : cleanKind(kind), cleanContent(content), by);
        return memories.save(memory);
    }

    @Transactional
    // @find: delete agent memory note, forget one
    public void delete(UUID orgId, UUID agentId, UUID id) {
        AgentMemory memory = memories
                .findByIdAndOrgIdAndAgentId(id, orgId, agentId)
                .orElseThrow(() -> ApiException.notFound("memory", id));
        memories.delete(memory);
    }

    /** Pins a note so it is always recalled, or unpins it. */
    @Transactional
    // @find: pin or unpin agent memory note
    public AgentMemory pin(UUID orgId, UUID agentId, UUID id, boolean pin) {
        AgentMemory memory = memories
                .findByIdAndOrgIdAndAgentId(id, orgId, agentId)
                .orElseThrow(() -> ApiException.notFound("memory", id));
        memory.pin(pin);
        return memories.save(memory);
    }

    /** Forgets every note this agent keeps, pinned ones too; returns how many were removed. */
    @Transactional
    // @find: forget all agent memories, clear memory
    public int forgetAll(UUID orgId, UUID agentId) {
        return memories.deleteAllOfAgent(orgId, agentId);
    }

    /**
     * The notes that bear on a query, best match first; with no query, the most recently changed.
     * Each one returned counts as recalled.
     */
    @Transactional
    // @find: recall agent notes, search memory
    public List<AgentMemory> recall(UUID orgId, UUID agentId, String query, int limit) {
        int capped = Math.min(Math.max(limit, 1), 20);
        // Pinned notes come first, whatever the request is about: somebody decided they always matter.
        List<AgentMemory> found = new ArrayList<>(
                memories.findByOrgIdAndAgentIdAndPinnedTrueOrderByPinnedAtDesc(orgId, agentId, PageRequest.of(0, capped)));
        int pinnedCount = found.size();
        String tsQuery = tsQueryOf(query);
        if (tsQuery != null) {
            found.addAll(memories.search(orgId, agentId, tsQuery, capped));
        }
        if (found.size() < capped && (tsQuery == null || found.size() == pinnedCount)) {
            // Nothing matched the words: the newest notes are still the best guess at what matters.
            found.addAll(memories.findByOrgIdAndAgentIdOrderByUpdatedAtDesc(orgId, agentId, PageRequest.of(0, capped)));
        }
        Set<UUID> seen = new LinkedHashSet<>();
        List<AgentMemory> result = new ArrayList<>();
        for (AgentMemory memory : found) {
            if (seen.add(memory.getId()) && result.size() < capped) {
                memory.recalled();
                result.add(memory);
            }
        }
        memories.saveAll(result);
        return result;
    }

    /** The query's words joined with OR, or null when there are none worth searching for. */
    static String tsQueryOf(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        Set<String> words = new LinkedHashSet<>();
        Matcher matcher = WORD.matcher(query.toLowerCase(Locale.ROOT));
        while (matcher.find() && words.size() < 12) {
            words.add(matcher.group());
        }
        return words.isEmpty() ? null : String.join(" | ", words);
    }

    static String cleanContent(String content) {
        String clean = content == null ? "" : CONTROL.matcher(content).replaceAll("").strip();
        if (clean.isEmpty()) {
            throw ApiException.validation("content", "Say what should be remembered.");
        }
        if (clean.length() > AgentMemory.MAX_CONTENT) {
            throw ApiException.validation(
                    "content", "must be at most " + AgentMemory.MAX_CONTENT + " characters; keep one fact per note");
        }
        if (SecretGuard.looksSecret(clean)) {
            throw ApiException.validation("content", SECRET_REFUSED);
        }
        return clean;
    }

    static String cleanKind(String kind) {
        String chosen = kind == null || kind.isBlank() ? "fact" : kind.strip().toLowerCase(Locale.ROOT);
        if (!AgentMemory.KINDS.contains(chosen)) {
            throw ApiException.validation("kind", "must be one of fact, preference, instruction or note");
        }
        return chosen;
    }
}
