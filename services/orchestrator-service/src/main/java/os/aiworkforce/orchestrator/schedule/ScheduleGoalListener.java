// @find: schedule goal listener, goal finished, schedule last status, consecutive failures, auto pause schedule, announce pause, lifecycle listener, ScheduleGoalListener
// @what: Updates a schedule's last result when its goal finishes and pauses it after repeated failures.
// @flow: Called by the goal lifecycle (GoalLifecycleListener); updates Schedule
package os.aiworkforce.orchestrator.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.service.GoalLifecycleListener;
import os.aiworkforce.orchestrator.service.LifecycleAnnouncer;

/**
 * Keeps a schedule's own health in step with the goals it fires.
 *
 * <p>{@link os.aiworkforce.orchestrator.service.TaskProgress} calls every listener as it settles
 * a goal's fate; this one reacts only to a goal whose {@code scheduleId} is set, which is exactly
 * the goals this package's own {@link ScheduleService} and {@link ScheduleSweep} created. A
 * schedule that fails three times in a row pauses itself, with a reason a person can read on the
 * Schedules page, rather than quietly retrying forever against whatever keeps breaking it. The
 * pause is announced once it commits, so a workspace that set up notifications hears about it
 * instead of finding out on Monday.
 */
@Component
public class ScheduleGoalListener implements GoalLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(ScheduleGoalListener.class);
    static final int MAX_CONSECUTIVE_FAILURES = 3;
    static final String PAUSED_REASON = "Paused after 3 failed runs in a row.";

    private final Schedules schedules;
    /**
     * Looked up when a pause happens rather than injected: the announcer is built with every
     * listener, this one included, so asking for it at construction would be a cycle. Null in unit
     * tests that do not look at announcements.
     */
    private ObjectProvider<LifecycleAnnouncer> announcer;

    public ScheduleGoalListener(Schedules schedules) {
        this.schedules = schedules;
    }

    @Autowired(required = false)
    void setAnnouncer(ObjectProvider<LifecycleAnnouncer> announcer) {
        this.announcer = announcer;
    }

    // @find: schedule goal finished, update last status, pause after failures
    @Override
    @Transactional
    public void onGoalFinished(Goal goal) {
        if (goal.getScheduleId() == null) {
            return;
        }
        schedules.findByIdAndOrgId(goal.getScheduleId(), goal.getOrgId()).ifPresent(schedule -> {
            schedule.setLastStatus(goal.getStatus());
            if ("completed".equals(goal.getStatus())) {
                schedule.setConsecutiveFailures(0);
            } else if ("failed".equals(goal.getStatus())) {
                int failures = schedule.getConsecutiveFailures() + 1;
                schedule.setConsecutiveFailures(failures);
                if (failures >= MAX_CONSECUTIVE_FAILURES) {
                    // Announced only as the schedule goes from running to paused: a paused
                    // schedule run by hand that fails again is not news a second time.
                    boolean pausingNow = schedule.isEnabled();
                    schedule.setEnabled(false);
                    schedule.setPausedReason(PAUSED_REASON);
                    log.warn("Schedule {} paused itself after {} failed runs in a row", schedule.getId(), failures);
                    if (pausingNow) {
                        announcePause(new SchedulePause(
                                schedule.getOrgId(),
                                schedule.getId(),
                                schedule.getRequestedBy(),
                                PAUSED_REASON,
                                failures));
                    }
                }
            }
            // A cancelled goal (a person cancelled this one occurrence) says nothing about
            // whether the schedule itself is healthy, so the streak is left exactly as it was.
            schedules.save(schedule);
        });
    }

    @Override
    public void onTaskFinished(Goal goal, Task task, String status) {
        // A schedule cares about the goal it fired reaching its end, not about one task within
        // it; onGoalFinished is the one signal this listener needs.
    }

    private void announcePause(SchedulePause pause) {
        LifecycleAnnouncer lifecycle = announcer == null ? null : announcer.getIfAvailable();
        if (lifecycle == null) {
            return;
        }
        LifecycleAnnouncer.afterCommit(() -> lifecycle.schedulePaused(pause));
    }
}
