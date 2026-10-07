package os.aiworkforce.knowledge.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import os.aiworkforce.knowledge.domain.Source;
import os.aiworkforce.knowledge.repository.Chunks;
import os.aiworkforce.knowledge.repository.Sources;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Finds the passages that support an answer.
 *
 * <p>Retrieval here is hybrid, and the reason is worth stating because dense-only search is the
 * common default. An embedding search understands meaning but is poor at exact tokens: ask about
 * invoice {@code INV-4417} or a colleague's surname and the nearest vectors are documents about
 * invoices in general. A lexical search has the opposite problem. Running both and fusing the
 * rankings gets the specific match and the conceptual one.
 *
 * <p>The fusion is reciprocal rank rather than a weighted score sum, because the two searches
 * produce scores on scales that cannot be compared - a cosine similarity of 0.82 and a BM25 rank
 * of 3.1 mean nothing to each other. Ranks are comparable; raw scores are not.
 *
 * <p>Every passage returned carries its document, page and offset. An answer the platform cannot
 * attribute is one nobody should act on, so a retrieval that finds nothing returns nothing rather
 * than the closest thing it could find.
 *
 * <p>Three rules keep it answering when something around it is slow or down:
 *
 * <ul>
 *   <li><b>No transaction spans the search.</b> The source lookup, the keyword search and the
 *       hydrate each run as their own short query, so no database connection is held while the
 *       embedding provider or the vector store is asked anything.
 *   <li><b>The meaning-based half has a budget.</b> It runs beside the keyword search, on its own
 *       thread, and is abandoned after {@link #DENSE_BUDGET}. A vector store that hangs rather than
 *       refusing - the common failure - costs accuracy, and the response says so with
 *       {@code degraded}, instead of costing the caller's whole time limit and every passage.
 *   <li><b>Sandbox sources are never searched by meaning.</b> Their vectors are pseudo-random, so
 *       the round trip could only add noise and latency.
 * </ul>
 *
 * <p>Access is decided before either search runs: the allowed sources are worked out first and
 * pushed into both searches and into the hydrate, never applied to results afterwards.
 *
 * <p>"Grounded" is a claim that a document supports an answer, so it is held to a bar a single
 * incidental word does not clear: a passage found by keyword alone must contain more than one of
 * the question's distinctive terms (see {@link #groundedPassages}). A question about supplier
 * onboarding that happened to share the word "risks" with an unrelated slide deck used to be
 * answered with that deck.
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    /**
     * The constant in reciprocal rank fusion.
     *
     * <p>60 is the value from the original paper and behaves well here: it keeps a result ranked
     * first in one search from completely dominating one ranked second in both.
     */
    private static final int RRF_K = 60;

    /** Below this similarity a dense hit is noise, and including it invites a fabricated answer. */
    private static final double MIN_SCORE = 0.25;

    /**
     * The equivalent floor for keyword search.
     *
     * <p>Measured against this corpus: passages that genuinely answer a question rank between
     * 0.037 and 0.056, an incidental single-term match ranks 0.015. Without a floor, OR-combined
     * terms make every query match something, and "no document supports an answer" - the honest
     * response, and the one that stops an agent inventing one - becomes impossible to reach.
     */
    private static final double MIN_LEXICAL_RANK = 0.03;

    /** The floor a long query's relevance bar may ease to, never lower (see Chunks.searchLexical). */
    private static final double LOWEST_LEXICAL_RANK = 0.016;

    /** The most passages one search returns. */
    public static final int MAX_LIMIT = 20;

    /**
     * How long the meaning-based half may take before the keyword results are returned without it.
     *
     * <p>The orchestrator gives the whole search ten seconds. Three leaves room for the keyword
     * search and the hydrate inside that even when the vector store never answers at all, and is
     * still several times what a healthy embedding call and vector search take together.
     */
    static final Duration DENSE_BUDGET = Duration.ofSeconds(3);

    private final QdrantClient vectors;
    private final EmbeddingService embeddings;
    private final Chunks chunks;
    private final Sources sources;

    /**
     * Runs the meaning-based half. Virtual threads, because the work is two blocking HTTP calls,
     * and a thread left waiting on a store that hangs past the budget costs almost nothing.
     */
    private final ExecutorService denseExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public RetrievalService(QdrantClient vectors, EmbeddingService embeddings, Chunks chunks, Sources sources) {
        this.vectors = vectors;
        this.embeddings = embeddings;
        this.chunks = chunks;
        this.sources = sources;
    }

    @PreDestroy
    void shutdown() {
        denseExecutor.shutdownNow();
    }

    /**
     * One passage, with everything needed to cite it.
     *
     * @param chunkId the passage
     * @param documentId the document it came from
     * @param sourceId the source that document is in, so a citation can link to it
     * @param documentTitle what to show in the citation
     * @param uri where to open the original, when there is somewhere to open
     * @param pageNumber the page, for a paginated document
     * @param heading the section it sat under, which gives the passage its context
     * @param content the text itself, so a person can check the claim against it
     * @param score fused rank score, for ordering and as a confidence signal
     * @param restricted whether the source is restricted to people who manage knowledge, so a caller
     *     that records the passage where others can read it keeps its text out of that record
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
            boolean restricted) {

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
     * @param passages what was found, best first
     * @param degraded true when the meaning-based half was wanted but timed out or failed, so these
     *     are keyword matches only and the caller can say so
     */
    public record Retrieval(List<Passage> passages, boolean degraded) {}

    /**
     * Searches the sources the caller may read.
     *
     * @param requested the sources the caller asked to search, or null for every one they may read;
     *     a source they may not read is dropped from it silently, the same as one that does not exist
     * @param includeRestricted whether the caller may read restricted sources
     */
    public Retrieval retrieve(
            UUID orgId, String query, int limit, List<UUID> requested, boolean includeRestricted) {
        return retrieve(orgId, query, limit, requested, includeRestricted, null);
    }

    /**
     * As {@link #retrieve(UUID, String, int, List, boolean)}, for an agent: its own documents are
     * searched too. Another agent's are never read, and a search with no agent never reads any.
     */
    public Retrieval retrieve(
            UUID orgId,
            String query,
            int limit,
            List<UUID> requested,
            boolean includeRestricted,
            UUID agentId) {
        if (query == null || query.isBlank()) {
            throw ApiException.validation("query", "must not be empty");
        }
        int capped = Math.min(Math.max(limit, 1), MAX_LIMIT);

        List<Source> searchable = allowedSources(orgId, requested, includeRestricted, agentId);
        if (searchable.isEmpty()) {
            return new Retrieval(List.of(), false);
        }
        Set<UUID> allowed = new LinkedHashSet<>();
        searchable.forEach(source -> allowed.add(source.getId()));
        String allowedArray = Chunks.uuidArray(allowed);

        // Started first, so it runs while the keyword search does.
        List<DenseGroup> groups = denseGroups(searchable);
        CompletableFuture<List<UUID>> dense = groups.isEmpty()
                ? CompletableFuture.completedFuture(List.of())
                : startDense(orgId, query, groups, capped * 3);

        List<UUID> lexical = lexicalSearch(orgId, query, allowedArray, capped * 3);

        boolean degraded = false;
        List<UUID> denseHits;
        try {
            denseHits = dense.join();
        } catch (CompletionException | CancellationException e) {
            /*
             * Caught broadly, and deliberately so. Anything that goes wrong in the dense half -
             * a timeout, a refused connection, a provider error - should cost accuracy, not the
             * answer. The keyword results stand, and the response says they are all there is.
             */
            degraded = true;
            denseHits = List.of();
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof TimeoutException) {
                log.warn("Meaning-based search took longer than {} ms; answering from keyword search", DENSE_BUDGET.toMillis());
            } else {
                log.warn("Meaning-based search unavailable, answering from keyword search: {}", cause.toString());
            }
        }

        if (denseHits.isEmpty() && lexical.isEmpty()) {
            // Returning nothing is the honest answer. Returning the least-bad match is how an
            // agent ends up citing an unrelated document with total confidence.
            log.debug("No passage matched the query in workspace {}", orgId);
            return new Retrieval(List.of(), degraded);
        }

        List<UUID> fused = fuse(denseHits, lexical, capped);
        Map<UUID, Boolean> restricted = new HashMap<>();
        searchable.forEach(source -> restricted.put(source.getId(), source.isRestricted()));
        List<Passage> found = hydrate(orgId, fused, allowedArray, restricted);
        return new Retrieval(groundedPassages(query, found, new LinkedHashSet<>(denseHits)), degraded);
    }

    /**
     * How many of the workspace's sources hold at least one passage, restricted ones included.
     *
     * <p>A fact about the workspace rather than about any document, which is why it needs no
     * caller's permission: the orchestrator uses it to decide whether to offer agents a document
     * search at all, and what a search may then read is still decided per person.
     */
    public int indexedSources(UUID orgId) {
        return indexedSources(orgId, null);
    }

    /** As {@link #indexedSources(UUID)}, counting this agent's own documents as well. */
    public int indexedSources(UUID orgId, UUID agentId) {
        return (int) sources.findByOrgIdOrderByName(orgId).stream()
                .filter(source -> source.getAgentId() == null || source.getAgentId().equals(agentId))
                .filter(source -> source.getChunkCount() > 0)
                .count();
    }

    /**
     * The sources this search may read: those the caller may see, narrowed to the ones they asked
     * for. One short query, with nothing held open afterwards.
     */
    private List<Source> allowedSources(
            UUID orgId, List<UUID> requested, boolean includeRestricted, UUID agentId) {
        List<Source> visible = agentId == null
                ? sources.findVisible(orgId, includeRestricted)
                : sources.findVisibleTo(orgId, includeRestricted, agentId);
        if (requested == null || requested.isEmpty()) {
            return visible;
        }
        Set<UUID> asked = Set.copyOf(requested);
        return visible.stream().filter(source -> asked.contains(source.getId())).toList();
    }

    /**
     * Sources that share a collection, provider and model, which one query embedding can search
     * together.
     *
     * @param sourceIds the sources in the collection this search may read
     */
    record DenseGroup(String collection, String provider, String model, List<UUID> sourceIds) {}

    /**
     * Groups the sources worth searching by meaning: not sandbox, whose vectors carry no meaning,
     * and holding at least one passage, so a source that was never filled cannot fail the search.
     */
    static List<DenseGroup> denseGroups(List<Source> searchable) {
        Map<List<String>, List<UUID>> grouped = new LinkedHashMap<>();
        for (Source source : searchable) {
            if (!source.isSearchableByMeaning() || source.getChunkCount() == 0 || source.getCollection() == null) {
                continue;
            }
            grouped.computeIfAbsent(
                            List.of(source.getCollection(), source.getEmbeddingProvider(), source.getEmbeddingModel()),
                            key -> new ArrayList<>())
                    .add(source.getId());
        }
        List<DenseGroup> groups = new ArrayList<>();
        grouped.forEach((key, ids) -> groups.add(new DenseGroup(key.get(0), key.get(1), key.get(2), List.copyOf(ids))));
        return groups;
    }

    private CompletableFuture<List<UUID>> startDense(UUID orgId, String query, List<DenseGroup> groups, int limit) {
        // The caller's identity goes with the work: the embedding call is made on their behalf.
        RequestContext.Snapshot context = RequestContext.snapshot();
        return CompletableFuture.supplyAsync(
                        () -> RequestContext.with(context, () -> denseSearch(orgId, query, groups, limit)), denseExecutor)
                .orTimeout(DENSE_BUDGET.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Embeds the query once per group, with that group's own model, and merges the hits by score.
     * Throws on any failure; the caller decides what a failure costs.
     */
    private List<UUID> denseSearch(UUID orgId, String query, List<DenseGroup> groups, int limit) {
        List<QdrantClient.Hit> hits = new ArrayList<>();
        for (DenseGroup group : groups) {
            float[] vector = embeddings.embedQuery(orgId, group.provider(), group.model(), query);
            hits.addAll(vectors.search(group.collection(), orgId, group.sourceIds(), vector, limit, MIN_SCORE));
        }
        Map<UUID, Double> best = new HashMap<>();
        hits.forEach(hit -> best.merge(hit.chunkId(), hit.score(), Math::max));
        return best.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    private List<UUID> lexicalSearch(UUID orgId, String query, String allowedArray, int limit) {
        return chunks.searchLexical(
                orgId,
                withoutConversationalWords(query),
                allowedArray,
                MIN_LEXICAL_RANK,
                LOWEST_LEXICAL_RANK,
                PageRequest.of(0, limit));
    }

    /**
     * Words a person uses to ask, not to say what they want found. Postgres's English stop list
     * already drops "me", "about" and "our", but not "tell", "explain" or "please", and each such
     * word joins the OR query as one more term the passage lacks, which lowers its rank. "Tell me
     * about our SIH project" ranked 0.025 against the 0.03 floor, while "SIH project" ranked
     * 0.038, so the same document went unfound only because of how the question was phrased.
     */
    private static final Pattern CONVERSATIONAL = Pattern.compile(
            "\\b(tell|explain|describe|summari[sz]e|summary|show|give|find|know|knows|please|want|need|"
                    + "information|info|details|anything|something|everything|can|could|would|help|"
                    + "question|knowledge|base|document|documents|docs|file|files|there|its)\\b",
            Pattern.CASE_INSENSITIVE);

    static String withoutConversationalWords(String query) {
        if (query == null) {
            return "";
        }
        String stripped = CONVERSATIONAL
                .matcher(query)
                .replaceAll(" ")
                .replaceAll("\\s+", " ")
                .strip();
        // A query made only of such words would search for nothing; keep it as it was then.
        return stripped.isEmpty() ? query : stripped;
    }

    /**
     * Reciprocal rank fusion.
     *
     * <p>Each list contributes {@code 1 / (k + rank)} to a passage's score, so a document found by
     * both searches outranks one found emphatically by only one. This is what makes the hybrid
     * better than either half rather than merely different.
     */
    private List<UUID> fuse(List<UUID> dense, List<UUID> lexical, int limit) {
        Map<UUID, Double> scores = new LinkedHashMap<>();
        for (int rank = 0; rank < dense.size(); rank++) {
            scores.merge(dense.get(rank), 1.0 / (RRF_K + rank + 1), Double::sum);
        }
        for (int rank = 0; rank < lexical.size(); rank++) {
            scores.merge(lexical.get(rank), 1.0 / (RRF_K + rank + 1), Double::sum);
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * Loads the passages and attaches their provenance.
     *
     * <p>Tombstoned documents are excluded here as well as at the vector store. A document deleted
     * at the source stops being citable immediately, before the vector purge has caught up -
     * otherwise a person would be shown a passage from a file that no longer exists. The allowed
     * sources are applied here too, because a vector hit is only an id until it is resolved.
     */
    private List<Passage> hydrate(
            UUID orgId, List<UUID> chunkIds, String allowedArray, Map<UUID, Boolean> restrictedBySource) {
        if (chunkIds.isEmpty()) {
            return List.of();
        }
        var rows = chunks.findCitable(orgId, chunkIds, allowedArray);
        Map<UUID, Integer> order = new LinkedHashMap<>();
        for (int i = 0; i < chunkIds.size(); i++) {
            order.put(chunkIds.get(i), i);
        }

        List<Passage> passages = new ArrayList<>();
        for (var row : rows) {
            passages.add(new Passage(
                    row.getChunkId(),
                    row.getDocumentId(),
                    row.getSourceId(),
                    row.getDocumentTitle(),
                    row.getUri(),
                    row.getPageNumber(),
                    row.getHeading(),
                    row.getContent(),
                    1.0 - (order.getOrDefault(row.getChunkId(), 0) / (double) Math.max(1, chunkIds.size())),
                    Boolean.TRUE.equals(restrictedBySource.get(row.getSourceId()))));
        }
        passages.sort(Comparator.comparingDouble(Passage::score).reversed());
        return passages;
    }

    // ---- The grounding bar ---------------------------------------------------------------------

    /**
     * Words that carry no subject on their own, so sharing one says nothing about whether a passage
     * is about what was asked. Short on purpose: the keyword search has already dropped Postgres's
     * own stop words, and this list only adds the ones a question is built from.
     */
    private static final Set<String> NO_SUBJECT = Set.of(
            "the", "and", "for", "are", "was", "were", "with", "that", "this", "from", "what", "which", "who", "how",
            "why", "when", "where", "does", "did", "have", "has", "had", "not", "but", "you", "your", "our", "can",
            "about", "into", "than", "then", "them", "they", "their", "there", "will", "would", "should", "could", "may",
            "might", "also", "any", "all", "been", "being", "its", "per", "use", "using", "used", "make", "made", "need",
            "get", "got", "give", "tell", "show", "list", "find", "know", "want", "like", "some", "more", "most", "very",
            "just", "only", "each", "other", "such", "please");

    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");

    /**
     * The passages that clear the grounding bar.
     *
     * <p>A passage the meaning-based search found is kept: its similarity already passed a floor.
     * One found by keyword alone is kept only when it contains at least {@link #requiredTerms} of
     * the question's distinctive terms - words that carry a subject, compared by stem, in its text,
     * its heading or its document's title. The keyword search ranks by how much of the passage the
     * terms fill, so a long question matched on one common word can still rank above the floor, and
     * an answer built on that passage is exactly the confident wrong one this service exists to
     * prevent. When nothing is left, the search is simply not grounded.
     *
     * @param viaMeaning the passages the meaning-based search found
     */
    static List<Passage> groundedPassages(String query, List<Passage> passages, Set<UUID> viaMeaning) {
        if (isAboutTheAssistant(query)) {
            return List.of();
        }
        Set<String> terms = distinctiveTerms(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        int required = requiredTerms(terms.size());
        if (required <= 1) {
            return passages.stream()
                    .filter(p -> viaMeaning.contains(p.chunkId()) || termsCovered(terms, p) >= 1)
                    .toList();
        }
        List<Passage> kept = new ArrayList<>();
        for (Passage passage : passages) {
            if (viaMeaning.contains(passage.chunkId()) || termsCovered(terms, passage) >= required) {
                kept.add(passage);
            }
        }
        if (kept.size() < passages.size()) {
            log.debug(
                    "Dropped {} passage(s) that covered fewer than {} of {} distinctive terms",
                    passages.size() - kept.size(),
                    required,
                    terms.size());
        }
        return kept;
    }

    /** Questions about the assistant itself, a greeting or thanks: no document is what they ask for. */
    private static final Pattern ABOUT_ASSISTANT = Pattern.compile(
            "\\b(who are you|what are you|what is your (name|role|job|purpose)|what'?s your (name|role|job)|your name"
                    + "|what can you do|what do you do|how are you|introduce yourself|about yourself)\\b"
                    + "|^\\s*(hi|hello|hey|thanks|thank you|good (morning|afternoon|evening))\\b[\\s\\p{Punct}\\p{L}]{0,30}$",
            Pattern.CASE_INSENSITIVE);

    static boolean isAboutTheAssistant(String query) {
        return query != null && ABOUT_ASSISTANT.matcher(query).find();
    }

    /** One distinctive term is enough for a question with one or two; a longer one needs two to agree. */
    static int requiredTerms(int distinctive) {
        return distinctive <= 2 ? 1 : 2;
    }

    /** The question's subject words, stemmed and without repeats. */
    static Set<String> distinctiveTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        if (query == null) {
            return terms;
        }
        String withoutAsking = withoutConversationalWords(query);
        var matcher = TOKEN.matcher(withoutAsking.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() >= 3 && !NO_SUBJECT.contains(token)) {
                terms.add(stem(token));
            }
        }
        return terms;
    }

    private static int termsCovered(Set<String> terms, Passage passage) {
        Set<String> present = new LinkedHashSet<>();
        for (String text : new String[] {passage.documentTitle(), passage.heading(), passage.content()}) {
            if (text == null) {
                continue;
            }
            var matcher = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
            while (matcher.find()) {
                present.add(stem(matcher.group()));
            }
        }
        int covered = 0;
        for (String term : terms) {
            if (present.contains(term)) {
                covered++;
            }
        }
        return covered;
    }

    /**
     * A light suffix strip, enough that "refunds", "refunded" and "refunding" are one word. It need
     * not be linguistically right, only the same on both sides of the comparison.
     */
    static String stem(String word) {
        String w = word;
        if (w.length() > 4 && w.endsWith("ies")) {
            return w.substring(0, w.length() - 3) + "y";
        }
        if (w.length() > 5 && w.endsWith("ing")) {
            w = w.substring(0, w.length() - 3);
        } else if (w.length() > 4 && w.endsWith("ed")) {
            w = w.substring(0, w.length() - 2);
        } else if (w.length() > 4 && w.endsWith("es")) {
            w = w.substring(0, w.length() - 2);
        } else if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) {
            w = w.substring(0, w.length() - 1);
        }
        // "managed" and "manage" meet at "manag".
        if (w.length() > 4 && w.endsWith("e")) {
            w = w.substring(0, w.length() - 1);
        }
        return w;
    }

    /**
     * Raised when an agent asked a question the corpus cannot support.
     *
     * <p>Surfaced to the caller as a distinct code so the interface can say "no document supports
     * an answer" rather than showing an empty result that looks like a failure.
     */
    static ApiException noEvidence() {
        return new ApiException(ErrorCode.NO_EVIDENCE_FOUND);
    }
}
