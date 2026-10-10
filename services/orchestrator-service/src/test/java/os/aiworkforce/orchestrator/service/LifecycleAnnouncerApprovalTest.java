// @find: tests for lifecycle announcer approval, lifecycle announcer, approval raised, approval expired, notifications after commit
// @what: Unit and integration tests (6 cases) for lifecycle announcer approval, for example: raised with the work behind it; raised for adirect run; expired; schedule paused.
package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static os.aiworkforce.orchestrator.service.WorkFixture.ORG;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.orchestrator.domain.Approval;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.RunQuestions;
import os.aiworkforce.orchestrator.service.GoalLifecycleListener.SchedulePause;

/** An approval raised or expired, and a schedule that paused itself, reach every listener with what they need. */
class LifecycleAnnouncerApprovalTest {

    private WorkFixture work;
    private GoalLifecycleListener first;
    private GoalLifecycleListener second;
    private LifecycleAnnouncer announcer;

    @BeforeEach
    void setUp() {
        work = new WorkFixture();
        first = mock(GoalLifecycleListener.class);
        second = mock(GoalLifecycleListener.class);
        announcer = new LifecycleAnnouncer(work.goals, work.tasks, mock(RunQuestions.class), List.of(first, second), null);
    }

    private Approval approvalFor(Task task) {
        Approval approval = new Approval();
        approval.setId(UUID.randomUUID());
        approval.setOrgId(ORG);
        approval.setRunId(UUID.randomUUID());
        approval.setAgentId(UUID.randomUUID());
        approval.setTaskId(task == null ? null : task.getId());
        return approval;
    }

    @Test
    @DisplayName("a raised approval is heard with its task and goal")
    void raisedWithTheWorkBehindIt() {
        Goal goal = work.goal("running");
        Task task = work.task(goal, 0, "waiting_approval");
        Approval approval = approvalFor(task);

        announcer.approvalRaised(approval);

        verify(first).onApprovalRaised(goal, task, approval);
        verify(second).onApprovalRaised(goal, task, approval);
    }

    @Test
    @DisplayName("an approval for a run started directly is heard with no task and no goal")
    void raisedForADirectRun() {
        Approval approval = approvalFor(null);

        announcer.approvalRaised(approval);

        verify(first).onApprovalRaised(isNull(), isNull(), any());
    }

    @Test
    @DisplayName("an expiry is heard with the same context")
    void expired() {
        Goal goal = work.goal("running");
        Task task = work.task(goal, 0, "waiting_approval");
        Approval approval = approvalFor(task);

        announcer.approvalExpired(approval);

        verify(first).onApprovalExpired(goal, task, approval);
        verify(second).onApprovalExpired(goal, task, approval);
    }

    @Test
    @DisplayName("a paused schedule is heard with who it works for and why")
    void schedulePaused() {
        SchedulePause pause = new SchedulePause(ORG, UUID.randomUUID(), UUID.randomUUID(), "Paused after 3 failed runs in a row.", 3);

        announcer.schedulePaused(pause);

        verify(first).onSchedulePaused(pause);
        verify(second).onSchedulePaused(pause);
    }

    @Test
    @DisplayName("a listener that fails neither stops the others nor reaches the caller")
    void aFailingListenerIsContained() {
        Approval approval = approvalFor(null);
        doThrow(new IllegalStateException("boom")).when(first).onApprovalRaised(any(), any(), any());

        assertThatCode(() -> announcer.approvalRaised(approval)).doesNotThrowAnyException();

        verify(second).onApprovalRaised(isNull(), isNull(), any());
    }

    @Test
    @DisplayName("nothing is announced for nothing")
    void nullsAreIgnored() {
        announcer.approvalRaised(null);
        announcer.approvalExpired(null);
        announcer.schedulePaused(null);

        verify(first, never()).onApprovalRaised(any(), any(), any());
        verify(first, never()).onApprovalExpired(any(), any(), any());
        verify(first, never()).onSchedulePaused(any());
    }
}
