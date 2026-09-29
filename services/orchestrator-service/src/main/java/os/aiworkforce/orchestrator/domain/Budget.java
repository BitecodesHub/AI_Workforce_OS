package os.aiworkforce.orchestrator.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import os.aiworkforce.platform.web.persistence.BaseEntity;

/**
 * A workspace's spending limits.
 *
 * <p>The period resets by comparison rather than by a scheduled job: a monthly cap that depends
 * on a cron having fired is a cap that silently stops applying when the job fails.
 */
@Entity
@Table(name = "budgets")
public class Budget extends BaseEntity {

    @Column(name = "monthly_cap", precision = 14, scale = 4)
    private BigDecimal monthlyCap;

    @Column(name = "per_run_cap", precision = 14, scale = 4)
    private BigDecimal perRunCap;

    @Column(name = "per_agent_daily_cap", precision = 14, scale = 4)
    private BigDecimal perAgentDailyCap;

    @Column(name = "spent_this_month", nullable = false, precision = 14, scale = 8)
    private BigDecimal spentThisMonth = BigDecimal.ZERO;

    @Column(name = "period_started_at", nullable = false)
    private Instant periodStartedAt = Instant.now();

    @Column(name = "on_exhausted", nullable = false)
    private String onExhausted = "stop";

    public BigDecimal getMonthlyCap() {
        return monthlyCap;
    }

    public void setMonthlyCap(BigDecimal monthlyCap) {
        this.monthlyCap = monthlyCap;
    }

    public BigDecimal getPerRunCap() {
        return perRunCap;
    }

    public void setPerRunCap(BigDecimal perRunCap) {
        this.perRunCap = perRunCap;
    }

    public BigDecimal getPerAgentDailyCap() {
        return perAgentDailyCap;
    }

    public void setPerAgentDailyCap(BigDecimal perAgentDailyCap) {
        this.perAgentDailyCap = perAgentDailyCap;
    }

    public BigDecimal getSpentThisMonth() {
        return spentThisMonth;
    }

    public void setSpentThisMonth(BigDecimal spentThisMonth) {
        this.spentThisMonth = spentThisMonth;
    }

    public Instant getPeriodStartedAt() {
        return periodStartedAt;
    }

    public void setPeriodStartedAt(Instant periodStartedAt) {
        this.periodStartedAt = periodStartedAt;
    }

    public String getOnExhausted() {
        return onExhausted;
    }

    public void setOnExhausted(String onExhausted) {
        this.onExhausted = onExhausted;
    }

    /** Rolls the period forward if the stored one has elapsed, and returns what is left. */
    public BigDecimal remaining() {
        rollPeriodIfElapsed();
        if (monthlyCap == null) {
            return null;
        }
        return monthlyCap.subtract(spentThisMonth).max(BigDecimal.ZERO);
    }

    public void rollPeriodIfElapsed() {
        Instant currentPeriod = java.time.YearMonth.now(java.time.ZoneOffset.UTC)
                .atDay(1)
                .atStartOfDay(java.time.ZoneOffset.UTC)
                .toInstant();
        if (periodStartedAt.isBefore(currentPeriod)) {
            periodStartedAt = currentPeriod;
            spentThisMonth = BigDecimal.ZERO;
        }
    }

    public void spend(BigDecimal amount) {
        rollPeriodIfElapsed();
        spentThisMonth = spentThisMonth.add(amount);
    }
}
