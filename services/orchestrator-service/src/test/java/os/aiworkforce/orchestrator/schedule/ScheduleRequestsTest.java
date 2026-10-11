// @find: tests for schedule requests and the agent schedule tool, remind HR, confirmation sentence, schedule.create refused from a scheduled run, ScheduleRequestsTest
// @what: Tests the plain-words confirmation, the "remind X to" agent lookup, and the schedule.create tool's rules.
// @flow: Exercises ScheduleRequests and AgentScheduleTool with a mocked ScheduleService
package os.aiworkforce.orchestrator.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;

class ScheduleRequestsTest {

    private static final UUID ORG = UUID.randomUUID();

    private static Agent agent(String key, String name) {
        Agent agent = new Agent();
        agent.setId(UUID.randomUUID());
        agent.setKey(key);
        agent.setName(name);
        agent.setStatus("active");
        return agent;
    }

    private static Schedule schedule(String kind, String description, String instruction) {
        Schedule schedule = new Schedule();
        schedule.setId(UUID.randomUUID());
        schedule.setKind(kind);
        schedule.setCron("recurring".equals(kind) ? "0 0 9 * * MON" : null);
        schedule.setRunAt("once".equals(kind) ? Instant.parse("2026-10-11T04:00:00Z") : null);
        schedule.setNextRunAt(Instant.parse("2026-10-11T22:00:00Z"));
        schedule.setTimezone("Australia/Melbourne");
        schedule.setDescription(description);
        schedule.setInstruction(instruction);
        schedule.setName(ScheduleRequests.nameFor(instruction));
        return schedule;
    }

    @Test
    @DisplayName("a recurring schedule is confirmed with its timing, zone and first run")
    void recurringSentence() {
        String sentence = ScheduleRequests.sentence(
                schedule("recurring", "Every Monday at 9:00 am", "Summarise competitor news"), "Research");
        assertThat(sentence)
                .isEqualTo("Scheduled. Research will summarise competitor news every Monday at 9:00 am"
                        + " (Australia/Melbourne). The first run is Mon 12 Oct 2026 at 9:00 AM.");
    }

    @Test
    @DisplayName("a one-off is confirmed with its date, and an acronym keeps its capitals")
    void onceSentence() {
        String sentence = ScheduleRequests.sentence(
                schedule("once", "Once, on Sun 11 Oct 2026 at 3:00 pm", "HR onboarding emails"), "HR");
        assertThat(sentence)
                .isEqualTo("Scheduled. HR will HR onboarding emails once, on Sun 11 Oct 2026 at 3:00 pm"
                        + " (Australia/Melbourne).");
    }

    @Test
    @DisplayName("remind X to names the agent by name or key, ignoring case")
    void reminderTarget() {
        Agent hr = agent("hr", "HR");
        Agent research = agent("research", "Research");
        ScheduleRequests.Target target = ScheduleRequests.reminderTarget(
                        "remind hr to send onboarding emails", List.of(research, hr))
                .orElseThrow();
        assertThat(target.agent()).isSameAs(hr);
        assertThat(target.instruction()).isEqualTo("Send onboarding emails");
        assertThat(ScheduleRequests.reminderTarget("remind Alex to call back", List.of(hr))).isEmpty();
        assertThat(ScheduleRequests.reminderTarget("summarise the news", List.of(hr))).isEmpty();
    }

    @Test
    @DisplayName("a long instruction is named by its opening words")
    void names() {
        assertThat(ScheduleRequests.nameFor("summarise competitor news")).isEqualTo("Summarise competitor news");
        assertThat(ScheduleRequests.nameFor("word ".repeat(40)).length()).isLessThanOrEqualTo(60);
        assertThat(ScheduleRequests.nameFor("  ")).isEqualTo("Scheduled task");
    }

    // ---- The agent's schedule.create tool -------------------------------------------------------

    private final ScheduleService service = mock(ScheduleService.class);
    private final Agents agents = mock(Agents.class);
    private final AgentScheduleTool tool =
            new AgentScheduleTool(new ScheduleRequests(service), agents, new ObjectMapper());

    @Test
    @DisplayName("schedule.create saves the schedule as the person the run is for, and confirms it")
    void toolCreatesAsRequester() throws Exception {
        Agent self = agent("research", "Research");
        UUID requester = UUID.randomUUID();
        AtomicReference<String> actingAs = new AtomicReference<>();
        when(service.create(eq(ORG), any(), eq(self.getId()), eq("Summarise competitor news"), eq("every monday at 9am")))
                .thenAnswer(call -> {
                    actingAs.set(RequestContext.actor().orElseThrow().humanId());
                    return schedule("recurring", "Every Monday at 9:00 am", "Summarise competitor news");
                });

        AgentScheduleTool.Made made = tool.create(
                ORG, self, requester, false,
                "{\"when\":\"every monday at 9am\",\"instruction\":\"Summarise competitor news\"}");

        assertThat(actingAs.get()).isEqualTo(requester.toString());
        assertThat(made.sentence()).startsWith("Scheduled. Research will summarise competitor news every Monday");
        assertThat(AgentScheduleTool.modelText(made)).contains(made.schedule().getId().toString());
    }

    @Test
    @DisplayName("schedule.create gives the work to a colleague named in agent")
    void toolNamesAColleague() throws Exception {
        Agent self = agent("research", "Research");
        Agent hr = agent("hr", "HR");
        when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(hr, self));
        when(service.create(eq(ORG), any(), eq(hr.getId()), any(), any()))
                .thenReturn(schedule("once", "Once, on Sun 11 Oct 2026 at 3:00 pm", "Send onboarding emails"));

        AgentScheduleTool.Made made = tool.create(
                ORG, self, UUID.randomUUID(), false,
                "{\"when\":\"tomorrow at 3pm\",\"instruction\":\"Send onboarding emails\",\"agent\":\"@hr\"}");

        assertThat(made.agentName()).isEqualTo("HR");
    }

    @Test
    @DisplayName("a run a schedule started cannot create another schedule, and nothing is saved")
    void toolRefusesFromScheduledRun() {
        assertThatThrownBy(() -> tool.create(
                        ORG, agent("research", "Research"), UUID.randomUUID(), true,
                        "{\"when\":\"every day\",\"instruction\":\"x\"}"))
                .isInstanceOf(AgentScheduleTool.Refused.class)
                .hasMessageContaining("started by a schedule");
        verify(service, never()).create(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("an unreadable time comes back to the model with phrases that work")
    void toolExplainsUnreadableTime() {
        when(service.create(any(), any(), any(), any(), any()))
                .thenThrow(ApiException.validation("text", "That is not a schedule this platform understands yet.")
                        .with("examples", List.of("every day at 9am")));
        assertThatThrownBy(() -> tool.create(
                        ORG, agent("research", "Research"), UUID.randomUUID(), false,
                        "{\"when\":\"whenever\",\"instruction\":\"x\"}"))
                .isInstanceOf(AgentScheduleTool.Refused.class)
                .hasMessageContaining("every day at 9am");
    }

    @Test
    @DisplayName("missing arguments and a missing person are refused with a sentence the model can act on")
    void toolRefusesBadInput() {
        Agent self = agent("research", "Research");
        assertThatThrownBy(() -> tool.create(ORG, self, null, false, "{\"when\":\"every day\",\"instruction\":\"x\"}"))
                .hasMessageContaining("not for a person");
        assertThatThrownBy(() -> tool.create(ORG, self, UUID.randomUUID(), false, "{\"instruction\":\"x\"}"))
                .hasMessageContaining("Say when");
        assertThatThrownBy(() -> tool.create(ORG, self, UUID.randomUUID(), false, "not json"))
                .hasMessageContaining("valid JSON");
    }
}
