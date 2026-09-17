package os.aiworkforce.memory.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.memory.domain.Episode;
import os.aiworkforce.memory.repository.Episodes;
import os.aiworkforce.platform.context.RequestContext;

/**
 * The permanent record of what agents have observed and decided.
 *
 * <p>Two operations matter. <b>Recall</b> answers "what do we already know about this?" before an
 * agent starts work, which is what stops it asking a colleague the same question twice a week.
 * <b>Compaction</b> keeps recall useful as the record grows: after a few thousand episodes, an
 * unfiltered recall returns more than fits in any context window, and the agent effectively
 * remembers nothing.
 */
@Service
public class EpisodicMemory {

    private static final Logger log = LoggerFactory.getLogger(EpisodicMemory.class);

    /** Episodes below this importance are eligible to be folded into a summary. */
    private static final int COMPACTION_IMPORTANCE_CEILING = 6;

    /** How old an episode must be before compaction will touch it. */
    private static final Duration COMPACTION_AGE = Duration.ofDays(30);

    private final Episodes episodes;

    public EpisodicMemory(Episodes episodes) {
        this.episodes = episodes;
    }

    @Transactional
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
    public List<Episode> recall(UUID orgId, UUID agentId, String query, int limit) {
        int capped = Math.min(Math.max(limit, 1), 50);
        if (query == null || query.isBlank()) {
            return episodes.findRecent(orgId, agentId, PageRequest.of(0, capped));
        }
        return episodes.search(orgId, query, PageRequest.of(0, capped));
    }

    @Transactional(readOnly = true)
    public List<Episode> forRun(UUID orgId, UUID runId) {
        return episodes.findByOrgIdAndRunIdOrderByOccurredAt(orgId, runId);
    }

    /**
     * Folds old, low-importance episodes into one summary per agent.
     *
     * <p>A summary rather than a deletion, and it records which episodes it replaced. Deleting
     * them outright would leave an agent confidently unaware that something happened; a summary
     * that says "forty observations were folded" is at least honest about what was lost.
     *
     * <p>Decisions, handoffs and outcomes are never compacted, whatever their age: those are the
     * ones somebody reconstructs an incident from.
     */
    @Transactional
    public int compact(UUID orgId, int batchSize) {
        Instant before = Instant.now().minus(COMPACTION_AGE);
        List<Episode> candidates =
                episodes.findCompactable(orgId, before, COMPACTION_IMPORTANCE_CEILING, PageRequest.of(0, batchSize));

        if (candidates.size() < 2) {
            return 0;
        }

        Map<UUID, List<Episode>> byAgent = candidates.stream()
                .filter(episode -> episode.getAgentId() != null)
                .collect(Collectors.groupingBy(Episode::getAgentId));

        int compacted = 0;
        for (Map.Entry<UUID, List<Episode>> entry : byAgent.entrySet()) {
            List<Episode> group = entry.getValue();
            if (group.size() < 2) {
                continue;
            }

            Episode summary =
                    Episode.of(orgId, entry.getKey(), "summary", summarise(group), COMPACTION_IMPORTANCE_CEILING);
            summary.setOccurredAt(group.get(0).getOccurredAt());
            summary.setSupersedes(group.stream().map(Episode::getId).toList());
            summary.setDetail(Map.of(
                    "episodeCount", group.size(),
                    "from", group.get(0).getOccurredAt().toString(),
                    "to", group.get(group.size() - 1).getOccurredAt().toString()));
            episodes.save(summary);

            // The originals are marked rather than deleted, so the summary can still be checked
            // against what it replaced until the retention sweep removes them.
            episodes.markCompacted(group.stream().map(Episode::getId).toList());
            compacted += group.size();
        }

        if (compacted > 0) {
            log.info("Compacted {} episode(s) for workspace {}", compacted, orgId);
        }
        return compacted;
    }

    /**
     * A factual summary of the episodes being folded.
     *
     * <p>Written mechanically rather than by asking a model. A model-written summary of records
     * the agent can no longer check is a confident invention waiting to be quoted back as fact.
     */
    private String summarise(List<Episode> group) {
        Map<String, Long> byKind =
                group.stream().collect(Collectors.groupingBy(Episode::getKind, Collectors.counting()));
        List<String> parts = new ArrayList<>();
        byKind.forEach((kind, count) -> parts.add(count + " " + kind + (count == 1 ? "" : "s")));
        return "Between " + group.get(0).getOccurredAt() + " and "
                + group.get(group.size() - 1).getOccurredAt() + ", "
                + String.join(", ", parts)
                + " were recorded and have been folded into this summary. "
                + "Treat the detail as unavailable rather than as agreed.";
    }

    /** Removes episodes past their retention date. Runs in small batches for the same reason. */
    @Transactional
    public int purgeExpired(int batchSize) {
        return episodes.deleteExpired(Instant.now(), batchSize);
    }

    private static int clamp(int importance) {
        return Math.min(10, Math.max(1, importance));
    }
}

// Week 1 update by LoveShah21

// Week 2 update by LoveShah21

// Week 3 update by LoveShah21
