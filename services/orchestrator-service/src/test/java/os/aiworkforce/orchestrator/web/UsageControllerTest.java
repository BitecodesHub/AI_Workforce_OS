package os.aiworkforce.orchestrator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpServletResponse;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.Usage;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/** The spend report and its CSV: grouping, totals, the window, and that no cell can run as a formula. */
class UsageControllerTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID PRIYA = UUID.fromString("00000000-0000-7000-8000-0000000000a1");
    private static final UUID REMOVED = UUID.fromString("00000000-0000-7000-8000-0000000000a2");

    private static final Instant NOW = Instant.parse("2026-10-11T08:00:00Z");

    private Usage usage;
    private Agents agents;
    private UsageController controller;

    @BeforeEach
    void setUp() {
        usage = mock(Usage.class);
        agents = mock(Agents.class);
        controller = new UsageController(usage, agents, Clock.fixed(NOW, ZoneOffset.UTC));
        RequestContext.setActor(Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of(), 0L));
        when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(agent(PRIYA, "Priya")));
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

    /** A repository row: [key, prompt, cached, completion, cost, failedCost, attempts, failed, skipped]. */
    private static Object[] row(Object key, long prompt, long cached, long completion, String cost, String failedCost) {
        return new Object[] {
            key, prompt, cached, completion, new BigDecimal(cost), new BigDecimal(failedCost), 4L, 1L, 2L
        };
    }

    // ---- The report ------------------------------------------------------------------------

    @Test
    @DisplayName("groups come back with their figures, and the totals are their sum")
    void groupsAndTotals() {
        when(usage.reportByModel(eq(ORG), any(), any()))
                .thenReturn(List.of(
                        row("groq/llama", 100, 20, 50, "0.50", "0.10"),
                        row("openrouter/qwen", 40, 0, 10, "0.25", "0")));

        UsageController.UsageReport report = controller.report("2026-10-01", "2026-10-11", "model");

        assertThat(report.groupBy()).isEqualTo("model");
        assertThat(report.basis()).isEqualTo("estimated USD from catalogue prices");
        assertThat(report.groups()).hasSize(2);
        UsageController.Line first = report.groups().get(0);
        assertThat(first.key()).isEqualTo("groq/llama");
        assertThat(first.label()).isEqualTo("groq/llama");
        assertThat(first.totalTokens()).isEqualTo(150);
        assertThat(first.failedAttemptCost()).isEqualByComparingTo("0.10");
        assertThat(first.skipped()).isEqualTo(2);
        UsageController.Line totals = report.totals();
        assertThat(totals.promptTokens()).isEqualTo(140);
        assertThat(totals.cachedTokens()).isEqualTo(20);
        assertThat(totals.completionTokens()).isEqualTo(60);
        assertThat(totals.totalTokens()).isEqualTo(200);
        assertThat(totals.cost()).isEqualByComparingTo("0.75");
        assertThat(totals.failedAttemptCost()).isEqualByComparingTo("0.10");
        assertThat(totals.attempts()).isEqualTo(8);
        assertThat(totals.failedAttempts()).isEqualTo(2);
        assertThat(totals.skipped()).isEqualTo(4);
    }

    @Test
    @DisplayName("each grouping asks the repository for that grouping")
    void eachGrouping() {
        for (String grouping : List.of("day", "provider", "model", "agent", "outcome")) {
            assertThat(controller.report(null, null, grouping).groupBy()).isEqualTo(grouping);
        }
        verify(usage).reportByDay(eq(ORG), any(), any());
        verify(usage).reportByProvider(eq(ORG), any(), any());
        verify(usage).reportByModel(eq(ORG), any(), any());
        verify(usage).reportByAgent(eq(ORG), any(), any());
        verify(usage).reportByOutcome(eq(ORG), any(), any());
    }

    @Test
    @DisplayName("with nothing asked for, it groups by day over the month so far, to the end of today")
    void defaults() {
        UsageController.UsageReport report = controller.report(null, null, null);

        assertThat(report.groupBy()).isEqualTo("day");
        assertThat(report.from()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(report.to()).isEqualTo(Instant.parse("2026-10-12T00:00:00Z"));
        assertThat(report.totals().cost()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a date as the end covers that whole day, and the window is half open")
    void dateBoundsCoverWholeDays() {
        controller.report("2026-10-01", "2026-10-03", "day");

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(usage).reportByDay(eq(ORG), from.capture(), to.capture());
        assertThat(from.getValue()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(to.getValue()).isEqualTo(Instant.parse("2026-10-04T00:00:00Z"));
    }

    @Test
    @DisplayName("an instant with an offset is read as that instant")
    void instantBounds() {
        UsageController.UsageReport report =
                controller.report("2026-10-01T10:00:00+10:00", "2026-10-02T00:00:00Z", "provider");

        assertThat(report.from()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(report.to()).isEqualTo(Instant.parse("2026-10-02T00:00:00Z"));
    }

    @Test
    @DisplayName("agents are shown by name, spend with no agent is named, and a removed agent is said to be removed")
    void agentLabels() {
        when(usage.reportByAgent(eq(ORG), any(), any()))
                .thenReturn(List.of(
                        row(PRIYA, 1, 0, 1, "1", "0"),
                        row(null, 1, 0, 1, "0.5", "0"),
                        row(REMOVED, 1, 0, 1, "0.1", "0")));

        List<UsageController.Line> groups = controller.report(null, null, "agent").groups();

        assertThat(groups).extracting(UsageController.Line::label)
                .containsExactly("Priya", "No agent (chat and knowledge)", "Removed agent");
        assertThat(groups.get(1).key()).isNull();
    }

    @Test
    @DisplayName("outcomes are named in plain words")
    void outcomeLabels() {
        when(usage.reportByOutcome(eq(ORG), any(), any()))
                .thenReturn(List.of(
                        row("SUCCEEDED", 1, 0, 1, "1", "0"),
                        row("FAILED", 1, 0, 0, "0.2", "0.2"),
                        row("SKIPPED", 0, 0, 0, "0", "0")));

        assertThat(controller.report(null, null, "outcome").groups())
                .extracting(UsageController.Line::label)
                .containsExactly("Answered", "Failed", "Not tried");
    }

    @Test
    @DisplayName("a window that ends before it starts, is longer than a year, or is not a date is refused")
    void badWindows() {
        assertThat(code(() -> controller.report("2026-10-05", "2026-10-01", null)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(code(() -> controller.report("2025-01-01", "2026-10-01", null)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(code(() -> controller.report("last week", null, null))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(code(() -> controller.report(null, null, "colour"))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        // A whole year is the most that is allowed.
        controller.report("2025-10-12", "2026-10-11", "provider");
    }

    private static ErrorCode code(Runnable call) {
        return catchThrowableOfType(call::run, ApiException.class).code();
    }

    // ---- The file --------------------------------------------------------------------------

    @Test
    @DisplayName("a cell that would run as a formula in a spreadsheet is made text")
    void formulasAreNeutralised() {
        assertThat(UsageController.cell("=HYPERLINK(\"http://evil\")"))
                .isEqualTo("\"'=HYPERLINK(\"\"http://evil\"\")\"");
        assertThat(UsageController.cell("+1+1")).isEqualTo("'+1+1");
        assertThat(UsageController.cell("-2+3")).isEqualTo("'-2+3");
        assertThat(UsageController.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(UsageController.cell("\t=1")).isEqualTo("'\t=1");
        assertThat(UsageController.cell("plain")).isEqualTo("plain");
        assertThat(UsageController.cell("a=b")).isEqualTo("a=b");
        assertThat(UsageController.cell("")).isEmpty();
        assertThat(UsageController.cell(null)).isEmpty();
    }

    @Test
    @DisplayName("a cell with a comma, a quote or a line break is quoted, and quotes are doubled")
    void quoting() {
        assertThat(UsageController.cell("one, two")).isEqualTo("\"one, two\"");
        assertThat(UsageController.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(UsageController.cell("line\nbreak")).isEqualTo("\"line\nbreak\"");
    }

    @Test
    @DisplayName("the attempts file says its amounts are estimates, and an agent's name cannot inject a formula")
    void attemptsFile() throws Exception {
        UUID run = UUID.randomUUID();
        when(agents.findByOrgIdOrderByName(ORG))
                .thenReturn(List.of(agent(PRIYA, "=cmd|' /C calc'!A0"), agent(REMOVED, "Plain, with comma")));
        when(usage.exportPage(eq(ORG), any(), any(), any(), any(), any()))
                .thenReturn(List.<Object[]>of(attempt(
                        Instant.parse("2026-10-02T01:02:03Z"), PRIYA, run, "groq", "llama", "FAILED", "RATE_LIMITED", null)));

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.csv("2026-10-01", "2026-10-03", null, response);

        assertThat(response.getContentType()).startsWith("text/csv");
        assertThat(response.getHeader("Content-Disposition"))
                .contains("attachment")
                .contains("usage-attempts_2026-10-01_to_2026-10-03.csv");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        String[] lines = response.getContentAsString().split("\r\n");
        assertThat(lines[0]).contains("estimated USD from catalogue prices").startsWith("occurred_at_utc,");
        assertThat(lines).hasSize(2);
        assertThat(lines[1])
                .startsWith("2026-10-02T01:02:03Z," + PRIYA + ",'=cmd|' /C calc'!A0,")
                .contains(",groq,llama,FAILED,RATE_LIMITED,,10,0,0,0.00120000,1500");
    }

    @Test
    @DisplayName("a long window is read a page at a time, each page picking up after the last row of the one before")
    void attemptsArePaged() throws Exception {
        List<Object[]> first = new ArrayList<>();
        for (int i = 0; i < UsageController.EXPORT_PAGE; i++) {
            Instant at = Instant.parse("2026-10-02T00:00:00Z").plusSeconds(i);
            first.add(attempt(at, null, null, "groq", "llama", "SUCCEEDED", null, null));
        }
        Object[] last = first.get(first.size() - 1);
        List<Object[]> second = List.<Object[]>of(
                attempt(Instant.parse("2026-10-05T00:00:00Z"), null, null, "groq", "llama", "SUCCEEDED", null, null));
        when(usage.exportPage(eq(ORG), any(), any(), any(), any(), any())).thenReturn(first)
                .thenReturn(second);

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.csv("2026-10-01", "2026-10-31", null, response);

        assertThat(response.getContentAsString().split("\r\n")).hasSize(1 + UsageController.EXPORT_PAGE + 1);
        ArgumentCaptor<Instant> afterTime = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<UUID> afterId = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(usage, times(2))
                .exportPage(eq(ORG), any(), any(), afterTime.capture(), afterId.capture(), page.capture());
        assertThat(afterTime.getAllValues().get(1)).isEqualTo(last[0]);
        assertThat(afterId.getAllValues().get(1)).isEqualTo(last[13]);
        assertThat(page.getValue().getPageSize()).isEqualTo(UsageController.EXPORT_PAGE);
    }

    @Test
    @DisplayName("with a grouping the file has one row per group and a total, all labelled as estimates")
    void groupedFile() throws Exception {
        when(usage.reportByAgent(eq(ORG), any(), any()))
                .thenReturn(List.<Object[]>of(row(PRIYA, 100, 0, 50, "0.50", "0.10")));
        when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(agent(PRIYA, "-Priya")));

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.csv("2026-10-01", "2026-10-03", "agent", response);

        assertThat(response.getHeader("Content-Disposition")).contains("usage-by-agent_2026-10-01_to_2026-10-03.csv");
        String[] lines = response.getContentAsString().split("\r\n");
        assertThat(lines[0]).contains("estimated_cost_usd (estimated USD from catalogue prices)");
        assertThat(lines[1]).startsWith(PRIYA + ",'-Priya,4,1,2,100,0,50,150,0.50");
        assertThat(lines[2]).startsWith("total,Total,");
    }

    @Test
    @DisplayName("a bad window is refused before any of the file is written")
    void badWindowWritesNothing() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        catchThrowableOfType(() -> controller.csv("2026-10-05", "2026-10-01", null, response), ApiException.class);

        assertThat(response.getContentAsString()).isEmpty();
    }

    /** A repository row: see {@link Usage#exportPage}. */
    private static Object[] attempt(
            Instant at,
            UUID agent,
            UUID run,
            String provider,
            String model,
            String outcome,
            String failure,
            String skip) {
        return new Object[] {
            at,
            agent,
            run,
            provider,
            model,
            outcome,
            failure,
            skip,
            10,
            0,
            0,
            new BigDecimal("0.00120000"),
            1500L,
            UUID.randomUUID()
        };
    }
}
