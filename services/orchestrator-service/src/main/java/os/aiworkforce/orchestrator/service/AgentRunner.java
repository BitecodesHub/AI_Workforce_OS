package os.aiworkforce.orchestrator.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.llm.model.AttemptRecord;
import os.aiworkforce.llm.model.ChatMessage;
import os.aiworkforce.llm.model.ChatRequest;
import os.aiworkforce.llm.model.ChatResponse;
import os.aiworkforce.llm.model.ToolCall;
import os.aiworkforce.llm.model.ToolSpec;
import os.aiworkforce.llm.router.ModelRouter;
import os.aiworkforce.llm.router.RoutingPolicy;
import os.aiworkforce.mcp.model.ToolDefinition;
import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.policy.ApprovalDecision;
import os.aiworkforce.mcp.policy.ToolGateway;
import os.aiworkforce.mcp.policy.ToolGrant;
import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.AgentToolGrant;
import os.aiworkforce.orchestrator.domain.AgentVersion;
import os.aiworkforce.orchestrator.domain.Approval;
import os.aiworkforce.orchestrator.domain.Run;
import os.aiworkforce.orchestrator.domain.RunStep;
import os.aiworkforce.orchestrator.repository.AgentVersions;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.RunSteps;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.repository.ToolGrants;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * Runs one agent against one instruction.
 *
 * <p>The loop is ordinary - ask the model, run any tools it asked for, feed the results back,
 * repeat - and almost all of the code here is about the three ways that loop goes wrong in
 * practice:
 *
 * <ul>
 *   <li><b>It does not stop.</b> A model that keeps calling a tool whose result never satisfies
 *       it will loop until the budget is gone. {@code maxSteps} bounds it, and exhausting the
 *       bound is reported as a failure rather than as a finished answer.
 *   <li><b>It needs permission half way through.</b> An outbound tool parks the run. The run is
 *       <em>persisted and abandoned</em>, not blocked on a thread: an approval can take a day,
 *       and a thread held for a day is a thread not serving anybody.
 *   <li><b>The worker dies.</b> Every step is written before the next begins and the lease is
 *       renewed as it goes, so a restart resumes from the last completed step instead of
 *       repeating a side effect that already happened.
 * </ul>
 */
@Service
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final String WORKER_ID = UUID.randomUUID().toString().substring(0, 8);

    private final Runs runs;
    private final RunSteps steps;
    private final Agents agents;
    private final AgentVersions versions;
    private final ToolGrants grants;
    private final ModelRouter router;
    private final ToolGateway tools;
    private final RoutingPolicyResolver policies;
    private final ApprovalService approvals;
    private final ToolCredentialResolver toolCredentials;

    public AgentRunner(
            Runs runs,
            RunSteps steps,
            Agents agents,
            AgentVersions versions,
            ToolGrants grants,
            ModelRouter router,
            ToolGateway tools,
            RoutingPolicyResolver policies,
            ApprovalService approvals,
            ToolCredentialResolver toolCredentials) {
        this.runs = runs;
        this.steps = steps;
        this.agents = agents;
        this.versions = versions;
        this.grants = grants;
        this.router = router;
        this.tools = tools;
        this.policies = policies;
        this.approvals = approvals;
        this.toolCredentials = toolCredentials;
    }

    /**
     * How a run ended.
     *
     * @param status the run's terminal state, or {@code waiting_approval}
     * @param answer the agent's final text, when it produced one
     * @param runId the run, so a caller can poll or link to the trace
     */
    public record Outcome(String status, String answer, UUID runId) {

        public boolean isWaiting() {
            return "waiting_approval".equals(status);
        }
    }

    /** Starts a new run and drives it as far as it will go. */
    @Transactional
    public Outcome start(UUID orgId, UUID agentId, UUID taskId, String instruction, String trigger) {
        Agent agent = agents.findByIdAndOrgId(agentId, orgId)
                .orElseThrow(() -> ApiException.notFound("agent", agentId));
        if (!agent.isActive()) {
            throw new ApiException(ErrorCode.POLICY_VIOLATION, "That agent is paused.");
        }

        AgentVersion version = versions.findById(agent.getCurrentVersionId())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.CONFLICT, "That agent has no configuration yet. Save its persona first."));
        // Sealing on first use is what keeps a trace readable: the prompt cannot be edited out
        // from under the record of what it produced.
        version.seal();
        versions.save(version);

        Run run = new Run();
        run.setId(UuidV7.generate());
        run.setOrgId(orgId);
        run.setTaskId(taskId);
        run.setAgentId(agentId);
        run.setAgentVersionId(version.getId());
        run.setTrigger(trigger);
        run.renewLease(WORKER_ID, LEASE);
        runs.save(run);

        List<ChatMessage> conversation = new ArrayList<>();
        conversation.add(ChatMessage.system(buildSystemPrompt(agent, version)));
        conversation.add(ChatMessage.user(instruction));

        return drive(run, agent, version, conversation);
    }

    /**
     * Resumes a run that was parked waiting for an approval.
     *
     * <p>The conversation is rebuilt from the persisted steps rather than held in memory, which is
     * precisely what allows the approval to take a day and to be decided by a different instance
     * of the service from the one that raised it.
     */
    @Transactional
    public Outcome resume(UUID orgId, UUID runId) {
        Run run = runs.findByIdAndOrgId(runId, orgId)
                .orElseThrow(() -> ApiException.notFound("run", runId));
        if (!"waiting_approval".equals(run.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "That run is not waiting for a decision.");
        }

        Agent agent = agents.findByIdAndOrgId(run.getAgentId(), orgId)
                .orElseThrow(() -> ApiException.notFound("agent", run.getAgentId()));
        AgentVersion version = versions.findById(run.getAgentVersionId())
                .orElseThrow(() -> ApiException.notFound("agent version", run.getAgentVersionId()));

        run.setStatus("running");
        run.renewLease(WORKER_ID, LEASE);
        runs.save(run);

        return drive(run, agent, version, rebuildConversation(run, agent, version));
    }

    // ---- The loop ------------------------------------------------------------------------

    private Outcome drive(Run run, Agent agent, AgentVersion version, List<ChatMessage> conversation) {
        RoutingPolicy policy = policies.resolve(run.getOrgId(), agent.getId());
        List<ToolGrant> toolGrants = loadGrants(agent.getId());
        List<ToolDefinition> available = tools.availableTools(toolGrants);
        List<ToolSpec> specs = available.stream().map(ToolDefinition::toSpec).toList();

        ModelRouter.CallContext context = new ModelRouter.CallContext(
                run.getOrgId().toString(), agent.getId().toString(), run.getId().toString());

        while (run.getStepCount() < version.getMaxSteps()) {
            ChatRequest request = ChatRequest.builder()
                    .messages(List.copyOf(conversation))
                    .tools(specs)
                    .temperature(version.getTemperature() == null ? null : version.getTemperature().doubleValue())
                    .maxOutputTokens(version.getMaxOutputTokens())
                    .timeout(Duration.ofSeconds(120))
                    // Keyed on the run and step, so a retried HTTP request cannot pay twice for
                    // the same logical turn.
                    .idempotencyKey(run.getId() + ":" + run.getStepCount())
                    .build();

            ChatResponse response;
            try {
                response = router.route(request, policy, context);
            } catch (ApiException e) {
                recordStep(run, "error", Map.of("code", e.code().wire(), "detail", e.getMessage()));
                return finish(run, "failed", e.getMessage(), null);
            }

            recordModelStep(run, response);
            run.setStepCount(run.getStepCount() + 1);
            // Renewed every step, so a long run is never reaped while it is genuinely working.
            run.renewLease(WORKER_ID, LEASE);
            runs.save(run);

            if (!response.hasToolCalls()) {
                // A truncated answer is not a finished one. Saying so lets a person ask for the
                // rest rather than acting on half of it.
                if (response.isTruncated()) {
                    return finish(run, "failed",
                            "The model's answer was cut short by the output limit.", response.content());
                }
                return finish(run, "completed", null, response.content());
            }

            conversation.add(response.asMessage());

            for (ToolCall call : response.toolCalls()) {
                ToolOutcome outcome = runTool(run, agent, call, toolGrants, available);
                if (outcome.parked()) {
                    return park(run);
                }
                conversation.add(ChatMessage.toolResult(call.id(), call.name(), outcome.result().forModel()));
            }
        }

        // Exhausting the step budget is a failure, not a completion. An agent that stopped
        // because it ran out of turns has not answered the question.
        return finish(run, "failed",
                "The agent reached its step limit of " + version.getMaxSteps() + " without finishing.", null);
    }

    private record ToolOutcome(ToolResult result, boolean parked) {}

    /**
     * Runs one tool the model asked for, or parks the run if a person must decide first.
     *
     * <p>The evaluation is made by the gateway, not here, so every server is governed identically.
     * This method's only judgement is what to do with the three possible answers.
     */
    private ToolOutcome runTool(
            Run run, Agent agent, ToolCall call, List<ToolGrant> toolGrants, List<ToolDefinition> available) {

        String[] parts = call.name().split("\\.", 2);
        if (parts.length != 2) {
            return new ToolOutcome(
                    ToolResult.failed("Tool names must be written as server.tool, for example gmail.send_message."),
                    false);
        }

        ToolInvocation invocation = new ToolInvocation(
                run.getOrgId().toString(),
                agent.getId().toString(),
                run.getId().toString(),
                parts[0],
                parts[1],
                call.argumentsJson(),
                run.getId() + ":" + call.id(),
                Map.of("runId", run.getId().toString()));

        ApprovalDecision decision = tools.evaluate(invocation, toolGrants, false);

        if (decision instanceof ApprovalDecision.AwaitApproval await) {
            Approval approval = approvals.raise(run, agent, invocation, await, call);
            recordStep(run, "approval", Map.of(
                    "approvalId", approval.getId().toString(),
                    "tool", call.name(),
                    "summary", await.reason()));
            return new ToolOutcome(ToolResult.blocked(await.reason()), true);
        }

        if (decision instanceof ApprovalDecision.Refuse refuse) {
            recordStep(run, "tool_call", Map.of(
                    "tool", call.name(), "status", "BLOCKED", "reason", refuse.reason()));
            return new ToolOutcome(ToolResult.blocked(refuse.reason()), false);
        }

        String credential = toolCredentials.resolve(run.getOrgId(), parts[0]).orElse(null);
        Instant startedAt = Instant.now();
        ToolResult result = tools.invoke(invocation, decision, credential).block(Duration.ofSeconds(60));
        if (result == null) {
            result = ToolResult.indeterminate("The tool did not return a result.", Duration.ZERO);
        }

        recordStep(run, "tool_call", Map.of(
                "tool", call.name(),
                "status", result.status().name(),
                "summary", result.summary() == null ? "" : result.summary(),
                "durationMs", Duration.between(startedAt, Instant.now()).toMillis()));

        return new ToolOutcome(result, false);
    }

    // ---- Persistence of the trace --------------------------------------------------------

    private void recordModelStep(Run run, ChatResponse response) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("provider", response.provider());
        detail.put("model", response.model());
        detail.put("finishReason", response.finishReason().name());
        detail.put("content", truncate(response.content()));
        detail.put("toolCalls", response.toolCalls().stream().map(ToolCall::name).toList());
        // The failed attempts are the useful part: a run that answered on the third provider is
        // only explicable if the first two are in the record.
        detail.put("attempts", response.attempts().stream().map(AttemptRecord::summary).toList());

        BigDecimal cost = BigDecimal.ZERO;
        RunStep step = RunStep.of(run.getOrgId(), run.getId(), nextPosition(run), "model_call", detail)
                .withModel(
                        response.provider(),
                        response.model(),
                        response.usage().promptTokens(),
                        response.usage().completionTokens(),
                        cost,
                        response.latency() == null ? 0 : response.latency().toMillis());
        steps.save(step);

        run.setTotalPromptTokens(run.getTotalPromptTokens() + response.usage().promptTokens());
        run.setTotalCompletionTokens(run.getTotalCompletionTokens() + response.usage().completionTokens());
    }

    private void recordStep(Run run, String kind, Map<String, Object> detail) {
        steps.save(RunStep.of(run.getOrgId(), run.getId(), nextPosition(run), kind, detail));
    }

    private int nextPosition(Run run) {
        return steps.highestPosition(run.getId()) + 1;
    }

    private Outcome park(Run run) {
        run.setStatus("waiting_approval");
        run.renewLease(WORKER_ID, Duration.ofDays(7));
        runs.save(run);
        log.info("Run {} parked waiting for approval", run.getId());
        return new Outcome("waiting_approval", null, run.getId());
    }

    private Outcome finish(Run run, String status, String failureReason, String answer) {
        run.finish(status, failureReason);
        runs.save(run);
        tools.releaseRun(run.getId().toString());
        log.info("Run {} finished as {}", run.getId(), status);
        return new Outcome(status, answer, run.getId());
    }

    // ---- Assembly ---------------------------------------------------------------------------

    /**
     * Builds the agent's instructions.
     *
     * <p>The persona comes from the version row; the paragraph below it is the platform's own
     * standing instruction, and it exists because an agent that invents a tool result, or claims
     * to have sent something it did not, is the failure that ends trust in the whole system.
     */
    private String buildSystemPrompt(Agent agent, AgentVersion version) {
        StringBuilder prompt = new StringBuilder(version.getSystemPrompt());
        if (!version.getGoals().isBlank()) {
            prompt.append("\n\nYour standing goals:\n").append(version.getGoals());
        }
        prompt.append("""

                How this platform works, and what is expected of you:
                - You are %s, working inside a workspace alongside people.
                - Use a tool only when you need what it returns. Never describe the result of a \
                tool you did not call.
                - Some actions wait for a person to approve them. When that happens you will be \
                told, and you should stop rather than trying another route to the same action.
                - If a tool reports that its outcome is unknown, do not repeat it. Say that the \
                outcome is unknown.
                - When you do not have enough information, say so plainly instead of guessing.
                - Write in plain, declarative sentences. No exclamation marks.
                """.formatted(agent.getName()));
        return prompt.toString();
    }

    /**
     * Rebuilds a conversation from the persisted trace.
     *
     * <p>Only the turns matter, not the metadata, so a resumed run sees the same conversation the
     * model saw before it parked - including the tool results that arrived before the approval
     * was needed.
     */
    private List<ChatMessage> rebuildConversation(Run run, Agent agent, AgentVersion version) {
        List<ChatMessage> conversation = new ArrayList<>();
        conversation.add(ChatMessage.system(buildSystemPrompt(agent, version)));

        for (RunStep step : steps.findByRunIdOrderByPosition(run.getId())) {
            Map<String, Object> detail = step.getDetail();
            switch (step.getKind()) {
                case "model_call" -> {
                    Object content = detail.get("content");
                    if (content instanceof String text && !text.isBlank()) {
                        conversation.add(ChatMessage.assistant(text));
                    }
                }
                case "tool_call" -> conversation.add(ChatMessage.toolResult(
                        String.valueOf(detail.getOrDefault("tool", "tool")),
                        String.valueOf(detail.getOrDefault("tool", "tool")),
                        String.valueOf(detail.getOrDefault("summary", ""))));
                case "approval" -> conversation.add(ChatMessage.user(
                        "The action you requested has been approved. Continue from where you stopped."));
                default -> {
                    /* Notes, errors and memory steps carry no conversational turn. */
                }
            }
        }
        return conversation;
    }

    private List<ToolGrant> loadGrants(UUID agentId) {
        return grants.findByAgentIdAndEnabledTrue(agentId).stream()
                .map(this::toGrant)
                .toList();
    }

    private ToolGrant toGrant(AgentToolGrant entity) {
        return new ToolGrant(
                entity.getAgentId().toString(),
                entity.getServer(),
                entity.getAllowedTools(),
                entity.getScopes(),
                entity.isRequireApproval(),
                entity.getMaxCallsPerRun(),
                entity.isEnabled());
    }

    /** Keeps one oversized answer from making the whole trace unreadable, and the row enormous. */
    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 8_000 ? value : value.substring(0, 8_000) + "…";
    }

    /**
     * Picks up runs whose worker stopped renewing the lease.
     *
     * <p>Marked abandoned rather than restarted. The run may have completed a side effect before
     * dying, and repeating it automatically is how one approved email becomes two.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int reapAbandoned(int limit) {
        List<Run> abandoned = runs.findAbandoned(
                Instant.now(), org.springframework.data.domain.PageRequest.of(0, limit));
        for (Run run : abandoned) {
            run.finish("abandoned", "The worker running this task stopped responding.");
            runs.save(run);
            log.warn("Run {} abandoned: lease expired at {}", run.getId(), run.getLeaseExpiresAt());
        }
        return abandoned.size();
    }

    static String workerId() {
        return WORKER_ID;
    }

    static String currentActor() {
        return RequestContext.actor().map(actor -> actor.id()).orElse("system");
    }
}
