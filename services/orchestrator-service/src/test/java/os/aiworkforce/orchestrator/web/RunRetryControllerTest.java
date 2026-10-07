package os.aiworkforce.orchestrator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.RunStep;
import os.aiworkforce.orchestrator.repository.RunSteps;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.service.AgentRunner;
import os.aiworkforce.orchestrator.service.RunExecutor;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;

/** Try again for a direct run starts a new run with the same instruction and leaves the old one alone. */
class RunRetryControllerTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();

    private Runs runs;
    private RunSteps steps;
    private AgentRunner runner;
    private RunExecutor executor;
    private RunRetryController controller;
    private Run run;

    @BeforeEach
    void setUp() {
        runs = mock(Runs.class);
        steps = mock(RunSteps.class);
        runner = mock(AgentRunner.class);
        executor = mock(RunExecutor.class);
        controller = new RunRetryController(runs, steps, runner, executor);
        run = new Run();
        run.setId(UUID.randomUUID());
        run.setOrgId(ORG);
        run.setAgentId(UUID.randomUUID());
        run.setStatus("failed");
        ReflectionTestUtils.setField(run, "createdBy", ME.toString());
        when(runs.findByIdAndOrgId(run.getId(), ORG)).thenReturn(Optional.of(run));
        when(steps.findByRunIdOrderByPosition(run.getId()))
                .thenReturn(List.of(RunStep.of(
                        ORG, run.getId(), 0, "note", Map.of("type", "instruction", "content", "Summarise the inbox."))));
        RequestContext.setActor(Actor.user(ME.toString(), ORG.toString(), "role", Set.of("agent:run"), 0L));
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    @Test
    void startsANewRunWithTheOriginalInstruction() {
        UUID fresh = UUID.randomUUID();
        when(runner.prepare(eq(ORG), eq(run.getAgentId()), eq(null), eq("Summarise the inbox."), eq("manual")))
                .thenReturn(fresh);

        RunRetryController.Retried result = controller.retry(run.getId());

        assertThat(result.runId()).isEqualTo(fresh);
        assertThat(result.retryOf()).isEqualTo(run.getId());
        verify(executor).submitDrive(eq(ORG), eq(fresh), any(Actor.class));
        assertThat(run.getStatus()).isEqualTo("failed");
    }

    @Test
    void aRunStillGoingCannotBeRetried() {
        run.setStatus("running");
        assertThatThrownBy(() -> controller.retry(run.getId())).isInstanceOf(ApiException.class);
        verify(runner, never()).prepare(any(), any(), any(), any(), any());
    }

    @Test
    void someoneElsesRunNeedsTheStopPermission() {
        RequestContext.setActor(Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of("agent:run"), 0L));
        assertThatThrownBy(() -> controller.retry(run.getId())).isInstanceOf(ApiException.class);

        RequestContext.setActor(
                Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of("agent:run", "run:cancel"), 0L));
        when(runner.prepare(any(), any(), any(), any(), any())).thenReturn(UUID.randomUUID());
        assertThat(controller.retry(run.getId()).retryOf()).isEqualTo(run.getId());
    }

    @Test
    void aGoalRunIsRetriedThroughItsGoal() {
        run.setTaskId(UUID.randomUUID());
        assertThatThrownBy(() -> controller.retry(run.getId())).isInstanceOf(ApiException.class);
    }
}
