// @find: tests for knowledge search tool, knowledge search tool, grounding, citations, RAG, passages, documents search
// @what: Unit and integration tests (4 cases) for knowledge search tool, for example: offered only when workspace has indexed sources and remembered; work with nobody behind it gets no documents and no search; person without access is told; parse clamps limit and rejects empty query.
package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import os.aiworkforce.orchestrator.chat.KnowledgeClient;

class KnowledgeSearchToolTest {

    private final KnowledgeClient client = mock(KnowledgeClient.class);
    private final KnowledgeSearchTool tool = new KnowledgeSearchTool(client, new ObjectMapper());
    private static final UUID ORG = UUID.randomUUID();

    @Test
    void offeredOnlyWhenWorkspaceHasIndexedSourcesAndRemembered() {
        when(client.hasIndexedSources(ORG)).thenReturn(Optional.of(true));
        assertThat(tool.isOfferedIn(ORG)).isTrue();
        assertThat(tool.isOfferedIn(ORG)).isTrue();
        verify(client, times(1)).hasIndexedSources(ORG);

        UUID empty = UUID.randomUUID();
        when(client.hasIndexedSources(empty)).thenReturn(Optional.of(false));
        assertThat(tool.isOfferedIn(empty)).isFalse();
        UUID down = UUID.randomUUID();
        when(client.hasIndexedSources(down)).thenReturn(Optional.empty());
        assertThat(tool.isOfferedIn(down)).isFalse();
    }

    @Test
    void workWithNobodyBehindItGetsNoDocumentsAndNoSearch() {
        KnowledgeSearchTool.Searched result = tool.search(ORG, null, UUID.randomUUID(), UUID.randomUUID(), "refunds", 4);
        assertThat(result.failed()).isTrue();
        assertThat(result.failure()).isEqualTo(KnowledgeSearchTool.NOT_AVAILABLE);
        verify(client, never()).searchFor(any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void personWithoutAccessIsTold() {
        when(client.searchFor(any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(KnowledgeClient.AgentSearch.failed(KnowledgeClient.Failure.NOT_ALLOWED));
        KnowledgeSearchTool.Searched result =
                tool.search(ORG, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "refunds", 4);
        assertThat(result.failure()).isEqualTo(KnowledgeSearchTool.NOT_ALLOWED);
    }

    @Test
    void parseClampsLimitAndRejectsEmptyQuery() throws Exception {
        assertThat(tool.parse("{\"query\":\" refunds \",\"limit\":99}").limit()).isEqualTo(KnowledgeSearchTool.MAX_LIMIT);
        assertThat(tool.parse("{\"query\":\"x\"}").limit()).isEqualTo(KnowledgeSearchTool.DEFAULT_LIMIT);
        org.junit.jupiter.api.Assertions.assertThrows(KnowledgeSearchTool.Invalid.class, () -> tool.parse("{\"query\":\"\"}"));
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }
}
