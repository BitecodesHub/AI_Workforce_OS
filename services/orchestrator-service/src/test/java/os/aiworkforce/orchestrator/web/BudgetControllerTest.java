// @find: tests for budget controller, budget api, spending caps, /api/orchestrator/budget
// @what: Unit and integration tests (13 cases) for budget controller, for example: permissions; usage permissions; get without abudget; get with acap.
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import os.aiworkforce.orchestrator.domain.Budget;
import os.aiworkforce.orchestrator.repository.Budgets;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.orchestrator.service.JpaBudgetGuard;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/** The budget endpoint: who may read and change a cap, what it returns, and that a change is audited. */
class BudgetControllerTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    /** Eleven days into a 31-day month, at midnight, so ten days have elapsed. */
    private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");

    private Budgets budgets;
    private JpaBudgetGuard guard;
    private AuditClient audit;
    private BudgetController controller;
    private Actor caller;

    @BeforeEach
    void setUp() {
        budgets = mock(Budgets.class);
        guard = mock(JpaBudgetGuard.class);
        audit = mock(AuditClient.class);
        controller = new BudgetController(budgets, guard, audit, Clock.fixed(NOW, ZoneOffset.UTC));
        caller = Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of(), 0L);
        RequestContext.setActor(caller);
        when(guard.freshMonthToDate(ORG)).thenReturn(new BigDecimal("10"));
        when(budgets.save(any(Budget.class))).thenAnswer(call -> call.getArgument(0));
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    private static BudgetController.BudgetRequest request(String monthly, String perRun, String perAgent, String on) {
        return new BudgetController.BudgetRequest(decimal(monthly), decimal(perRun), decimal(perAgent), on);
    }

    private static BigDecimal decimal(String text) {
        return text == null ? null : new BigDecimal(text);
    }

    private Budget existing() {
        Budget budget = new Budget();
        budget.setId(ORG);
        when(budgets.findForOrg(ORG)).thenReturn(Optional.of(budget));
        return budget;
    }

    // ---- Permissions -----------------------------------------------------------------------

    @Test
    @DisplayName("reading the budget needs budget:read and changing it needs budget:manage")
    void permissions() throws Exception {
        assertThat(permissionOf("get")).containsExactly(Permission.Codes.BUDGET_READ);
        assertThat(permissionOf("update", BudgetController.BudgetRequest.class))
                .containsExactly(Permission.Codes.BUDGET_MANAGE);
    }

    @Test
    @DisplayName("every usage endpoint needs budget:read")
    void usagePermissions() throws Exception {
        assertThat(UsageController.class
                        .getMethod("report", String.class, String.class, String.class)
                        .getAnnotation(RequiresPermission.class)
                        .value())
                .containsExactly(Permission.Codes.BUDGET_READ);
        assertThat(UsageController.class
                        .getMethod(
                                "csv",
                                String.class,
                                String.class,
                                String.class,
                                jakarta.servlet.http.HttpServletResponse.class)
                        .getAnnotation(RequiresPermission.class)
                        .value())
                .containsExactly(Permission.Codes.BUDGET_READ);
    }

    private static String[] permissionOf(String name, Class<?>... parameters) throws Exception {
        return BudgetController.class.getMethod(name, parameters).getAnnotation(RequiresPermission.class).value();
    }

    // ---- Reading ---------------------------------------------------------------------------

    @Test
    @DisplayName("a workspace that never set a cap sees no caps, what it spent, and a projection")
    void getWithoutABudget() {
        when(budgets.findForOrg(ORG)).thenReturn(Optional.empty());

        BudgetController.BudgetView view = controller.get();

        assertThat(view.monthlyCap()).isNull();
        assertThat(view.perRunCap()).isNull();
        assertThat(view.perAgentDailyCap()).isNull();
        assertThat(view.onExhausted()).isEqualTo("stop");
        assertThat(view.remaining()).isNull();
        assertThat(view.spentThisMonth()).isEqualByComparingTo("10");
        assertThat(view.periodStart()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(view.basis()).isEqualTo("estimated USD from catalogue prices");
    }

    @Test
    @DisplayName("the remaining amount is the cap less what has been spent, and the projection is the pace so far")
    void getWithACap() {
        Budget budget = existing();
        budget.setMonthlyCap(new BigDecimal("40"));
        budget.setPerRunCap(new BigDecimal("0.5"));
        budget.setPerAgentDailyCap(new BigDecimal("3"));
        budget.setOnExhausted(Budget.ON_EXHAUSTED_SANDBOX);

        BudgetController.BudgetView view = controller.get();

        assertThat(view.monthlyCap()).isEqualByComparingTo("40");
        assertThat(view.perRunCap()).isEqualByComparingTo("0.5");
        assertThat(view.perAgentDailyCap()).isEqualByComparingTo("3");
        assertThat(view.onExhausted()).isEqualTo("sandbox");
        assertThat(view.remaining()).isEqualByComparingTo("30");
        // Ten dollars in ten days, in a month of 31 days.
        assertThat(view.projectedMonthEnd()).isEqualByComparingTo("31");
    }

    @Test
    @DisplayName("an overspent workspace has nothing remaining, never a negative amount")
    void remainingNeverNegative() {
        Budget budget = existing();
        budget.setMonthlyCap(new BigDecimal("4"));

        assertThat(controller.get().remaining()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a projection is never made from less than a day of history, and never falls below the spend")
    void projectionFloors() {
        Instant monthStart = Instant.parse("2026-10-01T00:00:00Z");

        // Six hours in: a day's worth of pace, not four times that.
        assertThat(BudgetController.projectMonthEnd(
                        new BigDecimal("1"), monthStart, Instant.parse("2026-10-01T06:00:00Z")))
                .isEqualByComparingTo("31");
        assertThat(BudgetController.projectMonthEnd(BigDecimal.ZERO, monthStart, NOW))
                .isEqualByComparingTo("0");
    }

    // ---- Changing --------------------------------------------------------------------------

    @Test
    @DisplayName("a first cap creates the row and saves every field")
    void firstCap() {
        Budget budget = existing();

        BudgetController.BudgetView view = controller.update(request("25", "0.50", "2", "sandbox"));

        verify(budgets).insertIfAbsent(ORG);
        assertThat(budget.getMonthlyCap()).isEqualByComparingTo("25");
        assertThat(budget.getPerRunCap()).isEqualByComparingTo("0.5");
        assertThat(budget.getPerAgentDailyCap()).isEqualByComparingTo("2");
        assertThat(budget.getOnExhausted()).isEqualTo("sandbox");
        verify(budgets).save(budget);
        assertThat(view.monthlyCap()).isEqualByComparingTo("25");
        assertThat(view.remaining()).isEqualByComparingTo("15");
    }

    @Test
    @DisplayName("a cap left out is removed, and a missing onExhausted keeps what the workspace has")
    void leftOutCapIsRemoved() {
        Budget budget = existing();
        budget.setMonthlyCap(new BigDecimal("40"));
        budget.setPerRunCap(new BigDecimal("1"));
        budget.setOnExhausted(Budget.ON_EXHAUSTED_SANDBOX);

        controller.update(request("40", null, null, null));

        assertThat(budget.getMonthlyCap()).isEqualByComparingTo("40");
        assertThat(budget.getPerRunCap()).isNull();
        assertThat(budget.getOnExhausted()).isEqualTo("sandbox");
    }

    @Test
    @DisplayName("a negative cap is refused, whichever cap it is, and nothing is saved")
    void negativeCapRefused() {
        existing();

        for (BudgetController.BudgetRequest bad : new BudgetController.BudgetRequest[] {
            request("-1", null, null, null), request(null, "-0.01", null, null), request(null, null, "-5", null)
        }) {
            ApiException refused = catchThrowableOfType(() -> controller.update(bad), ApiException.class);
            assertThat(refused.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(refused.status()).isEqualTo(422);
        }
        verify(budgets, never()).save(any());
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a cap too large for the column is refused rather than failing in the database")
    void hugeCapRefused() {
        existing();

        ApiException refused = catchThrowableOfType(
                () -> controller.update(request("1000000001", null, null, null)), ApiException.class);

        assertThat(refused.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("a way of handling the cap that does not exist is refused")
    void unknownOnExhaustedRefused() {
        existing();

        ApiException refused = catchThrowableOfType(
                () -> controller.update(request("5", null, null, "downgrade")), ApiException.class);

        assertThat(refused.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(budgets, never()).save(any());
    }

    @Test
    @DisplayName("a cap is kept to the four decimals it is stored in")
    void capIsRounded() {
        Budget budget = existing();

        controller.update(request("0.12345", null, null, null));

        assertThat(budget.getMonthlyCap()).isEqualByComparingTo("0.1235");
    }

    @Test
    @DisplayName("every change is audited as budget.update with the old and the new values")
    void changeIsAudited() {
        Budget budget = existing();
        budget.setMonthlyCap(new BigDecimal("40.0000"));
        budget.setOnExhausted(Budget.ON_EXHAUSTED_STOP);

        controller.update(request("25", "0.5", null, "sandbox"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(audit)
                .record(
                        eq(ORG),
                        eq(caller),
                        eq("budget.update"),
                        eq("budget"),
                        eq(ORG.toString()),
                        eq("succeeded"),
                        detail.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> old = (Map<String, Object>) detail.getValue().get("old");
        @SuppressWarnings("unchecked")
        Map<String, Object> updated = (Map<String, Object>) detail.getValue().get("new");
        assertThat(old)
                .containsEntry("monthlyCap", "40")
                .containsEntry("perRunCap", null)
                .containsEntry("onExhausted", "stop");
        assertThat(updated)
                .containsEntry("monthlyCap", "25")
                .containsEntry("perRunCap", "0.5")
                .containsEntry("onExhausted", "sandbox");
    }
}
