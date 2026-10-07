package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.llm.budget.BudgetGuard;
import os.aiworkforce.llm.router.ModelRouter.CallContext;
import os.aiworkforce.orchestrator.domain.Budget;
import os.aiworkforce.orchestrator.repository.Budgets;
import os.aiworkforce.orchestrator.repository.Usage;

/**
 * The guard's three caps, and that spend is read from the usage table rather than kept as a counter.
 */
class JpaBudgetGuardTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID AGENT = UUID.fromString("00000000-0000-7000-8000-0000000000a1");
    private static final UUID RUN = UUID.fromString("00000000-0000-7000-8000-0000000000b1");

    /** Mid-October 2026, so the month started on the 1st and the day on the 15th. */
    private static final Instant NOW = Instant.parse("2026-10-15T10:30:00Z");

    private static final Instant MONTH_START = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant DAY_START = Instant.parse("2026-10-15T00:00:00Z");

    private Budgets budgets;
    private Usage usage;
    private MutableClock clock;
    private JpaBudgetGuard guard;
    private Budget budget;

    @BeforeEach
    void setUp() {
        budgets = mock(Budgets.class);
        usage = mock(Usage.class);
        clock = new MutableClock(NOW);
        guard = new JpaBudgetGuard(budgets, usage, clock);
        budget = new Budget();
        when(budgets.findForOrg(ORG)).thenReturn(Optional.of(budget));
        when(usage.spendSince(any(), any())).thenReturn(BigDecimal.ZERO);
        when(usage.costForRun(any(), any())).thenReturn(BigDecimal.ZERO);
        when(usage.spendSinceByAgent(any(), any(), any())).thenReturn(BigDecimal.ZERO);
    }

    private static CallContext context() {
        return new CallContext(ORG.toString(), AGENT.toString(), RUN.toString());
    }

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }

    @Test
    @DisplayName("a workspace with no budget row is never stopped")
    void noBudgetRow() {
        when(budgets.findForOrg(ORG)).thenReturn(Optional.empty());

        BudgetGuard.Decision decision = guard.check(context(), money("100"));

        assertThat(decision.allowed()).isTrue();
        verifyNoInteractions(usage);
    }

    @Test
    @DisplayName("a budget row with no cap set stops nothing and sums nothing")
    void noCapSet() {
        assertThat(guard.check(context(), money("100")).allowed()).isTrue();

        verifyNoInteractions(usage);
    }

    @Test
    @DisplayName("the month's spend is summed from the usage table, from the first of the month in UTC")
    void monthlySpendComesFromUsage() {
        budget.setMonthlyCap(money("5"));
        when(usage.spendSince(ORG, MONTH_START)).thenReturn(money("4.90"));

        BudgetGuard.Decision decision = guard.check(context(), money("0.05"));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isEqualByComparingTo("0.10");
        verify(usage).spendSince(ORG, MONTH_START);
    }

    @Test
    @DisplayName("a call that would pass the monthly cap is refused, with where to raise it")
    void monthlyCapRefuses() {
        budget.setMonthlyCap(money("5"));
        when(usage.spendSince(ORG, MONTH_START)).thenReturn(money("4.99"));

        BudgetGuard.Decision decision = guard.check(context(), money("0.05"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason())
                .isEqualTo("The workspace has reached its monthly model budget of $5.00."
                        + " Ask an administrator to raise the cap in Analytics, Budget.");
        assertThat(decision.remaining()).isEqualByComparingTo("0.01");
        assertThat(decision.degradeToSandbox()).isFalse();
    }

    @Test
    @DisplayName("a cap set part-way through the month counts what was already spent")
    void midMonthCapCountsEarlierSpend() {
        // The row is only just created, and the usage table already holds the month's spend.
        budget.setMonthlyCap(money("5"));
        when(usage.spendSince(ORG, MONTH_START)).thenReturn(money("6"));

        assertThat(guard.check(context(), money("0.0001")).allowed()).isFalse();
    }

    @Test
    @DisplayName("a call expected to cost nothing is let through at the cap")
    void freeCallAtTheCap() {
        budget.setMonthlyCap(money("5"));
        when(usage.spendSince(ORG, MONTH_START)).thenReturn(money("5"));

        assertThat(guard.check(context(), BigDecimal.ZERO).allowed()).isTrue();
    }

    @Test
    @DisplayName("a cap of zero is a cap")
    void zeroCapRefusesEverythingThatCosts() {
        budget.setMonthlyCap(BigDecimal.ZERO);

        assertThat(guard.check(context(), money("0.0001")).allowed()).isFalse();
    }

    @Test
    @DisplayName("the month's sum is reused for five seconds and read again after")
    void monthSpendIsCachedBriefly() {
        budget.setMonthlyCap(money("5"));

        guard.check(context(), money("0.01"));
        clock.advance(Duration.ofSeconds(4));
        guard.check(context(), money("0.01"));
        verify(usage, times(1)).spendSince(ORG, MONTH_START);

        clock.advance(Duration.ofSeconds(2));
        guard.check(context(), money("0.01"));
        verify(usage, times(2)).spendSince(ORG, MONTH_START);
    }

    @Test
    @DisplayName("a paid call is added to the cached sum, so the next check sees it")
    void recordKeepsTheCacheInStep() {
        budget.setMonthlyCap(money("1.60"));
        when(usage.spendSince(ORG, MONTH_START)).thenReturn(money("1.00"));
        assertThat(guard.check(context(), money("0.20")).allowed()).isTrue();

        guard.record(context(), money("0.50"));

        BudgetGuard.Decision decision = guard.check(context(), money("0.20"));
        assertThat(decision.allowed()).isFalse();
        verify(usage, times(1)).spendSince(ORG, MONTH_START);
    }

    @Test
    @DisplayName("recording a cost writes nothing: the usage row is the record")
    void recordWritesNothing() {
        budget.setMonthlyCap(money("5"));

        guard.record(context(), money("0.50"));

        verify(budgets, never()).save(any());
    }

    @Test
    @DisplayName("what an administrator is shown is never taken from the cache")
    void freshMonthToDateIgnoresTheCache() {
        budget.setMonthlyCap(money("5"));
        when(usage.spendSince(ORG, MONTH_START)).thenReturn(money("1"));
        guard.check(context(), money("0.01"));
        when(usage.spendSince(ORG, MONTH_START)).thenReturn(money("2"));

        assertThat(guard.freshMonthToDate(ORG)).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("a run that would pass its own cap is refused, naming the run's limit")
    void perRunCapRefuses() {
        budget.setPerRunCap(money("0.10"));
        when(usage.costForRun(ORG, RUN)).thenReturn(money("0.09"));

        BudgetGuard.Decision decision = guard.check(context(), money("0.02"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason())
                .startsWith("This run reached its spending limit of $0.10.")
                .endsWith("Ask an administrator to raise the cap in Analytics, Budget.");
    }

    @Test
    @DisplayName("a run within its cap is allowed")
    void perRunCapAllows() {
        budget.setPerRunCap(money("0.10"));
        when(usage.costForRun(ORG, RUN)).thenReturn(money("0.05"));

        assertThat(guard.check(context(), money("0.02")).allowed()).isTrue();
    }

    @Test
    @DisplayName("a call with no run is not held to the per-run cap")
    void perRunCapSkippedWithoutARun() {
        budget.setPerRunCap(money("0.10"));

        BudgetGuard.Decision decision =
                guard.check(new CallContext(ORG.toString(), AGENT.toString(), null), money("5"));

        assertThat(decision.allowed()).isTrue();
        verify(usage, never()).costForRun(any(), any());
    }

    @Test
    @DisplayName("an agent that would pass its daily cap is refused, counting from UTC midnight")
    void perAgentDailyCapRefuses() {
        budget.setPerAgentDailyCap(money("1"));
        when(usage.spendSinceByAgent(ORG, AGENT, DAY_START)).thenReturn(money("0.99"));

        BudgetGuard.Decision decision = guard.check(context(), money("0.02"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason())
                .startsWith("This agent reached its daily spending limit of $1.00.")
                .contains("raise the cap in Analytics, Budget.");
        verify(usage).spendSinceByAgent(ORG, AGENT, DAY_START);
    }

    @Test
    @DisplayName("a call that is for no agent is not held to the per-agent cap")
    void perAgentCapSkippedWithoutAnAgent() {
        budget.setPerAgentDailyCap(money("1"));

        BudgetGuard.Decision decision = guard.check(new CallContext(ORG.toString(), null, null), money("5"));

        assertThat(decision.allowed()).isTrue();
        verify(usage, never()).spendSinceByAgent(any(), any(), any());
    }

    @Test
    @DisplayName("the run's limit is reported before the agent's and the month's")
    void mostSpecificCapIsNamed() {
        budget.setPerRunCap(money("0.10"));
        budget.setPerAgentDailyCap(money("1"));
        budget.setMonthlyCap(money("5"));
        when(usage.costForRun(ORG, RUN)).thenReturn(money("0.10"));
        when(usage.spendSinceByAgent(any(), any(), any())).thenReturn(money("2"));
        when(usage.spendSince(any(), any())).thenReturn(money("9"));

        assertThat(guard.check(context(), money("0.01")).reason()).startsWith("This run reached");
    }

    @Test
    @DisplayName("a workspace set to use the offline model at its cap says so in every refusal")
    void sandboxChoiceTravelsWithTheRefusal() {
        budget.setOnExhausted(Budget.ON_EXHAUSTED_SANDBOX);
        budget.setMonthlyCap(money("1"));
        budget.setPerRunCap(money("0.10"));
        when(usage.spendSince(any(), any())).thenReturn(money("1"));
        when(usage.costForRun(ORG, RUN)).thenReturn(money("0.10"));

        assertThat(guard.check(context(), money("0.01")).degradeToSandbox()).isTrue();
        assertThat(guard.check(new CallContext(ORG.toString(), AGENT.toString(), null), money("0.01"))
                        .degradeToSandbox())
                .isTrue();
    }

    @Test
    @DisplayName("the month starts on the first in UTC, whatever the clock's zone")
    void monthAndDayStarts() {
        Instant lateOnTheLastDay = Instant.parse("2026-09-30T23:59:59Z");
        assertThat(JpaBudgetGuard.monthStart(lateOnTheLastDay)).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(JpaBudgetGuard.monthStart(Instant.parse("2026-10-01T00:00:00Z")))
                .isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(JpaBudgetGuard.dayStart(NOW)).isEqualTo(DAY_START);
    }

    @Test
    @DisplayName("dollar amounts read as money, with at least two decimals and no more than they need")
    void moneyFormat() {
        assertThat(JpaBudgetGuard.money(money("5"))).isEqualTo("$5.00");
        assertThat(JpaBudgetGuard.money(money("5.0000"))).isEqualTo("$5.00");
        assertThat(JpaBudgetGuard.money(money("12.5"))).isEqualTo("$12.50");
        assertThat(JpaBudgetGuard.money(money("0.0001"))).isEqualTo("$0.0001");
        assertThat(JpaBudgetGuard.money(money("100"))).isEqualTo("$100.00");
        assertThat(JpaBudgetGuard.money(money("1000000"))).isEqualTo("$1000000.00");
    }

    /** A clock a test moves by hand. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
