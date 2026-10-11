// @find: message context, composer plus menu, attached knowledge, attached repository, skill on a message, documentIds, sourceIds, repo chip, skillId, ground this message, queued message context
// @what: What a chat message carries besides its words and files: workspace documents to ground it, a GitHub repository to work on, and a skill to follow.
// @flow: Built by ChatController from the send request; resolved and applied by CoordinatorService via ContextPrompt; stored with a queued message as JSON.
package os.aiworkforce.orchestrator.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import os.aiworkforce.platform.error.ApiException;

/**
 * The things attached to a message from the composer's "+" menu, other than uploaded files.
 *
 * <ul>
 *   <li>{@code knowledge}: workspace sources, or single documents within them, that the message is
 *       grounded in. The coordinator searches only these for the passages the agent is given.
 *   <li>{@code repo}: a GitHub repository (with an optional branch and path) the work is about.
 *   <li>{@code skillId}: a skill whose playbook the run follows; {@code skill} is that skill once
 *       looked up, and null before (or when the message carries none).
 * </ul>
 *
 * <p>Titles and labels come from the person's own browser and are used only as labels: what may
 * be read is decided by the knowledge service and the connector, never by this record.
 */
public record MessageContext(List<KnowledgeRef> knowledge, RepoRef repo, UUID skillId, SkillLookup.ResolvedSkill skill) {

    /** The context of a message that carries nothing; shared so callers can compare cheaply. */
    public static final MessageContext NONE = new MessageContext(List.of(), null, null, null);

    /** The most knowledge references one message may carry. */
    static final int MAX_KNOWLEDGE = 20;

    private static final Pattern OWNER = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$");
    private static final Pattern REPO_NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,100}$");
    private static final Pattern REF = Pattern.compile("^[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*$");
    private static final Pattern PATH = Pattern.compile("^[A-Za-z0-9_. -]+(?:/[A-Za-z0-9_. -]+)*$");

    /** Compact constructor: makes the knowledge list immutable and never null. */
    public MessageContext {
        knowledge = knowledge == null ? List.of() : List.copyOf(knowledge);
    }

    /**
     * A workspace source, or one document in it.
     *
     * @param sourceId the source to search; always given
     * @param documentId one document in that source, or null for the whole source
     * @param title what the person saw when choosing it, for labels only
     */
    public record KnowledgeRef(UUID sourceId, UUID documentId, String title) {}

    /**
     * A GitHub repository.
     *
     * @param branch a branch, or null for the default one
     * @param path a folder or file inside it, or null for the whole repository
     */
    public record RepoRef(String owner, String name, String branch, String path) {

        /** "owner/repo", the form the GitHub tools take. */
        public String fullName() {
            return owner + "/" + name;
        }

        /** "owner/repo@branch/path", as the chip shows it. */
        public String label() {
            StringBuilder label = new StringBuilder(fullName());
            if (branch != null) {
                label.append('@').append(branch);
            }
            if (path != null) {
                label.append(branch == null ? ":" : "/").append(path);
            }
            return label.toString();
        }
    }

    // @find: build message context from request, validate repo and knowledge refs
    /**
     * Checks what the request sent and returns it, or {@link #NONE} when nothing was attached.
     * Blank strings count as absent; anything malformed is refused with the field it was in.
     */
    public static MessageContext of(List<KnowledgeRef> knowledge, RepoRef repo, UUID skillId) {
        List<KnowledgeRef> refs = new ArrayList<>();
        if (knowledge != null) {
            if (knowledge.size() > MAX_KNOWLEDGE) {
                throw ApiException.validation("knowledge", "Choose at most " + MAX_KNOWLEDGE + " documents.");
            }
            Set<String> seen = new LinkedHashSet<>();
            for (KnowledgeRef ref : knowledge) {
                if (ref == null || ref.sourceId() == null) {
                    throw ApiException.validation("knowledge", "Each document must name its source.");
                }
                if (seen.add(ref.sourceId() + ":" + ref.documentId())) {
                    refs.add(new KnowledgeRef(ref.sourceId(), ref.documentId(), cap(blankToNull(ref.title()), 200)));
                }
            }
        }
        RepoRef checked = repo == null ? null : checkRepo(repo);
        if (refs.isEmpty() && checked == null && skillId == null) {
            return NONE;
        }
        return new MessageContext(refs, checked, skillId, null);
    }

    /**
     * Validates and normalises a repository reference: trims blanks to null, checks the owner, name,
     * branch and path against the characters GitHub accepts, strips surrounding slashes from the path
     * and refuses {@code ..}. Used by {@link #of}; performs no network call, so it does not prove the
     * repository exists.
     *
     * @throws ApiException a validation error naming the offending field (repo.owner, repo.name, repo.branch or repo.path)
     */
    private static RepoRef checkRepo(RepoRef repo) {
        String owner = blankToNull(repo.owner());
        String name = blankToNull(repo.name());
        if (owner == null || !OWNER.matcher(owner).matches()) {
            throw ApiException.validation("repo.owner", "Choose a repository owner GitHub would accept.");
        }
        if (name == null || !REPO_NAME.matcher(name).matches() || name.matches("\\.+")) {
            throw ApiException.validation("repo.name", "Choose a repository name GitHub would accept.");
        }
        String branch = blankToNull(repo.branch());
        if (branch != null && (branch.length() > 200 || !REF.matcher(branch).matches())) {
            throw ApiException.validation("repo.branch", "The branch name has characters that cannot be used.");
        }
        String path = blankToNull(repo.path());
        if (path != null) {
            path = path.replaceAll("^/+|/+$", "");
            if (path.isEmpty()) {
                path = null;
            } else if (path.length() > 400 || !PATH.matcher(path).matches() || path.contains("..")) {
                throw ApiException.validation("repo.path", "The path has characters that cannot be used.");
            }
        }
        return new RepoRef(owner, name, branch, path);
    }

    /** Whether nothing at all is attached: no knowledge, no repository and no skill. */
    public boolean isEmpty() {
        return knowledge.isEmpty() && repo == null && skillId == null;
    }

    /**
     * Whether the message is always work for an agent, like a message with files: the documents,
     * repository or playbook attached are what it is about, and only an agent's run uses them.
     */
    public boolean forcesWork() {
        return !isEmpty();
    }

    /** The same context with its skill looked up. */
    public MessageContext withSkill(SkillLookup.ResolvedSkill resolved) {
        return new MessageContext(knowledge, repo, skillId, resolved);
    }

    /** The distinct sources to search. */
    public List<UUID> sourceIds() {
        return knowledge.stream().map(KnowledgeRef::sourceId).distinct().toList();
    }

    /**
     * Whether a passage from this source and document is one the person chose: its whole source
     * was chosen, or the document itself was.
     */
    public boolean covers(UUID sourceId, UUID documentId) {
        for (KnowledgeRef ref : knowledge) {
            if (ref.sourceId().equals(sourceId) && (ref.documentId() == null || ref.documentId().equals(documentId))) {
                return true;
            }
        }
        return false;
    }

    /** The titles the person saw, in order, for a search when the request itself finds nothing. */
    public List<String> titles() {
        return knowledge.stream()
                .map(KnowledgeRef::title)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    // ---- Stored forms --------------------------------------------------------------------------

    // @find: message context as json, queued message context column
    /** As kept with a queued message: ids and labels, never the skill's text. Null when empty. */
    public Map<String, Object> toStored() {
        if (isEmpty()) {
            return null;
        }
        Map<String, Object> stored = new LinkedHashMap<>();
        if (!knowledge.isEmpty()) {
            stored.put("knowledge", knowledge.stream().map(MessageContext::refMap).toList());
        }
        if (repo != null) {
            stored.put("repo", repoMap(repo));
        }
        if (skillId != null) {
            stored.put("skillId", skillId.toString());
        }
        return stored;
    }

    /** The reverse of {@link #toStored()}; anything unreadable is dropped rather than refused. */
    @SuppressWarnings("unchecked")
    public static MessageContext fromStored(Map<String, Object> stored) {
        if (stored == null || stored.isEmpty()) {
            return NONE;
        }
        List<KnowledgeRef> refs = new ArrayList<>();
        if (stored.get("knowledge") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    UUID source = uuid(m.get("sourceId"));
                    if (source != null) {
                        refs.add(new KnowledgeRef(source, uuid(m.get("documentId")), stringOrNull(m.get("title"))));
                    }
                }
            }
        }
        RepoRef repo = null;
        if (stored.get("repo") instanceof Map<?, ?> m) {
            repo = new RepoRef(
                    stringOrNull(m.get("owner")),
                    stringOrNull(m.get("name")),
                    stringOrNull(m.get("branch")),
                    stringOrNull(m.get("path")));
        }
        try {
            return of(refs, repo, uuid(stored.get("skillId")));
        } catch (ApiException unreadable) {
            return of(refs, null, uuid(stored.get("skillId")));
        }
    }

    // @find: message context from a sent message, reroute keeps context
    /** The context back from what a sent message records ({@link #detail()}), for a reroute. */
    @SuppressWarnings("unchecked")
    public static MessageContext fromDetail(Map<?, ?> detail) {
        if (detail == null || detail.isEmpty()) {
            return NONE;
        }
        Map<String, Object> stored = new LinkedHashMap<>();
        detail.forEach((key, value) -> stored.put(String.valueOf(key), value));
        if (stored.get("skill") instanceof Map<?, ?> skill && skill.get("id") != null) {
            stored.put("skillId", skill.get("id").toString());
        }
        return fromStored(stored);
    }

    // @find: message context shown on the user message, context chips in thread
    /** What the person's message shows of it in the thread: labels, never the skill's text. */
    public Map<String, Object> detail() {
        Map<String, Object> detail = new LinkedHashMap<>();
        if (!knowledge.isEmpty()) {
            detail.put("knowledge", knowledge.stream().map(MessageContext::refMap).toList());
        }
        if (repo != null) {
            Map<String, Object> m = repoMap(repo);
            m.put("label", repo.label());
            detail.put("repo", m);
        }
        if (skill != null) {
            detail.put("skill", Map.of("id", skill.id().toString(), "name", skill.name()));
        }
        return detail;
    }

    /** The stored map form of one knowledge reference; optional parts are omitted when null. */
    private static Map<String, Object> refMap(KnowledgeRef ref) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sourceId", ref.sourceId().toString());
        if (ref.documentId() != null) {
            m.put("documentId", ref.documentId().toString());
        }
        if (ref.title() != null) {
            m.put("title", ref.title());
        }
        return m;
    }

    /** The stored map form of a repository reference; branch and path are omitted when null. */
    private static Map<String, Object> repoMap(RepoRef repo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("owner", repo.owner());
        m.put("name", repo.name());
        if (repo.branch() != null) {
            m.put("branch", repo.branch());
        }
        if (repo.path() != null) {
            m.put("path", repo.path());
        }
        return m;
    }

    /** Parses a UUID from any value, returning null when absent or malformed. */
    private static UUID uuid(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The value as trimmed text, or null when absent or blank. */
    private static String stringOrNull(Object value) {
        return value == null ? null : blankToNull(value.toString());
    }

    /** Trims the text, mapping null and blank to null. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Truncates the text to at most {@code max} characters; null stays null. */
    private static String cap(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
