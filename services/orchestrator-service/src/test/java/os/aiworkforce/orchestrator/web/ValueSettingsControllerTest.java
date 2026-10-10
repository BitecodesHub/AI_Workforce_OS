// @find: tests for value settings controller, value settings, hours saved, ROI, /api/orchestrator/value-settings
// @what: Unit and integration tests (10 cases) for value settings controller, for example: permissions; reads every agent; saves inputs; no rate clears it.
package os.aiworkforce.orchestrator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import os.aiworkforce.orchestrator.board.InsightsService;
import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;
import os.aiworkforce.platform.runtimeconfig.RuntimeConfigStore;

/**
 * The two inputs behind the estimated value of the work: who may read and change them, that a
 * change replaces the whole set, and that what is saved is what the insights read back.
 */
class ValueSettingsControllerTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID ALPHA = UUID.randomUUID();
    private static final UUID BETA = UUID.randomUUID();
    private static final UUID PERSON = UUID.randomUUID();

    private RuntimeConfigStore store;
    private InsightsService insights;
    private Agents agents;
    private AuditClient audit;
    private ValueSettingsController controller;

    @BeforeEach
    void setUp() {
        store = mock(RuntimeConfigStore.class);
        insights = mock(InsightsService.class);
        agents = mock(Agents.class);
        audit = mock(AuditClient.class);
        controller = new ValueSettingsController(store, insights, agents, audit);
        RequestContext.setActor(Actor.user(PERSON.toString(), ORG.toString(), "role", Set.of(), 0L));
        when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(agent(ALPHA, "Alpha"), agent(BETA, "Beta")));
        when(insights.valueInputs(ORG)).thenReturn(InsightsService.ValueInputs.NONE);
        when(store.readAll(ORG.toString())).thenReturn(List.of());
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    private static Agent agent(UUID id, String name) {
        Agent agent = new Agent();
        agent.setId(id);
        agent.setName(name);
        return agent;
    }

    private static ValueSettingsController.ValueSettingsRequest request(String rate, Map<UUID, Integer> minutes) {
        return new ValueSettingsController.ValueSettingsRequest(rate == null ? null : new BigDecimal(rate), minutes);
    }

    // ---- Who may ---------------------------------------------------------------------------

    @Test
    @DisplayName("anyone who can see Analytics may read the inputs, and only budget:manage may change them")
    void permissions() throws Exception {
        assertThat(ValueSettingsController.class.getMethod("get").getAnnotation(RequiresPermission.class).value())
                .containsExactly(Permission.Codes.ANALYTICS_READ);
        assertThat(ValueSettingsController.class
                        .getMethod("update", ValueSettingsController.ValueSettingsRequest.class)
                        .getAnnotation(RequiresPermission.class)
                        .value())
                .containsExactly(Permission.Codes.BUDGET_MANAGE);
    }

    // ---- Reading ---------------------------------------------------------------------------

    @Test
    @DisplayName("every agent is listed with its estimate or none, under the label every figure carries")
    void readsEveryAgent() {
        when(insights.valueInputs(ORG))
                .thenReturn(new InsightsService.ValueInputs(new BigDecimal("55"), Map.of(ALPHA, 20)));

        ValueSettingsController.ValueSettings settings = controller.get();

        assertThat(settings.label()).isEqualTo("Estimate from your inputs (current values)");
        assertThat(settings.hourlyRate()).isEqualByComparingTo("55");
        assertThat(settings.agents())
                .extracting(ValueSettingsController.AgentMinutes::name, ValueSettingsController.AgentMinutes::minutesPerTask)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Alpha", 20), org.assertj.core.groups.Tuple.tuple("Beta", null));
    }

    // ---- Writing ---------------------------------------------------------------------------

    @Test
    @DisplayName("saving writes the rate and each agent's minutes as the workspace's settings")
    void savesInputs() {
        controller.update(request("48.5", Map.of(ALPHA, 30, BETA, 90)));

        verify(store).write("value.hourlyRate", ORG.toString(), "48.5", PERSON.toString());
        verify(store).write("value.minutesPerTask." + ALPHA, ORG.toString(), "30", PERSON.toString());
        verify(store).write("value.minutesPerTask." + BETA, ORG.toString(), "90", PERSON.toString());
        verify(store, never()).clear(eq("value.hourlyRate"), any(), any());
    }

    @Test
    @DisplayName("no rate, or a rate of zero, clears the rate rather than storing nothing useful")
    void noRateClearsIt() {
        controller.update(request(null, Map.of()));
        controller.update(request("0", Map.of()));

        verify(store, org.mockito.Mockito.times(2)).clear("value.hourlyRate", ORG.toString(), PERSON.toString());
        verify(store, never()).write(eq("value.hourlyRate"), any(), any(), any());
    }

    @Test
    @DisplayName("saving replaces the set: an agent left out loses its estimate, and so does one that has been deleted")
    void replacesTheSet() {
        UUID deleted = UUID.randomUUID();
        when(store.readAll(ORG.toString()))
                .thenReturn(List.of(
                        stored("value.hourlyRate", "60"),
                        stored("value.minutesPerTask." + ALPHA, "30"),
                        stored("value.minutesPerTask." + BETA, "45"),
                        stored("value.minutesPerTask." + deleted, "10"),
                        stored("value.minutesPerTask.not-an-id", "10"),
                        stored("notifications.digest", "daily")));

        controller.update(request("60", Map.of(ALPHA, 30)));

        verify(store).clear("value.minutesPerTask." + BETA, ORG.toString(), PERSON.toString());
        verify(store).clear("value.minutesPerTask." + deleted, ORG.toString(), PERSON.toString());
        verify(store).clear("value.minutesPerTask.not-an-id", ORG.toString(), PERSON.toString());
        verify(store, never()).clear(eq("notifications.digest"), any(), any());
        verify(store, never()).clear(eq("value.minutesPerTask." + ALPHA), any(), any());
        verify(store).write("value.minutesPerTask." + ALPHA, ORG.toString(), "30", PERSON.toString());
    }

    @Test
    @DisplayName("an entry with no minutes is dropped, so the agent shows as not estimated")
    void blankMinutesDropped() {
        Map<UUID, Integer> minutes = new HashMap<>();
        minutes.put(ALPHA, null);
        minutes.put(BETA, 15);

        controller.update(request("60", minutes));

        verify(store, never()).write(eq("value.minutesPerTask." + ALPHA), any(), any(), any());
        verify(store).write("value.minutesPerTask." + BETA, ORG.toString(), "15", PERSON.toString());
    }

    // ---- Refusals --------------------------------------------------------------------------

    @Test
    @DisplayName("minutes must be between 1 and a working day, and nothing is written when one is not")
    void minutesBounds() {
        for (int bad : new int[] {0, -5, 2_401}) {
            ApiException refused = catchThrowableOfType(
                    () -> controller.update(request("60", Map.of(ALPHA, bad))), ApiException.class);
            assertThat(refused.code()).as(String.valueOf(bad)).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }
        controller.update(request("60", Map.of(ALPHA, 1, BETA, 2_400)));
        verify(store).write("value.minutesPerTask." + BETA, ORG.toString(), "2400", PERSON.toString());
    }

    @Test
    @DisplayName("a negative or enormous hourly cost is refused")
    void rateBounds() {
        for (String bad : new String[] {"-1", "10000.01"}) {
            ApiException refused =
                    catchThrowableOfType(() -> controller.update(request(bad, Map.of())), ApiException.class);
            assertThat(refused.code()).as(bad).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }
        verify(store, never()).write(any(), any(), any(), any());
    }

    @Test
    @DisplayName("an agent of another workspace is not found, and nothing is written")
    void foreignAgentRefused() {
        ApiException refused = catchThrowableOfType(
                () -> controller.update(request("60", Map.of(UUID.randomUUID(), 30))), ApiException.class);

        assertThat(refused.code()).isEqualTo(ErrorCode.NOT_FOUND);
        verify(store, never()).write(any(), any(), any(), any());
        verify(store, never()).clear(any(), any(), any());
    }

    // ---- Audit -----------------------------------------------------------------------------

    @Test
    @DisplayName("a change is audited with the inputs before and after, as text")
    void audited() {
        when(insights.valueInputs(ORG))
                .thenReturn(new InsightsService.ValueInputs(new BigDecimal("40"), Map.of(ALPHA, 10)));

        controller.update(request("60", Map.of(ALPHA, 30)));

        ArgumentCaptor<Map<String, Object>> detail = mapCaptor();
        verify(audit)
                .record(
                        eq(ORG),
                        any(Actor.class),
                        eq("value_settings.update"),
                        eq("workspace"),
                        eq(ORG.toString()),
                        eq("succeeded"),
                        detail.capture());
        assertThat(detail.getValue().get("old").toString()).contains("hourlyRate=40");
        assertThat(detail.getValue().get("new").toString()).contains("hourlyRate=60").contains(ALPHA + "=30");
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
    }

    private static RuntimeConfigStore.StoredValue stored(String key, String value) {
        return new RuntimeConfigStore.StoredValue(key, ORG.toString(), value, null, null);
    }
}
