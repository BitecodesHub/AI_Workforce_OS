// @find: tests for agent controller, agents api, create agent, update agent, pause, resume, retire, restore, /api/agents
// @what: Unit and integration tests (17 cases) for agent controller, for example: first sentence; full stop inside word; long sentence cut; list carries summary and tools.
package os.aiworkforce.orchestrator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    @Test
    @DisplayName("a temperature must be between 0 and 2, and may be left out")
    void temperatureBounds() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(withTemperature("-0.1"))).isNotEmpty();
            assertThat(validator.validate(withTemperature("2.1"))).isNotEmpty();
            assertThat(validator.validate(withTemperature("0"))).isEmpty();
            assertThat(validator.validate(withTemperature("2"))).isEmpty();
            assertThat(validator.validate(withTemperature(null))).isEmpty();
        }
    }

    private static AgentController.UpdateConfigurationRequest withTemperature(String temperature) {
        return new AgentController.UpdateConfigurationRequest(
                "You answer questions.", "", temperature == null ? null : new java.math.BigDecimal(temperature), null, null);
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

    private AgentController controllerFor(Agents agents) {
        RequestContext.setActor(Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of(), 0L));
        return new AgentController(
                agents,
                mock(AgentVersions.class),
                mock(AgentRunner.class),
                mock(ToolGrants.class),
                mock(GeneralEmployee.class));
    }

    @Test
    @DisplayName("retiring an agent archives it and pauses its enabled schedules, keeping everything else")
    void retireArchivesAndPausesSchedules() {
        Agents agents = mock(Agents.class);
        AgentController controller = controllerFor(agents);
        os.aiworkforce.orchestrator.schedule.ScheduleService scheduleService =
                mock(os.aiworkforce.orchestrator.schedule.ScheduleService.class);
        os.aiworkforce.orchestrator.schedule.Schedules schedules =
                mock(os.aiworkforce.orchestrator.schedule.Schedules.class);
        controller.setSchedules(scheduleService, schedules);
        Agent agent = activeAgent();
        when(agents.findByIdAndOrgId(agent.getId(), ORG)).thenReturn(Optional.of(agent));
        var mine = new os.aiworkforce.orchestrator.schedule.Schedule();
        mine.setId(UUID.randomUUID());
        mine.setAgentId(agent.getId());
        mine.setEnabled(true);
        var other = new os.aiworkforce.orchestrator.schedule.Schedule();
        other.setId(UUID.randomUUID());
        other.setAgentId(UUID.randomUUID());
        other.setEnabled(true);
        when(schedules.findByOrgIdOrderByNameAsc(ORG)).thenReturn(List.of(mine, other));

        AgentController.AgentView view = controller.retire(agent.getId());

        assertThat(view.status()).isEqualTo("retired");
        verify(scheduleService).pauseInternal(ORG, mine.getId(), AgentController.RETIRED_SCHEDULE_REASON);
        verify(scheduleService, org.mockito.Mockito.never()).pauseInternal(eq(ORG), eq(other.getId()), any());
    }

    @Test
    @DisplayName("the General Employee cannot be retired")
    void fallbackCannotBeRetired() {
        Agents agents = mock(Agents.class);
        AgentController controller = controllerFor(agents);
        Agent agent = activeAgent();
        agent.setFallback(true);
        when(agents.findByIdAndOrgId(agent.getId(), ORG)).thenReturn(Optional.of(agent));

        assertThatThrownBy(() -> controller.retire(agent.getId()))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(agent.getStatus()).isEqualTo("active");
    }

    @Test
    @DisplayName("restoring a retired agent brings it back paused; restoring any other is refused")
    void restoreComesBackPaused() {
        Agents agents = mock(Agents.class);
        AgentController controller = controllerFor(agents);
        Agent agent = activeAgent();
        agent.setStatus("retired");
        when(agents.findByIdAndOrgId(agent.getId(), ORG)).thenReturn(Optional.of(agent));

        assertThat(controller.restore(agent.getId()).status()).isEqualTo("paused");
        assertThatThrownBy(() -> controller.restore(agent.getId()))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
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

    @Test
    @DisplayName("a description is kept to one line, and a blank one is cleared")
    void descriptionCleaned() {
        assertThat(AgentController.cleanDescription("  Screens applications\n and   books interviews. "))
                .isEqualTo("Screens applications and books interviews.");
        assertThat(AgentController.cleanDescription("   ")).isNull();
        assertThat(AgentController.cleanDescription(null)).isNull();
    }

    @Test
    @DisplayName("setting a description saves it on the agent and the view carries it; blank clears it")
    void setDescription() {
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

        AgentController.AgentView view = controller.setDescription(
                agent.getId(), new AgentController.DescriptionRequest("Sorts the support queue."));

        assertThat(view.description()).isEqualTo("Sorts the support queue.");
        assertThat(agent.getDescription()).isEqualTo("Sorts the support queue.");
        verify(agents).save(agent);

        assertThat(controller
                        .setDescription(agent.getId(), new AgentController.DescriptionRequest(" "))
                        .description())
                .isNull();
    }

    @Test
    @DisplayName("a description longer than 200 characters is refused")
    void descriptionLimit() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(new AgentController.DescriptionRequest("a".repeat(201))))
                    .isNotEmpty();
            assertThat(validator.validate(new AgentController.DescriptionRequest("a".repeat(200))))
                    .isEmpty();
        }
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
