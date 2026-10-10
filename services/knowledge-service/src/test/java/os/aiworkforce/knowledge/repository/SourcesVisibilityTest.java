// @find: tests for agent-owned sources visibility, agent documents hidden from workspace list, restricted sources, knowledge base sources
// @what: Checks an agent's own sources are read by that agent only and never listed with the workspace's.
package os.aiworkforce.knowledge.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.doReturn;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import os.aiworkforce.knowledge.domain.Source;

/** An agent's own sources are read by that agent only, and never listed with the workspace's. */
class SourcesVisibilityTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID AGENT = UUID.randomUUID();

    private static Source source(String name) {
        Source source = new Source();
        source.setName(name);
        return source;
    }

    @Test
    void workspaceListsNeverIncludeAgentSourcesAndAnAgentSeesOnlyItsOwn() {
        Sources sources = mock(Sources.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        Source workspace = source("Policies");
        Source restricted = source("HR");
        Source mine = source("Mine");
        doReturn(List.of(workspace, restricted)).when(sources).findByOrgIdAndAgentIdIsNullOrderByName(ORG);
        doReturn(List.of(workspace)).when(sources).findByOrgIdAndAgentIdIsNullAndRestrictedFalseOrderByName(ORG);
        doReturn(List.of(mine)).when(sources).findByOrgIdAndAgentIdOrderByName(ORG, AGENT);

        assertThat(sources.findVisible(ORG, true)).containsExactly(workspace, restricted);
        assertThat(sources.findVisible(ORG, false)).containsExactly(workspace);
        assertThat(sources.findVisibleTo(ORG, false, AGENT)).containsExactly(workspace, mine);
        assertThat(sources.findVisibleTo(ORG, false, null)).containsExactly(workspace);
        assertThat(sources.findVisibleTo(ORG, false, UUID.randomUUID())).containsExactly(workspace);
    }
}
