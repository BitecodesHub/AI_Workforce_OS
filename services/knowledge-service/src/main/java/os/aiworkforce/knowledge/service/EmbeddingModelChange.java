// @find: knowledge base, knowledge, documents, sources, change embedding model, switch embedding model, reindex all documents, re-embed, re-index, embedding migration, progress, resume reindex, Embedding settings page, PUT /api/knowledge/embedding, POST /api/knowledge/embedding/reindex, EmbeddingModelChange
// @what: Runs the workspace-wide change of embedding model: validates it, rebuilds every source's vectors in a new collection and reports progress.
// @flow: Called by EmbeddingSettingsController; uses IngestionService.switchEmbedding/reindex and EmbeddingService.probeDimension.
package os.aiworkforce.knowledge.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import os.aiworkforce.knowledge.domain.Source;
import os.aiworkforce.knowledge.repository.Sources;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Changes the embedding model a workspace's knowledge base searches by meaning with.
 *
 * <p>The model is tried before anything changes: one short passage is embedded with it, which
 * proves the provider takes the workspace's key and measures how wide its vectors are. A model
 * that cannot be used is refused with the provider's reason, and the sources stay as they were.
 *
 * <p>Then every source is moved to the model, each into the model's own collection, and its stored
 * passages are embedded again in the background, one source and one document at a time. Nothing
 * is uploaded again: the text is in Postgres. Keyword search keeps working throughout; search by
 * meaning returns what has been re-embedded so far. Progress is kept in memory while the work runs
 * and written to the settings table at every step, so the console can show it, and a restart that
 * cuts the work short is reported as such rather than as still running.
 */
@Service
public class EmbeddingModelChange {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingModelChange.class);

    /**
     * How far a change has got.
     *
     * @param state {@code idle} (never changed), {@code running}, {@code done}, {@code failed} or
     *     {@code interrupted}
     * @param message a plain sentence for the console
     */
    public record Progress(
            String state,
            int sourcesTotal,
            int sourcesDone,
            int passagesTotal,
            int passagesDone,
            String message,
            Instant startedAt,
            Instant finishedAt) {

        static final Progress IDLE = new Progress("idle", 0, 0, 0, 0, null, null, null);

        boolean running() {
            return "running".equals(state);
        }
    }

    /**
     * The knowledge base's embedding setting, as the console shows it.
     *
     * @param searchMode {@code keyword} or {@code keyword+meaning}
     */
    public record Status(String provider, String model, int dimension, String searchMode, Progress progress) {}

    private final EmbeddingSettings settings;
    private final EmbeddingService embeddings;
    private final IngestionService ingestion;
    private final Sources sources;
    private final QdrantClient vectors;

    private final Map<UUID, Progress> running = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();

    public EmbeddingModelChange(
            EmbeddingSettings settings,
            EmbeddingService embeddings,
            IngestionService ingestion,
            Sources sources,
            QdrantClient vectors) {
        this.settings = settings;
        this.embeddings = embeddings;
        this.ingestion = ingestion;
        this.sources = sources;
        this.vectors = vectors;
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

    // @find: embedding model status, reindex progress
    public Status status(UUID orgId) {
        EmbeddingSettings.Choice choice = settings.current(orgId);
        return new Status(
                choice.provider(),
                choice.model(),
                choice.dimension(),
                choice.searchesByMeaning() ? "keyword+meaning" : "keyword",
                progress(orgId));
    }

    /** The live progress, or the last one written; one written as running by a process that is gone was cut short. */
    Progress progress(UUID orgId) {
        Progress live = running.get(orgId);
        if (live != null) {
            return live;
        }
        Progress saved = settings.read(orgId, EmbeddingSettings.PROGRESS_KEY, Progress.class).orElse(Progress.IDLE);
        if (saved.running()) {
            return new Progress(
                    "interrupted",
                    saved.sourcesTotal(),
                    saved.sourcesDone(),
                    saved.passagesTotal(),
                    saved.passagesDone(),
                    "Re-indexing stopped part way when the service restarted. Start it again to finish;"
                            + " keyword search works meanwhile.",
                    saved.startedAt(),
                    null);
        }
        return saved;
    }

    // @find: change embedding model, switch model, start reindex of all knowledge
    /**
     * Switches the workspace to a model, or to keyword search only when {@code providerId} is
     * {@code sandbox} or blank, and starts re-indexing every source in the background.
     */
    public Status change(UUID orgId, String providerId, String modelId, String by) {
        if (running.containsKey(orgId)) {
            throw ApiException.conflict("The knowledge base is already being re-indexed. Wait for it to finish.");
        }
        EmbeddingSettings.Choice choice;
        if (providerId == null || providerId.isBlank() || Source.SANDBOX_PROVIDER.equalsIgnoreCase(providerId)) {
            choice = EmbeddingSettings.Choice.KEYWORD_ONLY;
        } else {
            if (modelId == null || modelId.isBlank()) {
                throw ApiException.validation("modelId", "must name the embedding model");
            }
            int dimension;
            try {
                dimension = embeddings.probeDimension(orgId, providerId.strip(), modelId.strip());
            } catch (EmbeddingService.EmbeddingRefused refused) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, refused.reason());
            } catch (RuntimeException e) {
                throw new ApiException(
                        ErrorCode.UPSTREAM_UNAVAILABLE,
                        "Could not reach the model service to try " + modelId + ". Nothing was changed; try again.");
            }
            choice = new EmbeddingSettings.Choice(providerId.strip(), modelId.strip(), dimension);
        }
        settings.save(orgId, choice, by);
        log.info("Workspace {} now embeds with {}/{} ({} dimensions), set by {}",
                orgId, choice.provider(), choice.model(), choice.dimension(), by);
        start(orgId, choice);
        return status(orgId);
    }

    // @find: resume interrupted reindex, retry embedding change
    /** Runs the re-indexing for the current model again, after a failure or an interruption. */
    public Status resume(UUID orgId) {
        if (running.containsKey(orgId)) {
            throw ApiException.conflict("The knowledge base is already being re-indexed. Wait for it to finish.");
        }
        start(orgId, settings.current(orgId));
        return status(orgId);
    }

    private void start(UUID orgId, EmbeddingSettings.Choice choice) {
        List<Source> all = sources.findByOrgIdOrderByName(orgId);
        int passages = all.stream().mapToInt(Source::getChunkCount).sum();
        Progress first = new Progress(
                "running",
                all.size(),
                0,
                choice.searchesByMeaning() ? passages : 0,
                0,
                choice.searchesByMeaning()
                        ? "Re-indexing " + passages + " passage(s) for search by meaning."
                        : "Switching to keyword search only.",
                Instant.now(),
                null);
        publish(orgId, first);
        RequestContext.Snapshot context = RequestContext.snapshot();
        worker.submit(() -> RequestContext.with(context, () -> {
            run(orgId, choice, all, first);
            return null;
        }));
    }

    private void run(UUID orgId, EmbeddingSettings.Choice choice, List<Source> all, Progress first) {
        AtomicInteger passagesDone = new AtomicInteger();
        AtomicInteger sourcesFinished = new AtomicInteger();
        int sourcesDone = 0;
        String failure = null;
        try {
            for (Source listed : all) {
                IngestionService.PreviousEmbedding previous;
                try {
                    previous = ingestion.switchEmbedding(orgId, listed.getId(), choice);
                } catch (ApiException gone) {
                    // Deleted since the list was read.
                    sourcesDone++;
                    continue;
                }
                if (choice.searchesByMeaning() && listed.getChunkCount() > 0) {
                    IngestionService.ReindexResult result = ingestion.reindex(orgId, listed.getId(), done -> {
                        passagesDone.addAndGet(done);
                        publish(orgId, step(first, sourcesFinished.get(), passagesDone.get(), null));
                    });
                    if (!result.vectorised() && result.documentCount() > 0) {
                        failure = result.detail() == null ? "The embedding model could not be used." : result.detail();
                    }
                }
                String newCollection = IngestionService.collectionOf(orgId, choice);
                if (previous.searchedByMeaning() && !previous.collection().equals(newCollection)) {
                    // The old model's vectors would only take search slots; searches never read them now.
                    try {
                        vectors.deleteBySource(previous.collection(), listed.getId());
                    } catch (RuntimeException e) {
                        log.warn("Old vectors of source {} could not be removed: {}", listed.getId(), e.getMessage());
                    }
                }
                sourcesDone++;
                sourcesFinished.set(sourcesDone);
                publish(orgId, step(first, sourcesDone, passagesDone.get(), null));
                if (failure != null) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.warn("Re-indexing for workspace {} failed", orgId, e);
            failure = "Re-indexing stopped: " + e.getMessage();
        }

        Progress last;
        if (failure != null) {
            last = new Progress(
                    "failed",
                    first.sourcesTotal(),
                    sourcesDone,
                    first.passagesTotal(),
                    passagesDone.get(),
                    failure.strip() + " Keyword search still works. Fix the cause and choose Re-index again.",
                    first.startedAt(),
                    Instant.now());
        } else {
            last = new Progress(
                    "done",
                    first.sourcesTotal(),
                    sourcesDone,
                    first.passagesTotal(),
                    passagesDone.get(),
                    choice.searchesByMeaning()
                            ? "All " + passagesDone.get() + " passage(s) can be searched by meaning with "
                                    + choice.model() + "."
                            : "Documents are searched by keyword only.",
                    first.startedAt(),
                    Instant.now());
        }
        running.remove(orgId);
        save(orgId, last);
        log.info("Re-indexing for workspace {} {}: {}", orgId, last.state(), last.message());
    }

    private static Progress step(Progress first, int sourcesDone, int passagesDone, String message) {
        return new Progress(
                "running",
                first.sourcesTotal(),
                Math.min(sourcesDone, first.sourcesTotal()),
                first.passagesTotal(),
                Math.min(passagesDone, first.passagesTotal()),
                message == null
                        ? "Re-indexing: " + Math.min(passagesDone, first.passagesTotal()) + " of "
                                + first.passagesTotal() + " passage(s) done."
                        : message,
                first.startedAt(),
                null);
    }

    private void publish(UUID orgId, Progress progress) {
        running.put(orgId, progress);
        save(orgId, progress);
    }

    private void save(UUID orgId, Progress progress) {
        try {
            settings.write(orgId, EmbeddingSettings.PROGRESS_KEY, progress, "system");
        } catch (RuntimeException e) {
            log.debug("Progress for workspace {} could not be saved: {}", orgId, e.getMessage());
        }
    }
}
