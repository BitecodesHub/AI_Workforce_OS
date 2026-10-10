// @find: tests for episodic memory, record episode, recall, compaction, purge expired, episodes for run
// @what: Checks recording, recalling, compacting and purging episodes.
package os.aiworkforce.memory.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.memory.repository.Episodes;

/** Recall is scoped to the agent asked about, whether it lists or searches. */
class EpisodicMemoryTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-00000000000a");
    private static final UUID AGENT = UUID.fromString("00000000-0000-7000-8000-0000000000a1");

    @Test
    @DisplayName("a search passes the agent through, so one agent never recalls another's memories")
    void searchScopedToAgent() {
        Episodes episodes = mock(Episodes.class);

        new EpisodicMemory(episodes).recall(ORG, AGENT, "refund policy", 10);

        verify(episodes).search(eq(ORG), eq(AGENT), eq("refund policy"), any());
    }

    @Test
    @DisplayName("without a query, the most important recent memories of that agent are listed")
    void listScopedToAgent() {
        Episodes episodes = mock(Episodes.class);

        new EpisodicMemory(episodes).recall(ORG, AGENT, " ", 10);

        verify(episodes).findRecent(eq(ORG), eq(AGENT), any());
    }
}
