// @find: tests for agent revisions controller, agent revisions, history, /api/agents/{agentId}/revisions
// @what: Unit and integration tests (9 cases) for agent revisions controller, for example: lists revisions with what changed; marks current and used by runs; carries the settings of the revision; equal values are not changes.
package os.aiworkforce.orchestrator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.AgentVersion;
import os.aiworkforce.orchestrator.repository.AgentVersions;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.web.AgentRevisionsController.RevisionView;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * An agent's revision history: what the console reads is a view, never the stored row, and it
 * says what changed from one revision to the one before and whether a run has used it.
 */
class AgentRevisionsControllerTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID OTHER_ORG = UUID.fromString("00000000-0000-7000-8000-000000000002");
    private static final UUID AGENT = UUID.fromString("00000000-0000-7000-8000-0000000000a1");

    private Agents agents;
    private AgentVersions versions;
    private AgentRevisionsController controller;
    private Agent agent;

    @BeforeEach
    void setUp() {
        agents = mock(Agents.class);
        versions = mock(AgentVersions.class);
        controller = new AgentRevisionsController(agents, versions);
        RequestContext.setActor(Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of(), 0L));

        agent = new Agent();
        ReflectionTestUtils.setField(agent, "id", AGENT);
        when(agents.findByIdAndOrgId(AGENT, ORG)).thenReturn(Optional.of(agent));
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    private AgentVersion version(int revision, String prompt, String goals, int maxSteps, boolean sealed) {
        AgentVersion version = new AgentVersion();
        version.setAgentId(AGENT);
        version.setOrgId(ORG);
        version.setRevision(revision);
        version.setSystemPrompt(prompt);
        version.setGoals(goals);
        version.setMaxSteps(maxSteps);
        version.setCreatedBy("user-" + revision);
        ReflectionTestUtils.setField(version, "createdAt", Instant.parse("2026-10-0" + revision + "T09:00:00Z"));
        if (sealed) {
            version.seal();
        }
        return version;
    }

    /** Newest first, as the repository returns them. */
    private void history(AgentVersion... newestFirst) {
        when(versions.findByAgentIdOrderByRevisionDesc(AGENT)).thenReturn(List.of(newestFirst));
    }

    @Test
    @DisplayName("lists the revisions newest first, each saying what changed from the one before it")
    void listsRevisionsWithWhatChanged() {
        AgentVersion first = version(1, "You answer questions.", "", 12, true);
        AgentVersion second = version(2, "You answer questions politely.", "", 12, true);
        AgentVersion third = version(3, "You answer questions politely.", "Be brief.", 20, false);
        agent.setCurrentVersionId(third.getId());
        history(third, second, first);

        List<RevisionView> views = controller.revisions(AGENT);

        assertThat(views).extracting(RevisionView::revision).containsExactly(3, 2, 1);
        assertThat(views.get(0).changedFields()).containsExactly("goals", "maxSteps");
        assertThat(views.get(1).changedFields()).containsExactly("systemPrompt");
        assertThat(views.get(2).changedFields()).isEmpty();
        assertThat(views.get(2).initial()).isTrue();
        assertThat(views.get(0).initial()).isFalse();
    }

    @Test
    @DisplayName("marks the revision the agent works from, and the ones a run has used")
    void marksCurrentAndUsedByRuns() {
        AgentVersion first = version(1, "A", "", 12, true);
        AgentVersion second = version(2, "B", "", 12, false);
        agent.setCurrentVersionId(second.getId());
        history(second, first);

        List<RevisionView> views = controller.revisions(AGENT);

        assertThat(views.get(0).current()).isTrue();
        assertThat(views.get(0).usedByRuns()).isFalse();
        assertThat(views.get(1).current()).isFalse();
        assertThat(views.get(1).usedByRuns()).isTrue();
    }

    @Test
    @DisplayName("carries what a restore needs: the prompt, goals, temperature, output limit and step limit")
    void carriesTheSettingsOfTheRevision() {
        AgentVersion only = version(1, "You answer questions.", "Be brief.", 7, false);
        only.setTemperature(new BigDecimal("0.30"));
        only.setMaxOutputTokens(900);
        history(only);

        RevisionView view = controller.revisions(AGENT).get(0);

        assertThat(view.systemPrompt()).isEqualTo("You answer questions.");
        assertThat(view.goals()).isEqualTo("Be brief.");
        assertThat(view.temperature()).isEqualByComparingTo("0.3");
        assertThat(view.maxOutputTokens()).isEqualTo(900);
        assertThat(view.maxSteps()).isEqualTo(7);
        assertThat(view.createdBy()).isEqualTo("user-1");
        assertThat(view.createdAt()).isEqualTo(Instant.parse("2026-10-01T09:00:00Z"));
    }

    @Test
    @DisplayName("the same temperature at another scale, and unset goals against empty ones, are not changes")
    void equalValuesAreNotChanges() {
        AgentVersion older = version(1, "A", null, 12, false);
        older.setTemperature(new BigDecimal("0.7"));
        AgentVersion newer = version(2, "A", "", 12, false);
        newer.setTemperature(new BigDecimal("0.70"));
        history(newer, older);

        assertThat(controller.revisions(AGENT).get(0).changedFields()).isEmpty();
    }

    @Test
    @DisplayName("names the temperature and the output limit when they change")
    void temperatureAndOutputLimit() {
        AgentVersion older = version(1, "A", "", 12, false);
        AgentVersion newer = version(2, "A", "", 12, false);
        newer.setTemperature(new BigDecimal("0.5"));
        newer.setMaxOutputTokens(500);
        history(newer, older);

        assertThat(controller.revisions(AGENT).get(0).changedFields()).containsExactly("temperature", "maxOutputTokens");
    }

    @Test
    @DisplayName("answers a view, never the stored row: nothing of the entity's own columns is in the JSON")
    void neverReturnsTheEntity() throws Exception {
        AgentVersion sealed = version(1, "A", "", 12, true);
        history(sealed);

        List<RevisionView> views = controller.revisions(AGENT);

        assertThat(views).allSatisfy(item -> assertThat(item).isInstanceOf(RevisionView.class));
        Method method = AgentRevisionsController.class.getMethod("revisions", UUID.class);
        assertThat(method.getReturnType()).isEqualTo(List.class);
        assertThat(method.getGenericReturnType().getTypeName()).contains("RevisionView").doesNotContain("AgentVersion");

        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        JsonNode body = json.valueToTree(views.get(0));
        assertThat(body.fieldNames()).toIterable()
                .containsExactlyInAnyOrder(
                        "id",
                        "revision",
                        "createdAt",
                        "createdBy",
                        "changedFields",
                        "initial",
                        "current",
                        "usedByRuns",
                        "systemPrompt",
                        "goals",
                        "temperature",
                        "maxOutputTokens",
                        "maxSteps");
        assertThat(body.has("orgId")).isFalse();
        assertThat(body.has("agentId")).isFalse();
        assertThat(body.has("sealedAt")).isFalse();
    }

    @Test
    @DisplayName("an agent of another workspace is a 404 and its history is never read")
    void anotherWorkspacesAgentIsNotFound() {
        RequestContext.setActor(Actor.user(UUID.randomUUID().toString(), OTHER_ORG.toString(), "role", Set.of(), 0L));
        when(agents.findByIdAndOrgId(AGENT, OTHER_ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.revisions(AGENT))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(404));
        verify(versions, never()).findByAgentIdOrderByRevisionDesc(AGENT);
    }

    @Test
    @DisplayName("returns the newest hundred revisions, comparing the oldest of them with the one beyond")
    void boundsTheList() {
        List<AgentVersion> newestFirst = new ArrayList<>();
        for (int revision = 130; revision >= 1; revision--) {
            AgentVersion version = new AgentVersion();
            version.setAgentId(AGENT);
            version.setOrgId(ORG);
            version.setRevision(revision);
            version.setSystemPrompt("Prompt " + revision);
            newestFirst.add(version);
        }
        when(versions.findByAgentIdOrderByRevisionDesc(AGENT)).thenReturn(newestFirst);

        List<RevisionView> views = controller.revisions(AGENT);

        assertThat(views).hasSize(AgentRevisionsController.MAX_REVISIONS);
        assertThat(views.get(0).revision()).isEqualTo(130);
        RevisionView oldest = views.get(views.size() - 1);
        assertThat(oldest.revision()).isEqualTo(31);
        assertThat(oldest.initial()).isFalse();
        assertThat(oldest.changedFields()).containsExactly("systemPrompt");
    }

    @Test
    @DisplayName("is readable with agent:read")
    void needsAgentRead() throws Exception {
        Method method = AgentRevisionsController.class.getMethod("revisions", UUID.class);

        assertThat(method.getAnnotation(RequiresPermission.class).value()).containsExactly(Permission.Codes.AGENT_READ);
    }
}
