package os.aiworkforce.orchestrator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.AgentToolGrant;
import os.aiworkforce.orchestrator.domain.AgentVersion;
import os.aiworkforce.orchestrator.repository.AgentVersions;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.ToolGrants;
import os.aiworkforce.orchestrator.service.AgentRunner;
import os.aiworkforce.orchestrator.service.GeneralEmployee;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

class AgentControllerTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("the summary is the first sentence of the prompt, with its line breaks collapsed")
    void firstSentence() {
        String prompt =
                """
                You handle people operations for a small care provider. Screen applications \
                against the stated requirements of the role.
                Leave email as a draft.""";

        assertThat(AgentController.summarise(prompt))
                .isEqualTo("You handle people operations for a small care provider.");
        assertThat(AgentController.summarise("You keep\n  tickets current.\nPost only when asked."))
                .isEqualTo("You keep tickets current.");
    }

    @Test
    @DisplayName("a full stop inside a word does not end the sentence")
    void fullStopInsideWord() {
        assertThat(AgentController.summarise("You maintain release v1.2 of the handbook. Nothing else."))
                .isEqualTo("You maintain release v1.2 of the handbook.");
    }

    @Test
    @DisplayName("a long sentence is cut at a word boundary within 160 characters and marked as cut")
    void longSentenceCut() {
        String sentence = "You compile market and competitor reports ".repeat(8).strip() + ".";

        String summary = AgentController.summarise(sentence);

        assertThat(summary)
                .hasSizeLessThanOrEqualTo(AgentController.SUMMARY_LIMIT)
                .endsWith("…");
        assertThat(summary.substring(0, summary.length() - 1)).doesNotEndWith(" ");
        assertThat(sentence).startsWith(summary.substring(0, summary.length() - 1));
    }

    @Test
    @DisplayName(
            "a prompt with no sentence ending is returned whole when short, and nothing is invented for an empty one")
    void edges() {
        assertThat(AgentController.summarise("You answer questions")).isEqualTo("You answer questions");
        assertThat(AgentController.summarise("   ")).isNull();
        assertThat(AgentController.summarise(null)).isNull();
    }

    @Test
    @DisplayName("the list carries each agent's summary and its distinct tool servers, sorted")
    void listCarriesSummaryAndTools() {
        Agents agents = mock(Agents.class);
        AgentVersions versions = mock(AgentVersions.class);
        ToolGrants grants = mock(ToolGrants.class);
        AgentController controller =
                new AgentController(agents, versions, mock(AgentRunner.class), grants, mock(GeneralEmployee.class));

        Agent agent = new Agent();
        agent.setId(UUID.randomUUID());
        agent.setOrgId(ORG);
        agent.setKey("support");
        agent.setName("Customer Support");
        agent.setCategory("support");
        AgentVersion version = new AgentVersion();
        version.setId(UUID.randomUUID());
        version.setRevision(3);
        version.setSystemPrompt("You triage support tickets. Escalate rather than guess.");
        agent.setCurrentVersionId(version.getId());
        when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(agent));
        when(versions.findById(version.getId())).thenReturn(Optional.of(version));
        when(grants.findByAgentIdAndEnabledTrue(any()))
                .thenReturn(List.of(grant("slack"), grant("gmail"), grant("slack")));
        RequestContext.setActor(Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of(), 0L));

        AgentController.AgentView view = controller.list().getFirst();

        assertThat(view.revision()).isEqualTo(3);
        assertThat(view.summary()).isEqualTo("You triage support tickets.");
        assertThat(view.tools()).containsExactly("gmail", "slack");
    }

    @Test
    @DisplayName("a step limit must be between 1 and 50, and may be left out")
    void stepLimitBounds() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(configuration(0))).isNotEmpty();
            assertThat(validator.validate(configuration(-3))).isNotEmpty();
            assertThat(validator.validate(configuration(51))).isNotEmpty();
            assertThat(validator.validate(configuration(1))).isEmpty();
            assertThat(validator.validate(configuration(50))).isEmpty();
            assertThat(validator.validate(configuration(null))).isEmpty();
        }
    }

    private static AgentController.UpdateConfigurationRequest configuration(Integer maxSteps) {
        return new AgentController.UpdateConfigurationRequest("You answer questions.", "", null, null, maxSteps);
    }

    private static AgentToolGrant grant(String server) {
        AgentToolGrant grant = new AgentToolGrant();
        grant.setServer(server);
        return grant;
    }

    @Test
    @DisplayName("pausing an agent sets its status so its queued tasks wait")
    void pauseSetsStatus() {
        Agents agents = mock(Agents.class);
        AgentController controller = new AgentController(
                agents,
                mock(AgentVersions.class),
                mock(AgentRunner.class),
                mock(ToolGrants.class),
                mock(GeneralEmployee.class));
        Agent agent = activeAgent();
        when(agents.findByIdAndOrgId(agent.getId(), ORG)).thenReturn(Optional.of(agent));
        RequestContext.setActor(Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of(), 0L));

        AgentController.AgentView view = controller.pause(agent.getId());

        assertThat(view.status()).isEqualTo("paused");
        assertThat(agent.getStatus()).isEqualTo("paused");
        verify(agents).save(agent);
    }

    @Test
    @DisplayName("resuming a paused agent sets it back to active")
    void resumeSetsStatus() {
        Agents agents = mock(Agents.class);
        AgentController controller = new AgentController(
                agents,
                mock(AgentVersions.class),
                mock(AgentRunner.class),
                mock(ToolGrants.class),
                mock(GeneralEmployee.class));
        Agent agent = activeAgent();
        agent.setStatus("paused");
        when(agents.findByIdAndOrgId(agent.getId(), ORG)).thenReturn(Optional.of(agent));
        RequestContext.setActor(Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of(), 0L));

        AgentController.AgentView view = controller.resume(agent.getId());

        assertThat(view.status()).isEqualTo("active");
        assertThat(agent.getStatus()).isEqualTo("active");
    }

    @Test
    @DisplayName("a retired agent cannot be paused or resumed")
    void retiredAgentRefused() {
        Agents agents = mock(Agents.class);
        AgentController controller = new AgentController(
                agents,
                mock(AgentVersions.class),
                mock(AgentRunner.class),
                mock(ToolGrants.class),
                mock(GeneralEmployee.class));
        Agent agent = activeAgent();
        agent.setStatus("retired");
        when(agents.findByIdAndOrgId(agent.getId(), ORG)).thenReturn(Optional.of(agent));
        RequestContext.setActor(Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of(), 0L));

        assertThatThrownBy(() -> controller.pause(agent.getId()))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(agent.getStatus()).isEqualTo("retired");
    }

    @Test
    @DisplayName("listing agents ensures the workspace has a General Employee before reading them")
    void listEnsuresGeneralEmployee() {
        Agents agents = mock(Agents.class);
        GeneralEmployee generalEmployee = mock(GeneralEmployee.class);
        AgentController controller = new AgentController(
                agents, mock(AgentVersions.class), mock(AgentRunner.class), mock(ToolGrants.class), generalEmployee);
        when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of());
        RequestContext.setActor(Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of(), 0L));

        controller.list();

        verify(generalEmployee).ensure(ORG);
    }

    @Test
    @DisplayName("the view marks the workspace's General Employee, found by its flag rather than its key")
    void viewMarksFallback() {
        Agents agents = mock(Agents.class);
        AgentController controller = new AgentController(
                agents,
                mock(AgentVersions.class),
                mock(AgentRunner.class),
                mock(ToolGrants.class),
                mock(GeneralEmployee.class));
        Agent general = activeAgent();
        general.setKey("general");
        general.setFallback(true);
        when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(general));
        RequestContext.setActor(Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of(), 0L));

        AgentController.AgentView view = controller.list().getFirst();

        assertThat(view.fallback()).isTrue();
    }

    private static Agent activeAgent() {
        Agent agent = new Agent();
        agent.setId(UUID.randomUUID());
        agent.setOrgId(ORG);
        agent.setKey("support");
        agent.setName("Customer Support");
        agent.setStatus("active");
        return agent;
    }
}
