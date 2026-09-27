package os.aiworkforce.orchestrator.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.Tasks;

/**
 * A goal and its tasks held in memory, behind mocked repositories.
 *
 * <p>The repositories answer from the same objects the code under test changes, the way a
 * persistence context would, so a test can assert on the final state of every task and goal
 * rather than on the order of repository calls.
 */
final class WorkFixture {

    static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    final Tasks tasks = mock(Tasks.class);
    final Goals goals = mock(Goals.class);
    final List<Task> allTasks = new ArrayList<>();
    final List<Goal> allGoals = new ArrayList<>();

    WorkFixture() {
        lenient().when(tasks.findById(any())).thenAnswer(call -> allTasks.stream()
                .filter(task -> task.getId().equals(call.getArgument(0)))
                .findFirst());
        lenient().when(tasks.findByGoalIdOrderByPosition(any())).thenAnswer(call -> allTasks.stream()
                .filter(task -> task.getGoalId().equals(call.getArgument(0)))
                .sorted(Comparator.comparingInt(Task::getPosition))
                .toList());
        lenient().when(goals.findById(any())).thenAnswer(call -> find(call.getArgument(0)));
        lenient().when(goals.findByIdAndOrgId(any(), any())).thenAnswer(call -> find(call.getArgument(0)));
    }

    private Optional<Goal> find(Object id) {
        return allGoals.stream().filter(goal -> goal.getId().equals(id)).findFirst();
    }

    Goal goal(String status) {
        Goal goal = new Goal();
        goal.setId(UUID.randomUUID());
        goal.setOrgId(ORG);
        goal.setTitle("Onboard the new starter");
        goal.setStatus(status);
        allGoals.add(goal);
        return goal;
    }

    Task task(Goal goal, int position, String status) {
        Task task = new Task();
        task.setId(UUID.randomUUID());
        task.setOrgId(ORG);
        task.setGoalId(goal.getId());
        task.setAgentId(UUID.randomUUID());
        task.setTitle("Task " + position);
        task.setInstruction("Do step " + position);
        task.setPosition(position);
        task.setStatus(status);
        allTasks.add(task);
        return task;
    }

    static Task attempt(Task task, int attempt, int maxAttempts) {
        task.setAttempt(attempt);
        task.setMaxAttempts(maxAttempts);
        return task;
    }

    static Run runFor(Task task, String status) {
        Run run = new Run();
        run.setId(UUID.randomUUID());
        run.setOrgId(ORG);
        run.setTaskId(task == null ? null : task.getId());
        run.setAgentId(task == null ? UUID.randomUUID() : task.getAgentId());
        run.setAgentVersionId(UUID.randomUUID());
        run.setStatus(status);
        return run;
    }
}
