// @find: tests for agent controller run, POST /api/agents/{agentId}/runs, run agent, start run, give agent a task
// @what: Unit and integration tests (2 cases) for agent controller run, for example: run answers before the agent works; paused agent is refused in the request.
package os.aiworkforce.orchestrator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import os.aiworkforce.orchestrator.repository.AgentVersions;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.ToolGrants;
import os.aiworkforce.orchestrator.service.AgentRunner;
import os.aiworkforce.orchestrator.service.GeneralEmployee;
import os.aiworkforce.orchestrator.service.RunExecutor;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Giving an agent a task directly saves the run and answers 202 with its id at once; the agent
 * works on the executor's thread, as the person who asked, never inside the request.
 */
class AgentControllerRunTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private AgentRunner runner;
    private RunExecutor executor;
    private AgentController controller;
    private Actor person;

    @BeforeEach
    void setUp() {
        runner = mock(AgentRunner.class);
        executor = mock(RunExecutor.class);
        controller = new AgentController(
                mock(Agents.class),
                mock(AgentVersions.class),
                runner,
                mock(ToolGrants.class),
                mock(GeneralEmployee.class));
        controller.setRunExecutor(executor);
        person = Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of("agent:run"), 0L);
        RequestContext.setActor(person);
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("prepares the run, hands its drive to the executor as the caller, and answers 202 with its id")
    void runAnswersBeforeTheAgentWorks() throws Exception {
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(runner.prepare(ORG, agentId, null, "Check the stock levels", "manual")).thenReturn(runId);

        AgentController.RunStarted started =
                controller.run(agentId, new AgentController.RunRequest("Check the stock levels"));

        assertThat(started.runId()).isEqualTo(runId);
        assertThat(started.status()).isEqualTo("running");
        assertThat(started.answer()).isNull();
        verify(executor).submitDrive(ORG, runId, person);
        verify(runner, never()).start(any(), any(), any(), any(), any());
        verify(runner, never()).drive(any(), any());
        ResponseStatus status = AgentController.class
                .getMethod("run", UUID.class, AgentController.RunRequest.class)
                .getAnnotation(ResponseStatus.class);
        assertThat(status.value()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    @DisplayName("a paused agent is refused straight away, and nothing is handed to the executor")
    void pausedAgentIsRefusedInTheRequest() {
        UUID agentId = UUID.randomUUID();
        when(runner.prepare(ORG, agentId, null, "Check the stock levels", "manual"))
                .thenThrow(new ApiException(ErrorCode.POLICY_VIOLATION, "That agent is paused."));

        assertThatThrownBy(() -> controller.run(agentId, new AgentController.RunRequest("Check the stock levels")))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.POLICY_VIOLATION));
        verifyNoInteractions(executor);
    }
}
