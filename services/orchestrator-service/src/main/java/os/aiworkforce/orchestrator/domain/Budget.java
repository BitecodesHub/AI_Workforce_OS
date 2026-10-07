package os.aiworkforce.orchestrator.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import os.aiworkforce.platform.web.persistence.BaseEntity;

/**
 * A workspace's spending limits.
 *
 * <p>Only the limits are kept here. What has been spent is never stored on this row: it is summed
 * from {@code llm_usage}, which already holds every attempt with its cost, so there is one record
 * of spend rather than two that can disagree. The earlier running counter on this row lost
 * updates when two runs in one workspace paid at once, and started from zero when a cap was set
 * part-way through a month. The table still has {@code spent_this_month} and
 * {@code period_started_at}; nothing reads or writes them any more, and their defaults keep new
 * rows valid.
 *
 * <p>A cap of {@code null} means no cap of that kind. A cap of zero is a cap: it refuses every
 * call that is expected to cost anything.
 */
@Entity
@Table(name = "budgets")
public class Budget extends BaseEntity {

    public static final String ON_EXHAUSTED_STOP = "stop";
    public static final String ON_EXHAUSTED_SANDBOX = "sandbox";

    /** What this workspace may spend in a calendar month (UTC). */
    @Column(name = "monthly_cap", precision = 14, scale = 4)
    private BigDecimal monthlyCap;

    /** What one run may spend, across every step it takes. */
    @Column(name = "per_run_cap", precision = 14, scale = 4)
    private BigDecimal perRunCap;

    /** What one agent may spend in a day (UTC), across all its runs. */
    @Column(name = "per_agent_daily_cap", precision = 14, scale = 4)
    private BigDecimal perAgentDailyCap;

    /**
     * What happens when a cap is reached: {@code stop} refuses the call, {@code sandbox} answers
     * with the offline model instead. The database holds no other value.
     */
    @Column(name = "on_exhausted", nullable = false)
    private String onExhausted = ON_EXHAUSTED_STOP;

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

    public String getOnExhausted() {
        return onExhausted;
    }

    public void setOnExhausted(String onExhausted) {
        this.onExhausted = onExhausted;
    }

    /** Whether the workspace chose the offline model over stopping when a cap is reached. */
    public boolean degradesToSandbox() {
        return ON_EXHAUSTED_SANDBOX.equals(onExhausted);
    }

    /** True when at least one cap is set, so there is something to enforce. */
    public boolean hasAnyCap() {
        return monthlyCap != null || perRunCap != null || perAgentDailyCap != null;
    }
}
