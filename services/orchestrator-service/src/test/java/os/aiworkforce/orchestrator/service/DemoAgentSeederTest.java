package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.AgentToolGrant;
import os.aiworkforce.orchestrator.domain.AgentVersion;
import os.aiworkforce.orchestrator.repository.AgentVersions;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.ToolGrants;
import os.aiworkforce.platform.config.PlatformProperties;

/** The demo workspace's four agents, their A5.2 prompts, and the General Employee that follows them. */
class DemoAgentSeederTest {

    private static final UUID ORG = DemoAgentSeeder.DEMO_ORG_ID;

    /** The exact prompt HR shipped with before A5.2 - only used here to prove it was replaced. */
    private static final String LEGACY_HR_PROMPT =
            """
            You handle people operations for a small care provider. Screen applications \
            against the stated requirements of the role, draft onboarding email in plain \
            English, and book interviews. Leave email as a draft for a person to send.""";

    private final Agents agents = mock(Agents.class);
    private final AgentVersions versions = mock(AgentVersions.class);
    private final ToolGrants grants = mock(ToolGrants.class);
    private final PlatformProperties properties = mock(PlatformProperties.class);
    private final GeneralEmployee generalEmployee = mock(GeneralEmployee.class);
    private final Map<UUID, AgentVersion> versionStore = new HashMap<>();
    private DemoAgentSeeder seeder;

    @BeforeEach
    void setUp() {
        when(properties.environment()).thenReturn(PlatformProperties.Environment.LOCAL);
        when(agents.findByOrgIdAndKey(eq(ORG), any())).thenReturn(Optional.empty());
        when(grants.findByAgentIdAndServer(any(), any())).thenReturn(Optional.empty());

        // A small stand-in for the versions table, so a version saved during one seed() call is
        // the one a later call reads back - real enough to prove the upgrade is not repeated.
        when(versions.findById(any()))
                .thenAnswer(invocation -> Optional.ofNullable(versionStore.get(invocation.getArgument(0, UUID.class))));
        when(versions.save(any())).thenAnswer(invocation -> {
            AgentVersion saved = invocation.getArgument(0, AgentVersion.class);
            versionStore.put(saved.getId(), saved);
            return saved;
        });

        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        seeder = new DemoAgentSeeder(agents, versions, grants, properties, generalEmployee, transactions);
    }

    /** An already-seeded demo agent, with a current version carrying the given prompt. */
    private Agent existingAgent(String key, String systemPrompt, String createdBy) {
        Agent agent = new Agent();
        agent.setId(UUID.randomUUID());
        agent.setOrgId(ORG);
        agent.setKey(key);
        agent.setName(key);
        agent.setCategory("operations");
        UUID versionId = UUID.randomUUID();
        agent.setCurrentVersionId(versionId);

        AgentVersion version = new AgentVersion();
        version.setId(versionId);
        version.setAgentId(agent.getId());
        version.setOrgId(ORG);
        version.setRevision(1);
        version.setSystemPrompt(systemPrompt);
        version.setMaxSteps(8);
        version.setCreatedBy(createdBy);
        versionStore.put(versionId, version);

        when(agents.findByOrgIdAndKey(ORG, key)).thenReturn(Optional.of(agent));
        return agent;
    }

    @Test
    @DisplayName("a fresh seed creates every demo agent with the new A5.2 prompt, not the legacy one")
    void freshSeedUsesNewPrompts() {
        seeder.seed();

        ArgumentCaptor<AgentVersion> saved = ArgumentCaptor.forClass(AgentVersion.class);
        verify(versions, times(4)).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(version -> {
            assertThat(version.getSystemPrompt()).contains("How you work");
            assertThat(version.getSystemPrompt()).isNotEqualTo(LEGACY_HR_PROMPT);
            assertThat(version.getCreatedBy()).isEqualTo("system");
        });
    }

    @Test
    @DisplayName("a failing prompt upgrade is swallowed: it neither throws nor undoes the agents already created")
    void aFailingUpgradeDoesNotThrowOrUndoTheSeed() {
        // Every demo agent already exists, so creation has nothing to do; hr alone is still on
        // its legacy prompt and so is the only one upgradePrompts() would touch.
        existingAgent("hr", LEGACY_HR_PROMPT, "system");
        existingAgent("engineering-manager", "Already on the new prompt.", "system");
        existingAgent("research", "Already on the new prompt.", "system");
        existingAgent("support", "Already on the new prompt.", "system");
        when(versions.highestRevision(any())).thenThrow(new RuntimeException("boom"));

        assertThatCode(() -> seeder.seed()).doesNotThrowAnyException();

        // The failing upgrade must not have saved a partial revision, for hr or anyone else.
        verify(versions, never()).save(any());
        verify(agents, never()).save(any());
        verify(generalEmployee).ensure(ORG);
    }

    @Test
    @DisplayName("an untouched legacy prompt is upgraded once, and a second run adds no further revision")
    void untouchedLegacyPromptIsUpgradedOnce() {
        // Every demo agent already exists, so nothing is created on either run; hr alone starts
        // on its legacy prompt.
        Agent hr = existingAgent("hr", LEGACY_HR_PROMPT, "system");
        existingAgent("engineering-manager", "Already on the new prompt.", "system");
        existingAgent("research", "Already on the new prompt.", "system");
        existingAgent("support", "Already on the new prompt.", "system");
        when(versions.highestRevision(hr.getId())).thenReturn(1);

        seeder.seed();
        seeder.seed();

        ArgumentCaptor<AgentVersion> saved = ArgumentCaptor.forClass(AgentVersion.class);
        verify(versions).save(saved.capture());
        assertThat(saved.getAllValues()).hasSize(1);
        assertThat(saved.getValue().getAgentId()).isEqualTo(hr.getId());
    }

    @Test
    @DisplayName("a prompt a person has since edited is never rewritten")
    void editedPromptIsLeftAlone() {
        Agent hr = existingAgent("hr", "Our own house style for onboarding email.", "a-person-id");
        UUID originalVersionId = hr.getCurrentVersionId();

        seeder.seed();

        verify(versions, never()).save(argThatAgentId(hr.getId()));
        assertThat(hr.getCurrentVersionId()).isEqualTo(originalVersionId);
    }

    @Test
    @DisplayName("support is given the drive grant once, even across repeated seeds")
    void supportGetsDriveGrantOnce() {
        // Every demo agent already exists, so only the grant backfill is under test here.
        existingAgent("hr", "Already on the new prompt.", "system");
        existingAgent("engineering-manager", "Already on the new prompt.", "system");
        existingAgent("research", "Already on the new prompt.", "system");
        Agent support = existingAgent("support", "Already on the new prompt, so nothing to upgrade here.", "system");
        when(grants.findByAgentIdAndServer(support.getId(), "drive"))
                .thenReturn(Optional.empty(), Optional.of(new AgentToolGrant()));

        seeder.seed();
        seeder.seed();

        ArgumentCaptor<AgentToolGrant> saved = ArgumentCaptor.forClass(AgentToolGrant.class);
        verify(grants, times(1)).save(saved.capture());
        AgentToolGrant grant = saved.getValue();
        assertThat(grant.getAgentId()).isEqualTo(support.getId());
        assertThat(grant.getServer()).isEqualTo("drive");
        assertThat(grant.getAllowedTools()).containsExactly("list_files", "get_file");
        assertThat(grant.getScopes()).containsExactly("drive.readonly");
        assertThat(grant.getMaxCallsPerRun()).isEqualTo(10);
    }

    @Test
    @DisplayName("seeding the demo workspace always ensures its General Employee, last")
    void seedEnsuresGeneralEmployee() {
        seeder.seed();

        verify(generalEmployee).ensure(ORG);
    }

    private static AgentVersion argThatAgentId(UUID agentId) {
        return org.mockito.ArgumentMatchers.argThat(version -> version != null && agentId.equals(version.getAgentId()));
    }
}
