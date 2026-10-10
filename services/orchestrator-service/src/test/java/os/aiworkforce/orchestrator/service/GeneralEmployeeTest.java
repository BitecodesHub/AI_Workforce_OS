// @find: tests for general employee, general employee, fallback agent, is_fallback, unmatched requests
// @what: Unit and integration tests (8 cases) for general employee, for example: creates when missing; uses next key when general is taken; does nothing when already flagged; swallows race on create.
package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.AgentVersion;
import os.aiworkforce.orchestrator.repository.AgentVersions;
import os.aiworkforce.orchestrator.repository.Agents;

/** The workspace's employee of last resort, found by a flag rather than a key. */
class GeneralEmployeeTest {

    private static final UUID ORG = UUID.randomUUID();

    private final Agents agents = mock(Agents.class);
    private final AgentVersions versions = mock(AgentVersions.class);
    private final PlatformTransactionManager transactions = transactionManager();

    private GeneralEmployee employee() {
        return new GeneralEmployee(agents, versions, transactions);
    }

    private static PlatformTransactionManager transactionManager() {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        return transactions;
    }

    private static Agent agent(String key, boolean fallback, String status) {
        Agent agent = new Agent();
        agent.setId(UUID.randomUUID());
        agent.setOrgId(ORG);
        agent.setKey(key);
        agent.setName("Something");
        agent.setCategory("operations");
        agent.setFallback(fallback);
        agent.setStatus(status);
        return agent;
    }

    @Test
    @DisplayName("creates the agent and its first version when the workspace has no flagged General")
    void createsWhenMissing() {
        when(agents.findByOrgIdAndFallbackTrue(ORG)).thenReturn(Optional.empty());
        when(agents.findByOrgIdAndKey(ORG, "general")).thenReturn(Optional.empty());

        employee().ensure(ORG);

        ArgumentCaptor<Agent> savedAgent = ArgumentCaptor.forClass(Agent.class);
        verify(agents, times(2)).save(savedAgent.capture());
        Agent finalAgent = savedAgent.getValue();
        assertThat(finalAgent.getKey()).isEqualTo("general");
        assertThat(finalAgent.getName()).isEqualTo(GeneralEmployee.NAME);
        assertThat(finalAgent.getCategory()).isEqualTo(GeneralEmployee.CATEGORY);
        assertThat(finalAgent.isFallback()).isTrue();
        assertThat(finalAgent.getOrgId()).isEqualTo(ORG);
        assertThat(finalAgent.getCurrentVersionId()).isNotNull();

        ArgumentCaptor<AgentVersion> savedVersion = ArgumentCaptor.forClass(AgentVersion.class);
        verify(versions).save(savedVersion.capture());
        AgentVersion version = savedVersion.getValue();
        assertThat(version.getRevision()).isEqualTo(1);
        assertThat(version.getSystemPrompt()).isEqualTo(GeneralEmployee.PROMPT);
        assertThat(version.getMaxSteps()).isEqualTo(GeneralEmployee.MAX_STEPS);
        assertThat(version.getCreatedBy()).isEqualTo("system");
        assertThat(version.getOrgId()).isEqualTo(ORG);
    }

    @Test
    @DisplayName("falls back to the second key when a custom agent already owns 'general', and leaves it unflagged")
    void usesNextKeyWhenGeneralIsTaken() {
        Agent custom = agent("general", false, "active");
        when(agents.findByOrgIdAndFallbackTrue(ORG)).thenReturn(Optional.empty());
        when(agents.findByOrgIdAndKey(ORG, "general")).thenReturn(Optional.of(custom));
        when(agents.findByOrgIdAndKey(ORG, "general-employee")).thenReturn(Optional.empty());

        employee().ensure(ORG);

        ArgumentCaptor<Agent> savedAgent = ArgumentCaptor.forClass(Agent.class);
        verify(agents, times(2)).save(savedAgent.capture());
        assertThat(savedAgent.getValue().getKey()).isEqualTo("general-employee");
        assertThat(custom.isFallback()).isFalse();
    }

    @Test
    @DisplayName("does nothing when a flagged row already exists, however it is keyed or however it is paused")
    void doesNothingWhenAlreadyFlagged() {
        Agent existing = agent("custom-general-name", true, "paused");
        when(agents.findByOrgIdAndFallbackTrue(ORG)).thenReturn(Optional.of(existing));

        employee().ensure(ORG);

        verify(agents, never()).save(any());
        verify(versions, never()).save(any());
    }

    @Test
    @DisplayName("a race that violates the one-fallback constraint is swallowed, not thrown")
    void swallowsRaceOnCreate() {
        when(agents.findByOrgIdAndFallbackTrue(ORG)).thenReturn(Optional.empty());
        when(agents.findByOrgIdAndKey(ORG, "general")).thenReturn(Optional.empty());
        when(agents.save(any())).thenThrow(new DataIntegrityViolationException("agents_one_fallback"));

        assertThatCode(() -> employee().ensure(ORG)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a second call makes no repository call, because the workspace is already remembered")
    void secondCallIsFree() {
        when(agents.findByOrgIdAndFallbackTrue(ORG)).thenReturn(Optional.empty());
        when(agents.findByOrgIdAndKey(ORG, "general")).thenReturn(Optional.empty());
        GeneralEmployee employee = employee();

        employee.ensure(ORG);
        org.mockito.Mockito.clearInvocations(agents, versions);
        employee.ensure(ORG);

        verify(agents, never()).findByOrgIdAndFallbackTrue(any());
        verify(agents, never()).save(any());
        verify(versions, never()).save(any());
    }

    @Test
    @DisplayName("activeIn ignores a paused General")
    void activeInIgnoresPaused() {
        Agent paused = agent("general", true, "paused");

        Optional<Agent> found = employee().activeIn(List.of(paused));

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("activeIn finds the flagged agent when it is active")
    void activeInFindsActive() {
        Agent other = agent("hr", false, "active");
        Agent general = agent("general", true, "active");

        Optional<Agent> found = employee().activeIn(List.of(other, general));

        assertThat(found).contains(general);
    }

    @Test
    @DisplayName("isFallback is false for an unflagged agent keyed 'general'")
    void isFallbackRequiresTheFlag() {
        Agent unflagged = agent("general", false, "active");

        assertThat(GeneralEmployee.isFallback(unflagged)).isFalse();
    }
}
