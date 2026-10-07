package os.aiworkforce.memory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import os.aiworkforce.memory.domain.AgentMemory;
import os.aiworkforce.memory.repository.AgentMemories;
import os.aiworkforce.platform.error.ApiException;

class AgentMemoryServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID AGENT = UUID.randomUUID();

    private AgentMemories repo;
    private AgentMemoryService service;

    @BeforeEach
    void setUp() {
        repo = mock(AgentMemories.class);
        when(repo.save(any(AgentMemory.class))).thenAnswer(call -> call.getArgument(0));
        when(repo.findSame(any(), any(), anyString())).thenReturn(List.of());
        service = new AgentMemoryService(repo);
    }

    @Test
    void keepsANote() {
        AgentMemoryService.Saved saved =
                service.add(ORG, AGENT, null, "  The Brisbane clinic closes at 4pm on Fridays.  ", "agent", "svc", null);
        assertThat(saved.created()).isTrue();
        assertThat(saved.memory().getContent()).isEqualTo("The Brisbane clinic closes at 4pm on Fridays.");
        assertThat(saved.memory().getKind()).isEqualTo("fact");
        assertThat(saved.memory().getSource()).isEqualTo("agent");
    }

    @Test
    void refusesSecrets() {
        for (String secret : List.of(
                "The password is hunter2hunter2",
                "use api key sk-abcdefghijklmnopqrstuvwx",
                "card 4111 1111 1111 1111",
                "token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijklmnop")) {
            assertThatThrownBy(() -> service.add(ORG, AGENT, "fact", secret, "agent", "svc", null))
                    .as(secret)
                    .isInstanceOf(ApiException.class);
        }
        verify(repo, never()).save(any());
    }

    @Test
    void ordinaryNumbersAreNotSecrets() {
        assertThat(SecretGuard.looksSecret("Invoices are due in 14 days and the rate is 38.50 per hour.")).isFalse();
        assertThat(SecretGuard.looksSecret("Call the office on 07 3000 1234 before 5pm.")).isFalse();
    }

    @Test
    void theSameNoteIsOneNote() {
        AgentMemory existing = AgentMemory.of(ORG, AGENT, "fact", "Rates are 38.50.", "person", "u");
        when(repo.findSame(ORG, AGENT, "Rates are 38.50.")).thenReturn(List.of(existing));
        AgentMemoryService.Saved saved = service.add(ORG, AGENT, "fact", "Rates are 38.50.", "agent", "svc", null);
        assertThat(saved.created()).isFalse();
        assertThat(saved.memory()).isSameAs(existing);
    }

    @Test
    void aFullMemoryAsksForATidy() {
        when(repo.countByOrgIdAndAgentId(ORG, AGENT)).thenReturn((long) AgentMemoryService.MAX_PER_AGENT);
        assertThatThrownBy(() -> service.add(ORG, AGENT, "fact", "One more thing.", "agent", "svc", null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("full");
    }

    @Test
    void recallSearchesTheQueryWordsAndCountsTheRecall() {
        AgentMemory hit = AgentMemory.of(ORG, AGENT, "fact", "Refunds take 5 business days.", "agent", "svc");
        when(repo.search(ORG, AGENT, "refund | policy", 8)).thenReturn(List.of(hit));

        List<AgentMemory> recalled = service.recall(ORG, AGENT, "refund policy", 8);

        assertThat(recalled).containsExactly(hit);
        assertThat(hit.getRecallCount()).isEqualTo(1);
    }

    @Test
    void recallWithNoMatchFallsBackToTheNewest() {
        AgentMemory recent = AgentMemory.of(ORG, AGENT, "note", "Prefers short replies.", "person", "u");
        when(repo.search(any(), any(), anyString(), anyInt())).thenReturn(List.of());
        when(repo.findByOrgIdAndAgentIdOrderByUpdatedAtDesc(any(), any(), any(Pageable.class)))
                .thenReturn(List.of(recent));
        assertThat(service.recall(ORG, AGENT, "something unrelated", 5)).containsExactly(recent);
    }

    @Test
    void anotherAgentsNoteIsNotFound() {
        when(repo.findByIdAndOrgIdAndAgentId(any(), any(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(ORG, AGENT, UUID.randomUUID(), "fact", "x y z", "u"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.delete(ORG, AGENT, UUID.randomUUID())).isInstanceOf(ApiException.class);
    }

    @Test
    void tsQueryUsesWordsOnly() {
        assertThat(AgentMemoryService.tsQueryOf("Who handles refunds?! (a 'b')")).isEqualTo("who | handles | refunds");
        assertThat(AgentMemoryService.tsQueryOf("  ?? ")).isNull();
    }
}
