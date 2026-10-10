// @find: tests for insights service, board, free runs are not unpriced, spans, window bounds, window across month end, success rate needs five finished runs, cancelled and abandoned, unpriced is not free, partly priced, InsightsServiceTest, InsightsService
// @what: Tests for InsightsService in the orchestrator board package (21 test methods).
// @flow: Exercises InsightsService
package os.aiworkforce.orchestrator.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import os.aiworkforce.orchestrator.board.InsightsService.AgentRow;
import os.aiworkforce.orchestrator.board.InsightsService.Delta;
import os.aiworkforce.orchestrator.board.InsightsService.RunGroup;
import os.aiworkforce.orchestrator.board.InsightsService.Span;
import os.aiworkforce.orchestrator.board.InsightsService.ValueFigures;
import os.aiworkforce.orchestrator.board.InsightsService.ValueInputs;
import os.aiworkforce.orchestrator.board.InsightsService.Window;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.runtimeconfig.RuntimeConfigStore;

/**
 * The rules the insight figures keep, with no database: when a rate exists, when the estimate of
 * value is absent, what a change is measured against. The SQL itself is read against the real
 * schema in {@link InsightsServiceQueriesTest}.
 */
class InsightsServiceTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final UUID OTHER_AGENT = UUID.randomUUID();

    private static RunGroup runs(String status, long n, String cost, long priced, long unpriced, long sandbox) {
        return new RunGroup(AGENT, status, n, new BigDecimal(cost), priced, unpriced, sandbox);
    }

    private static AgentRow row(List<RunGroup> groups, long[] ratings, Integer minutes, long tasks) {
        return InsightsService.agentRow(AGENT, "Alpha", "operations", "active", groups, 0, null, ratings, minutes, tasks);
    }

    // @find: test free runs are not unpriced, insights service
    @Test
    @DisplayName("runs on models the catalogue marks free are counted as free, not unpriced, and cost a known zero")
    void freeRunsAreNotUnpriced() {
        AgentRow row = row(
                List.of(new RunGroup(AGENT, "completed", 6, BigDecimal.ZERO, 0, 0, 0, 6)), null, null, 0);

        assertThat(row.freeRuns()).isEqualTo(6);
        assertThat(row.unpricedRuns()).isZero();
        assertThat(row.totalCost()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ---- Windows ---------------------------------------------------------------------------

    // @find: test spans, insights service
    @Test
    @DisplayName("a window is 7, 30 or 90 days, defaulting to 30, and anything else is refused")
    void spans() {
        assertThat(Span.parse("7d")).isEqualTo(Span.D7);
        assertThat(Span.parse(" 90D ")).isEqualTo(Span.D90);
        assertThat(Span.parse(null)).isEqualTo(Span.D30);
        assertThat(Span.parse("  ")).isEqualTo(Span.D30);

        ApiException refused = catchThrowableOfType(() -> Span.parse("1y"), ApiException.class);

        assertThat(refused.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    // @find: test window bounds, insights service
    @Test
    @DisplayName("a window starts at midnight UTC and the previous one is as long and ends at the same time of day")
    void windowBounds() {
        Instant now = Instant.parse("2026-10-15T12:00:00Z");

        Window week = Window.of(Span.D7, now);

        assertThat(week.from()).isEqualTo(Instant.parse("2026-10-09T00:00:00Z"));
        assertThat(week.to()).isEqualTo(now);
        assertThat(week.previousFrom()).isEqualTo(Instant.parse("2026-10-02T00:00:00Z"));
        assertThat(week.previousTo()).isEqualTo(Instant.parse("2026-10-08T12:00:00Z"));
        assertThat(week.dayKeys()).hasSize(7).startsWith("2026-10-09").endsWith("2026-10-15");
    }

    // @find: test window across month end, insights service
    @Test
    @DisplayName("a window across a month end still lists every day once")
    void windowAcrossMonthEnd() {
        Window week = Window.of(Span.D7, Instant.parse("2026-11-02T06:00:00Z"));

        assertThat(week.dayKeys())
                .containsExactly(
                        "2026-10-27", "2026-10-28", "2026-10-29", "2026-10-30", "2026-10-31", "2026-11-01", "2026-11-02");
    }

    // ---- Rates and small samples -----------------------------------------------------------

    // @find: test success rate needs five finished runs, insights service
    @Test
    @DisplayName("four finished runs give no success rate, and five give one over finished runs only")
    void successRateNeedsFiveFinishedRuns() {
        AgentRow few = row(List.of(runs("completed", 3, "0.30", 3, 0, 0), runs("failed", 1, "0.10", 1, 0, 0)), null, null, 0);
        assertThat(few.finishedRuns()).isEqualTo(4);
        assertThat(few.enoughRuns()).isFalse();
        assertThat(few.successRate()).isNull();

        // A run still going is not a failure: 3 of 5 finished, with two more not yet done.
        AgentRow enough = row(
                List.of(
                        runs("completed", 3, "0.30", 3, 0, 0),
                        runs("failed", 2, "0.20", 2, 0, 0),
                        runs("running", 2, "0", 0, 0, 0),
                        runs("waiting_approval", 1, "0", 0, 0, 0)),
                null,
                null,
                0);
        assertThat(enough.runs()).isEqualTo(8);
        assertThat(enough.finishedRuns()).isEqualTo(5);
        assertThat(enough.enoughRuns()).isTrue();
        assertThat(enough.successRate()).isEqualByComparingTo("0.6");
    }

    // @find: test cancelled and abandoned, insights service
    @Test
    @DisplayName("cancelled work is neither success nor failure but still finished, and abandoned runs count as failed")
    void cancelledAndAbandoned() {
        AgentRow agent = row(
                List.of(
                        runs("completed", 2, "0", 0, 0, 0),
                        runs("cancelled", 2, "0", 0, 0, 0),
                        runs("abandoned", 1, "0", 0, 0, 0)),
                null,
                null,
                0);

        assertThat(agent.completed()).isEqualTo(2);
        assertThat(agent.cancelled()).isEqualTo(2);
        assertThat(agent.failed()).isEqualTo(1);
        assertThat(agent.finishedRuns()).isEqualTo(5);
        assertThat(agent.successRate()).isEqualByComparingTo("0.4");
    }

    // ---- Unpriced work ---------------------------------------------------------------------

    // @find: test unpriced is not free, insights service
    @Test
    @DisplayName("an agent whose every run was unpriced has no total cost, not a total of zero")
    void unpricedIsNotFree() {
        AgentRow agent = row(List.of(runs("completed", 4, "0", 0, 4, 0)), null, null, 0);

        assertThat(agent.totalCost()).isNull();
        assertThat(agent.avgCostPerCompleted()).isNull();
        assertThat(agent.unpricedRuns()).isEqualTo(4);
    }

    // @find: test partly priced, insights service
    @Test
    @DisplayName("an agent with some priced runs shows what they cost, and how many were unpriced")
    void partlyPriced() {
        AgentRow agent = row(List.of(runs("completed", 3, "0.30", 2, 1, 0)), null, null, 0);

        assertThat(agent.totalCost()).isEqualByComparingTo("0.30");
        // Over the completed runs that have a price, not all three.
        assertThat(agent.avgCostPerCompleted()).isEqualByComparingTo("0.15");
        assertThat(agent.unpricedRuns()).isEqualTo(1);
    }

    // @find: test no runs is zero, insights service
    @Test
    @DisplayName("an agent with no runs at all has spent nothing, which is true and is not unpriced")
    void noRunsIsZero() {
        AgentRow agent = row(List.of(), null, null, 0);

        assertThat(agent.totalCost()).isEqualByComparingTo("0");
        assertThat(agent.successRate()).isNull();
        assertThat(agent.runs()).isZero();
    }

    // @find: test sandbox runs, insights service
    @Test
    @DisplayName("an agent the offline sandbox alone answered counts its runs as sandbox and costs nothing")
    void sandboxRuns() {
        AgentRow agent = row(List.of(runs("completed", 5, "0", 0, 0, 5)), null, null, 0);

        assertThat(agent.sandboxRuns()).isEqualTo(5);
        assertThat(agent.unpricedRuns()).isZero();
        assertThat(agent.totalCost()).isEqualByComparingTo("0");
    }

    // ---- Satisfaction ----------------------------------------------------------------------

    // @find: test satisfaction, insights service
    @Test
    @DisplayName("satisfaction is thumbs up over all ratings, and absent with none")
    void satisfaction() {
        AgentRow rated = row(List.of(), new long[] {3, 1}, null, 0);
        assertThat(rated.ratings()).isEqualTo(4);
        assertThat(rated.thumbsDown()).isEqualTo(1);
        assertThat(rated.satisfactionRate()).isEqualByComparingTo("0.75");

        AgentRow none = row(List.of(), null, null, 0);
        assertThat(none.ratings()).isZero();
        assertThat(none.satisfactionRate()).isNull();
    }

    // ---- Estimated value -------------------------------------------------------------------

    // @find: test value omitted without inputs, insights service
    @Test
    @DisplayName("no estimate of value is made until some agent has minutes per task")
    void valueOmittedWithoutInputs() {
        Map<UUID, Long> tasks = Map.of(AGENT, 10L);
        Map<UUID, BigDecimal> cost = Map.of(AGENT, new BigDecimal("1.00"));

        assertThat(InsightsService.valueFigures(ValueInputs.NONE, tasks, cost)).isNull();
        assertThat(InsightsService.valueFigures(null, tasks, cost)).isNull();
        // A rate alone is not an estimate either: it has nothing to multiply.
        assertThat(InsightsService.valueFigures(new ValueInputs(new BigDecimal("60"), Map.of()), tasks, cost)).isNull();
    }

    // @find: test value arithmetic, insights service
    @Test
    @DisplayName("hours are tasks times minutes over sixty, what a person would cost is hours times the rate, net is that less spend")
    void valueArithmetic() {
        ValueInputs inputs = new ValueInputs(new BigDecimal("60"), Map.of(AGENT, 45));

        ValueFigures value = InsightsService.valueFigures(
                inputs, Map.of(AGENT, 8L), Map.of(AGENT, new BigDecimal("2.50")));

        // Eight tasks of 45 minutes is six hours.
        assertThat(value.hoursReturned()).isEqualByComparingTo("6.00");
        assertThat(value.humanEquivalentCost()).isEqualByComparingTo("360.00");
        assertThat(value.netValue()).isEqualByComparingTo("357.50");
        assertThat(value.completedTasks()).isEqualTo(8);
        assertThat(value.agentsEstimated()).isEqualTo(1);
        assertThat(value.hourlyRate()).isEqualByComparingTo("60");
        assertThat(value.label()).isEqualTo("Estimate from your inputs (current values)");
    }

    // @find: test value without rate, insights service
    @Test
    @DisplayName("minutes without a rate give hours only, and the worth and the net stay absent")
    void valueWithoutRate() {
        ValueFigures value = InsightsService.valueFigures(
                new ValueInputs(null, Map.of(AGENT, 30)), Map.of(AGENT, 4L), Map.of(AGENT, BigDecimal.ONE));

        assertThat(value.hoursReturned()).isEqualByComparingTo("2.00");
        assertThat(value.humanEquivalentCost()).isNull();
        assertThat(value.netValue()).isNull();
    }

    // @find: test not estimated agents add nothing, insights service
    @Test
    @DisplayName("an agent with finished tasks and no estimate is counted as not estimated and adds nothing")
    void notEstimatedAgentsAddNothing() {
        ValueInputs inputs = new ValueInputs(new BigDecimal("60"), Map.of(AGENT, 60));

        ValueFigures value = InsightsService.valueFigures(
                inputs,
                Map.of(AGENT, 2L, OTHER_AGENT, 50L),
                Map.of(AGENT, new BigDecimal("1"), OTHER_AGENT, new BigDecimal("40")));

        assertThat(value.agentsEstimated()).isEqualTo(1);
        assertThat(value.agentsNotEstimated()).isEqualTo(1);
        assertThat(value.completedTasks()).isEqualTo(2);
        assertThat(value.hoursReturned()).isEqualByComparingTo("2.00");
        // The other agent's spend is not netted off against value it was not credited with.
        assertThat(value.netValue()).isEqualByComparingTo("119.00");
    }

    // @find: test agent hours, insights service
    @Test
    @DisplayName("an agent's hours are absent without its estimate and tasks times minutes with one")
    void agentHours() {
        assertThat(row(List.of(), null, null, 6).hoursReturned()).isNull();
        assertThat(row(List.of(), null, 20, 6).hoursReturned()).isEqualByComparingTo("2.00");
    }

    // @find: test inputs from settings, insights service
    @Test
    @DisplayName("inputs are read from the workspace's settings, and a stored value that is not valid is left out")
    void inputsFromSettings() {
        UUID org = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        RuntimeConfigStore store = mock(RuntimeConfigStore.class);
        when(store.readAll(org.toString()))
                .thenReturn(List.of(
                        stored("notification.something", "x"),
                        stored("value.hourlyRate", " 72.50 "),
                        stored("value.minutesPerTask." + AGENT, "30"),
                        stored("value.minutesPerTask." + second, "-5"),
                        stored("value.minutesPerTask.nonsense", "30"),
                        stored("value.minutesPerTask." + OTHER_AGENT, "lots")));
        InsightsService service = new InsightsService(mock(JdbcTemplate.class), store);

        ValueInputs inputs = service.valueInputs(org);

        assertThat(inputs.hourlyRate()).isEqualByComparingTo("72.50");
        assertThat(inputs.minutesPerTask()).containsOnlyKeys(AGENT).containsEntry(AGENT, 30);
    }

    // @find: test zero rate is no rate, insights service
    @Test
    @DisplayName("an hourly rate of zero or less is no rate")
    void zeroRateIsNoRate() {
        UUID org = UUID.randomUUID();
        RuntimeConfigStore store = mock(RuntimeConfigStore.class);
        when(store.readAll(any())).thenReturn(List.of(stored("value.hourlyRate", "0")));

        assertThat(new InsightsService(mock(JdbcTemplate.class), store).valueInputs(org).hourlyRate()).isNull();
    }

    private static RuntimeConfigStore.StoredValue stored(String key, String value) {
        return new RuntimeConfigStore.StoredValue(key, null, value, null, null);
    }

    // ---- Changes ---------------------------------------------------------------------------

    // @find: test delta, insights service
    @Test
    @DisplayName("a change is the difference and a percentage of the previous figure")
    void delta() {
        Delta delta = InsightsService.delta(new BigDecimal("12"), new BigDecimal("8"));

        assertThat(delta.change()).isEqualByComparingTo("4");
        assertThat(delta.changePercent()).isEqualByComparingTo("50.0");

        Delta fall = InsightsService.delta(new BigDecimal("6"), new BigDecimal("8"));
        assertThat(fall.change()).isEqualByComparingTo("-2");
        assertThat(fall.changePercent()).isEqualByComparingTo("-25.0");
    }

    // @find: test delta needs both sides, insights service
    @Test
    @DisplayName("a change from nothing has no percentage, and a figure missing on either side has no change")
    void deltaNeedsBothSides() {
        Delta fromZero = InsightsService.delta(new BigDecimal("3"), BigDecimal.ZERO);
        assertThat(fromZero.change()).isEqualByComparingTo("3");
        assertThat(fromZero.changePercent()).isNull();

        Delta noPrevious = InsightsService.delta(new BigDecimal("3"), null);
        assertThat(noPrevious.current()).isEqualByComparingTo("3");
        assertThat(noPrevious.change()).isNull();
        assertThat(noPrevious.changePercent()).isNull();

        Delta noCurrent = InsightsService.delta(null, new BigDecimal("3"));
        assertThat(noCurrent.change()).isNull();
    }

    // @find: test ratio, insights service
    @Test
    @DisplayName("a ratio is to four places and absent with no whole to be a part of")
    void ratio() {
        assertThat(InsightsService.ratio(2, 3)).isEqualByComparingTo("0.6667");
        assertThat(InsightsService.ratio(0, 5)).isEqualByComparingTo("0");
        assertThat(InsightsService.ratio(0, 0)).isNull();
    }
}
