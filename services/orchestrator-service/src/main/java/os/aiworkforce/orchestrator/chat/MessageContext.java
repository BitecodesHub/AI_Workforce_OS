// @find: message context, composer plus menu, attached knowledge, attached repository, skill on a message, documentIds, sourceIds, repo chip, skillId, ground this message, queued message context, sent from an answer card, card origin, CardRef
// @what: What a chat message carries besides its words and files: workspace documents to ground it, a GitHub repository to work on, a skill to follow, and the answer card it was sent from.
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
 *   <li>{@code card}: the answer card the message was sent from ("Ask the agent to send it", a
 *       ticked checklist, a choice), with what the card held; null for a message the person typed.
 * </ul>
 *
 * <p>Titles and labels come from the person's own browser and are used only as labels: what may
 * be read is decided by the knowledge service and the connector, never by this record. A card
 * only changes how the message is routed (to the card's agent, never as a schedule) and is given
 * to the agent as data; it grants nothing.
 */
public record MessageContext(
        List<KnowledgeRef> knowledge, RepoRef repo, UUID skillId, SkillLookup.ResolvedSkill skill, CardRef card) {

    /** The context of a message that carries nothing; shared so callers can compare cheaply. */
    public static final MessageContext NONE = new MessageContext(List.of(), null, null, null, null);

    /** The card kinds a message can be sent from: the cards that act. A table or chart only reads. */
    static final Set<String> CARD_KINDS = Set.of("email-draft", "checklist", "choices");

    /** Cards one answer may show (web/src/lib/answerBlocks.ts LIMITS.cardsPerAnswer); a card's index is below this. */
    static final int MAX_CARDS = 8;

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

    /** The shape before a message could come from a card: no card. */
    public MessageContext(List<KnowledgeRef> knowledge, RepoRef repo, UUID skillId, SkillLookup.ResolvedSkill skill) {
        this(knowledge, repo, skillId, skill, null);
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

    // @find: CardRef, message sent from an answer card, card content as data
    /**
     * The answer card a message was sent from, and what the card held when the person used it.
     *
     * <p>The content is the person's own edited copy of what an agent wrote - a draft to send, the
     * items they ticked, the option they chose - so it reaches the agent as data, apart from the
     * words of the request ({@link ContextPrompt}). Checked by {@link #checkCard}: only the fields
     * of its kind are kept, every one within a size cap.
     *
     * @param messageId the agent's answer the card is in
     * @param index the card's place among that answer's cards, from 0
     * @param kind {@code email-draft}, {@code checklist} or {@code choices}
     * @param content for an email draft: to, cc, subject and body; for a checklist: title and items
     *     (each text and done); for a choice: question and option
     */
    public record CardRef(UUID messageId, Integer index, String kind, Map<String, Object> content) {}

    // @find: build message context from request, validate repo and knowledge refs
    /**
     * Checks what the request sent and returns it, or {@link #NONE} when nothing was attached.
     * Blank strings count as absent; anything malformed is refused with the field it was in.
     */
    public static MessageContext of(List<KnowledgeRef> knowledge, RepoRef repo, UUID skillId) {
        return of(knowledge, repo, skillId, null);
    }

    /**
     * As {@link #of(List, RepoRef, UUID)}, with the answer card the message was sent from, checked
     * by {@link #checkCard}; null when the person typed it.
     *
     * @throws ApiException a validation error naming the field that was wrong
     */
    public static MessageContext of(List<KnowledgeRef> knowledge, RepoRef repo, UUID skillId, CardRef card) {
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
        CardRef checkedCard = card == null ? null : checkCard(card);
        if (refs.isEmpty() && checked == null && skillId == null && checkedCard == null) {
            return NONE;
        }
        return new MessageContext(refs, checked, skillId, null, checkedCard);
    }

    /** The longest recipient list, subject, body and other card fields kept; the web card caps its fields lower. */
    static final int MAX_CARD_RECIPIENTS = 6_000;

    static final int MAX_CARD_SUBJECT = 1_000;
    static final int MAX_CARD_BODY = 10_000;
    static final int MAX_CARD_TEXT = 300;
    static final int MAX_CARD_ITEMS = 30;

    // @find: check card content, validate email draft from card, card size caps
    /**
     * Checks a card reference and keeps only the fields of its kind, each within its cap: the answer
     * it is in, its place (0 to {@link #MAX_CARDS} - 1), its kind ({@link #CARD_KINDS}) and its
     * content. Line breaks in a body are kept; nothing else is changed. Pure.
     *
     * @throws ApiException a validation error on {@code card} or {@code card.<field>}, in plain words
     */
    static CardRef checkCard(CardRef card) {
        if (card.messageId() == null) {
            throw ApiException.validation("card.messageId", "The card must name the answer it is in.");
        }
        if (card.index() == null || card.index() < 0 || card.index() >= MAX_CARDS) {
            throw ApiException.validation("card.index", "The card's place in the answer is not valid.");
        }
        String kind = card.kind() == null ? "" : card.kind().strip().toLowerCase(java.util.Locale.ROOT);
        if (!CARD_KINDS.contains(kind)) {
            throw ApiException.validation("card.kind", "Only an email draft, a checklist or a choice can send a message.");
        }
        Map<String, Object> raw = card.content() == null ? Map.of() : card.content();
        Map<String, Object> content = new LinkedHashMap<>();
        switch (kind) {
            case "email-draft" -> {
                content.put("to", cardText(raw.get("to"), "to", MAX_CARD_RECIPIENTS, true));
                content.put("cc", cardText(raw.get("cc"), "cc", MAX_CARD_RECIPIENTS, false));
                content.put("subject", cardText(raw.get("subject"), "subject", MAX_CARD_SUBJECT, true));
                content.put("body", cardText(raw.get("body"), "body", MAX_CARD_BODY, true));
            }
            case "checklist" -> {
                content.put("title", cardText(raw.get("title"), "title", MAX_CARD_TEXT, false));
                if (!(raw.get("items") instanceof List<?> items) || items.isEmpty() || items.size() > MAX_CARD_ITEMS) {
                    throw ApiException.validation(
                            "card.items", "A checklist sends one to " + MAX_CARD_ITEMS + " items.");
                }
                List<Map<String, Object>> kept = new ArrayList<>();
                for (Object item : items) {
                    if (!(item instanceof Map<?, ?> entry)) {
                        throw ApiException.validation("card.items", "Each checklist item must have its text.");
                    }
                    Map<String, Object> one = new LinkedHashMap<>();
                    one.put("text", cardText(entry.get("text"), "items", MAX_CARD_TEXT, true));
                    one.put("done", Boolean.TRUE.equals(entry.get("done")));
                    kept.add(one);
                }
                content.put("items", List.copyOf(kept));
            }
            default -> {
                content.put("question", cardText(raw.get("question"), "question", MAX_CARD_TEXT, true));
                content.put("option", cardText(raw.get("option"), "option", MAX_CARD_TEXT, true));
            }
        }
        return new CardRef(card.messageId(), card.index(), kind, java.util.Collections.unmodifiableMap(content));
    }

    /** One text field of a card: a string within {@code max} characters, required or else empty. */
    private static String cardText(Object value, String field, int max, boolean required) {
        if (value != null && !(value instanceof String)) {
            throw ApiException.validation("card." + field, "The card's " + field + " must be text.");
        }
        String text = value == null ? "" : ((String) value).replace("\r\n", "\n").replace('\r', '\n');
        if (text.length() > max) {
            throw ApiException.validation(
                    "card." + field, "The card's " + field + " is longer than " + String.format("%,d", max) + " characters.");
        }
        if (required && text.isBlank()) {
            throw ApiException.validation("card." + field, "The card's " + field + " is empty.");
        }
        return text;
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

    /** Whether nothing at all is attached: no knowledge, no repository, no skill and no card. */
    public boolean isEmpty() {
        return knowledge.isEmpty() && repo == null && skillId == null && card == null;
    }

    /**
     * Whether the message is always work for an agent, like a message with files: the documents,
     * repository or playbook attached are what it is about, and only an agent's run uses them. A
     * card alone does not count here; {@link #fromCard()} has rules of its own.
     */
    public boolean forcesWork() {
        return !knowledge.isEmpty() || repo != null || skillId != null;
    }

    /**
     * Whether the message was sent from an answer card. Such a message goes to the card's agent as
     * work, exactly as sent: it is never read as a schedule, and an "@" in its text (an email
     * address in a draft) is never a mention.
     */
    public boolean fromCard() {
        return card != null;
    }

    /** The same context with its skill looked up. */
    public MessageContext withSkill(SkillLookup.ResolvedSkill resolved) {
        return new MessageContext(knowledge, repo, skillId, resolved, card);
    }

    /** The same context without its skill (one that no longer exists), keeping everything else. */
    public MessageContext withoutSkill() {
        return new MessageContext(knowledge, repo, null, null, card);
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
        if (card != null) {
            stored.put("card", cardMap(card));
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
        CardRef card = cardFrom(stored.get("card"));
        try {
            return of(refs, repo, uuid(stored.get("skillId")), card);
        } catch (ApiException unreadable) {
            try {
                return of(refs, null, uuid(stored.get("skillId")), card);
            } catch (ApiException cardUnreadable) {
                return of(refs, null, uuid(stored.get("skillId")), null);
            }
        }
    }

    /** A card reference read back from its stored map, or null when there is none or it is not a map. */
    @SuppressWarnings("unchecked")
    private static CardRef cardFrom(Object stored) {
        if (!(stored instanceof Map<?, ?> m)) {
            return null;
        }
        Integer index = m.get("index") instanceof Number n ? n.intValue() : null;
        Map<String, Object> content = m.get("content") instanceof Map<?, ?> c ? (Map<String, Object>) c : null;
        return new CardRef(uuid(m.get("messageId")), index, stringOrNull(m.get("kind")), content);
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
        if (card != null) {
            // With its content, so the card can show what was sent and a reroute sends the same.
            detail.put("card", cardMap(card));
        }
        return detail;
    }

    /** The stored and shown map form of a card reference: the answer, the place, the kind and the content. */
    private static Map<String, Object> cardMap(CardRef card) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("messageId", card.messageId().toString());
        m.put("index", card.index());
        m.put("kind", card.kind());
        m.put("content", card.content());
        return m;
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
