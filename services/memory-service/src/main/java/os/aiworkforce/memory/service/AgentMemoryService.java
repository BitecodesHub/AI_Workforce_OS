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

    /**
     * Validation message returned when a note looks like a secret; it tells the person to describe
     * the purpose of the value without the value.
     */
    public static final String SECRET_REFUSED =
            "That looks like a password, key or card number. Memory is read back into prompts and shown to people,"
                    + " so it must not hold secrets. Say what it is for, without the value.";

    /** Words of three or more letters or digits, used to build the search query. */
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9]{3,}");
    /** Control characters other than newline and tab, which are stripped from note text. */
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");

    private final AgentMemories memories;

    /** Creates the service over the agent memories repository. */
    public AgentMemoryService(AgentMemories memories) {
        this.memories = memories;
    }

    /**
     * Result of {@link #add}: the stored note and whether it is new.
     *
     * @param created false when the same note was already there and only its date moved
     */
    public record Saved(AgentMemory memory, boolean created) {}

    /**
     * Lists an agent's notes, pinned first and then newest change first. Called by AgentMemoryController.
     *
     * @param limit requested page size, clamped to 1 through {@link #MAX_PER_AGENT}
     */
    @Transactional(readOnly = true)
    public List<AgentMemory> list(UUID orgId, UUID agentId, int limit) {
        int capped = Math.min(Math.max(limit, 1), MAX_PER_AGENT);
        return memories.findByOrgIdAndAgentIdOrderByPinnedDescUpdatedAtDesc(orgId, agentId, PageRequest.of(0, capped));
    }

    /** Counts the notes an agent holds in the workspace; used for the total shown on its page. */
    @Transactional(readOnly = true)
    public long count(UUID orgId, UUID agentId) {
        return memories.countByOrgIdAndAgentId(orgId, agentId);
    }

    /**
     * Keeps a note for an agent. Called by AgentMemoryController (source person) and
     * InternalAgentMemoryController (source agent).
     *
     * Flow: clean the text (strips control characters, rejects empty, over-long or secret-looking text),
     * default and check the kind, check the source, and look for the same note already stored. A duplicate
     * is only touched so its date moves, and returned with created false. Otherwise the per-agent cap is
     * checked and a new note is saved. Side effect: one insert or update.
     *
     * @param kind fact, preference, instruction or note; blank means fact
     * @param source agent or person
     * @param by identifier of whoever wrote it, stored as creator
     * @param runId run in which an agent kept it; may be null
     * @return the stored note and whether it was newly created
     * @throws ApiException validation error for bad text, kind or source; conflict when the agent is at the cap
     */
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

    /**
     * Corrects an existing note. Called by AgentMemoryController. A null kind keeps the current kind; the
     * text is cleaned and secret-checked as in {@link #add}. Side effect: one update that records who
     * changed it and when.
     *
     * @throws ApiException not found when the note is not this agent's in this workspace; validation errors for bad text
     */
    @Transactional
    // @find: update agent memory note, edit note
    public AgentMemory update(UUID orgId, UUID agentId, UUID id, String kind, String content, String by) {
        AgentMemory memory = memories
                .findByIdAndOrgIdAndAgentId(id, orgId, agentId)
                .orElseThrow(() -> ApiException.notFound("memory", id));
        memory.change(kind == null ? memory.getKind() : cleanKind(kind), cleanContent(content), by);
        return memories.save(memory);
    }

    /**
     * Deletes one note, pinned or not. Called by AgentMemoryController. Side effect: one delete.
     *
     * @throws ApiException not found when the note is not this agent's in this workspace
     */
    @Transactional
    // @find: delete agent memory note, forget one
    public void delete(UUID orgId, UUID agentId, UUID id) {
        AgentMemory memory = memories
                .findByIdAndOrgIdAndAgentId(id, orgId, agentId)
                .orElseThrow(() -> ApiException.notFound("memory", id));
        memories.delete(memory);
    }

    /**
     * Pins a note so it is always recalled, or unpins it. Called by AgentMemoryController. Side
     * effect: one update of the pin flag and its date; the note's text and change date are untouched.
     *
     * @throws ApiException not found when the note is not this agent's in this workspace
     */
    @Transactional
    // @find: pin or unpin agent memory note
    public AgentMemory pin(UUID orgId, UUID agentId, UUID id, boolean pin) {
        AgentMemory memory = memories
                .findByIdAndOrgIdAndAgentId(id, orgId, agentId)
                .orElseThrow(() -> ApiException.notFound("memory", id));
        memory.pin(pin);
        return memories.save(memory);
    }

    /**
     * Forgets every note this agent keeps, pinned ones too; returns how many were removed. Called by
     * AgentMemoryController. Side effect: one bulk delete; episodes are not touched.
     */
    @Transactional
    // @find: forget all agent memories, clear memory
    public int forgetAll(UUID orgId, UUID agentId) {
        return memories.deleteAllOfAgent(orgId, agentId);
    }

    /**
     * The notes that bear on a query, best match first; with no query, the most recently changed.
     * Each one returned counts as recalled.
     *
     * <p>Flow: pinned notes come first, then notes matching any query word, and when nothing matched
     * the newest notes fill the remainder. Duplicates are dropped and the result is capped at 20.
     * Side effect: each returned note has its recall count and time updated and saved. Called by
     * InternalAgentMemoryController.
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

    /**
     * The query's words joined with OR, or null when there are none worth searching for. Only
     * lower-cased words of three or more letters or digits are kept, at most twelve, which also keeps
     * query syntax characters out of the full-text expression.
     */
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

    /**
     * Normalises and validates note text: removes control characters, trims, and rejects empty text,
     * text over {@link AgentMemory#MAX_CONTENT} characters and anything {@link SecretGuard} flags.
     *
     * @return the cleaned text
     * @throws ApiException validation error naming the content field
     */
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

    /**
     * Normalises a note kind to lower case, defaulting a null or blank value to fact.
     *
     * @throws ApiException validation error when the kind is not one of the allowed kinds
     */
    static String cleanKind(String kind) {
        String chosen = kind == null || kind.isBlank() ? "fact" : kind.strip().toLowerCase(Locale.ROOT);
        if (!AgentMemory.KINDS.contains(chosen)) {
            throw ApiException.validation("kind", "must be one of fact, preference, instruction or note");
        }
        return chosen;
    }
}
