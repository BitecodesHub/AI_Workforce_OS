// @find: episodic memory, record episode, recall episodes, episodes for run, purge expired, what do we already know, agent history
// @what: Records, recalls and purges the permanent record of what agents did and decided.
// @flow: Called by InternalMemoryController, MemoryController and EpisodePurgeJob
package os.aiworkforce.memory.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.memory.domain.Episode;
import os.aiworkforce.memory.repository.Episodes;
import os.aiworkforce.platform.context.RequestContext;

/**
 * The permanent record of what agents have observed and decided.
 *
 * <p><b>Recall</b> answers "what do we already know about this?" before an agent starts work,
 * which is what stops it asking a colleague the same question twice a week. Recall is ordered by
 * importance then recency and capped, so it stays within a context window as the record grows.
 * Expired episodes are removed by {@link EpisodePurgeJob} through {@link #purgeExpired}.
 */
@Service
public class EpisodicMemory {

    private final Episodes episodes;

    /** Creates the service over the episodes repository, which is the only store it touches. */
    public EpisodicMemory(Episodes episodes) {
        this.episodes = episodes;
    }

    /**
     * Appends one episode to the permanent record.
     *
     * Flow: the importance is clamped to 1 through 10, an episode is built for the organisation and agent,
     * the run id and detail map are attached, the acting user (when the request has one) is stored as the
     * creator, and the row is saved. Called by InternalMemoryController, which validates the request
     * first. Side effect: one insert. A database failure propagates to the caller.
     *
     * @param orgId organisation that owns the episode
     * @param agentId agent the episode is about; may be null for workspace-wide events
     * @param runId run during which it happened; may be null
     * @param kind category such as observation, decision, handoff or outcome
     * @param summary short text that is searched and recalled
     * @param importance weight from 1 to 10; values outside the range are clamped
     * @param detail structured extra data stored beside the summary; may be null
     * @return the saved episode
     */
    @Transactional
    // @find: record episode, save what an agent did
    public Episode record(
            UUID orgId,
            UUID agentId,
            UUID runId,
            String kind,
            String summary,
            int importance,
            Map<String, Object> detail) {
        Episode episode = Episode.of(orgId, agentId, kind, summary, clamp(importance));
        episode.setRunId(runId);
        episode.setDetail(detail);
        RequestContext.actor().ifPresent(actor -> episode.setCreatedBy(actor.id()));
        return episodes.save(episode);
    }

    /**
     * What an agent should be reminded of before it starts.
     *
     * <p>Ordered by importance then recency, and capped. Returning everything would be honest and
     * useless: the caller has a context window, and an unbounded recall simply pushes the actual
     * task out of it.
     */
    @Transactional(readOnly = true)
    // @find: recall episodes, search episodes
    public List<Episode> recall(UUID orgId, UUID agentId, String query, int limit) {
        int capped = Math.min(Math.max(limit, 1), 50);
        if (query == null || query.isBlank()) {
            return episodes.findRecent(orgId, agentId, PageRequest.of(0, capped));
        }
        return episodes.search(orgId, agentId, query, PageRequest.of(0, capped));
    }

    /**
     * Lists the episodes recorded during one run, oldest first. Read-only; called by MemoryController to show
     * what an agent did in a run. Returns an empty list when the run has none.
     *
     * @param orgId organisation scope
     * @param runId run to look up
     */
    @Transactional(readOnly = true)
    // @find: episodes for a run, run memory
    public List<Episode> forRun(UUID orgId, UUID runId) {
        return episodes.findByOrgIdAndRunIdOrderByOccurredAt(orgId, runId);
    }

    /**
     * Removes episodes past their retention date. Runs in small batches for the same reason.
     *
     * <p>Deletes episodes whose expiry has passed, at most {@code batchSize} per call. Called by
     * {@link EpisodePurgeJob} in a loop. Returns the number of rows removed; this is a hard delete.
     *
     * @param batchSize maximum rows removed in this call
     */
    @Transactional
    // @find: purge expired episodes, retention cleanup
    public int purgeExpired(int batchSize) {
        return episodes.deleteExpired(Instant.now(), batchSize);
    }

    /** Restricts an importance value to the range 1 through 10. */
    private static int clamp(int importance) {
        return Math.min(10, Math.max(1, importance));
    }
}
