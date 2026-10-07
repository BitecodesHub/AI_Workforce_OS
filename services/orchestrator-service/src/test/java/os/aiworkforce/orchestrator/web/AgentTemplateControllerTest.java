package os.aiworkforce.orchestrator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.AgentVersion;
import os.aiworkforce.orchestrator.repository.AgentVersions;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.ToolGrants;
import os.aiworkforce.orchestrator.service.AgentRunner;
import os.aiworkforce.orchestrator.service.AgentTemplates;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.orchestrator.service.GeneralEmployee;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Adding a ready-made assistant to a real workspace. The agent is the real {@link AgentController}
 * over in-memory tables, so "the same path as POST /api/agents" is what is being run.
 */
class AgentTemplateControllerTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-0000000000a1");
    private static final String PERSON = "7e0e27f0-0000-7000-8000-00000000cafe";

    /** Keys already taken in the workspace. */
    private final Set<String> takenKeys = new HashSet<>();

    private final List<Agent> savedAgents = new ArrayList<>();
    private final List<AgentVersion> savedVersions = new ArrayList<>();
    private ToolGrants grants;
    private AuditClient audit;
    private AgentTemplateController controller;
    private Agents agents;

    @BeforeEach
    void setUp() {
        agents = mock(Agents.class);
        when(agents.findByOrgIdAndKey(eq(ORG), any()))
                .thenAnswer(call -> takenKeys.contains(call.<String>getArgument(1)) ? Optional.of(new Agent()) : Optional.empty());
        when(agents.save(any())).thenAnswer(call -> {
            Agent agent = call.getArgument(0);
            if (!savedAgents.contains(agent)) {
                savedAgents.add(agent);
                takenKeys.add(agent.getKey());
            }
            return agent;
        });
        AgentVersions versions = mock(AgentVersions.class);
        when(versions.highestRevision(any())).thenReturn(0);
        when(versions.save(any())).thenAnswer(call -> {
            AgentVersion version = call.getArgument(0);
            savedVersions.add(version);
            return version;
        });
        grants = mock(ToolGrants.class);
        audit = mock(AuditClient.class);

        AgentController agentController =
                new AgentController(agents, versions, mock(AgentRunner.class), grants, mock(GeneralEmployee.class));
        controller = new AgentTemplateController(agentController, agents, audit);
        RequestContext.setActor(Actor.user(PERSON, ORG.toString(), "owner", Set.of(), 0L));
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("the catalogue lists the four assistants with what they do and what to connect, and no prompt")
    void listsTheCatalogue() {
        List<AgentTemplateController.TemplateView> templates = controller.list();

        assertThat(templates).extracting(AgentTemplateController.TemplateView::key)
                .containsExactly("hr", "engineering-manager", "research", "support");
        assertThat(templates).allSatisfy(template -> {
            assertThat(template.description()).isNotBlank();
            assertThat(template.suggestedConnectors()).isNotEmpty();
        });
        assertThat(templates.getFirst().suggestedConnectors()).containsExactly("gmail", "calendar");
        assertThat(templates.toString()).doesNotContain("How you work");
    }

    @Test
    @DisplayName("adding a template creates the agent and revision 1 as the caller, with the template's own prompt")
    void createsTheAgentAsTheCaller() {
        AgentTemplateController.FromTemplateResult result = controller.create("hr");

        assertThat(result.agent().key()).isEqualTo("hr");
        assertThat(result.agent().name()).isEqualTo("HR");
        assertThat(result.agent().category()).isEqualTo("operations");
        assertThat(result.agent().revision()).isEqualTo(1);
        assertThat(result.suggestedConnectors()).containsExactly("gmail", "calendar");

        assertThat(savedVersions).hasSize(1);
        AgentVersion version = savedVersions.getFirst();
        assertThat(version.getRevision()).isEqualTo(1);
        assertThat(version.getSystemPrompt()).isEqualTo(AgentTemplates.find("hr").orElseThrow().prompt());
        // The person who asked, never "system": that is how the demo seeder marks an agent it may rewrite.
        assertThat(version.getCreatedBy()).isEqualTo(PERSON);
        assertThat(savedAgents.getFirst().getOrgId()).isEqualTo(ORG);
    }

    @Test
    @DisplayName("adding a template grants nothing: no connector is given to the new agent")
    void createsNoGrants() {
        for (AgentTemplates.Template template : AgentTemplates.all()) {
            AgentTemplateController.FromTemplateResult result = controller.create(template.key());
            // The agent's own card reads its grants, and finds none.
            assertThat(result.agent().tools()).isEmpty();
        }

        verify(grants, never()).save(any());
        verify(grants, never()).saveAll(any());
        verify(grants, never()).saveAndFlush(any());
        assertThat(savedAgents).hasSize(4);
    }

    @Test
    @DisplayName("a key already taken gets -2, then -3, so adding twice never fails")
    void aKeyClashGetsASuffix() {
        takenKeys.add("hr");
        assertThat(controller.create("hr").agent().key()).isEqualTo("hr-2");
        assertThat(controller.create("hr").agent().key()).isEqualTo("hr-3");
        assertThat(controller.create("engineering-manager").agent().key()).isEqualTo("engineering-manager");
    }

    @Test
    @DisplayName("a key taken between the look and the create moves on to the next one")
    void aKeyTakenInTheMeantimeMovesOn() {
        // Free when looked at, taken when AgentController checks: the second look finds it.
        when(agents.findByOrgIdAndKey(ORG, "research"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new Agent()));

        assertThat(controller.create("research").agent().key()).isEqualTo("research-2");
    }

    @Test
    @DisplayName("a template that does not exist is a 404, and nothing is created")
    void anUnknownTemplateIsNotFound() {
        assertThatThrownBy(() -> controller.create("nobody"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(savedAgents).isEmpty();
    }

    @Test
    @DisplayName("adding a template is audited, naming the template and the key it was created under")
    void isAudited() {
        takenKeys.add("support");
        AgentTemplateController.FromTemplateResult result = controller.create("support");

        ArgumentCaptor<java.util.Map<String, Object>> detail = ArgumentCaptor.forClass(java.util.Map.class);
        verify(audit)
                .record(
                        eq(ORG),
                        any(Actor.class),
                        eq("agent.create_from_template"),
                        eq("agent"),
                        eq(result.agent().id().toString()),
                        eq("succeeded"),
                        detail.capture());
        assertThat(detail.getValue()).containsEntry("template", "support").containsEntry("key", "support-2");
        verify(audit).record(any(), any(), any(), any(), any(), any(), anyMap());
    }
}
