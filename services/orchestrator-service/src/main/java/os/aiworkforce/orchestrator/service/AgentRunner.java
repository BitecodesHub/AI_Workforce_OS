package os.aiworkforce.orchestrator.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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
import os.aiworkforce.orchestrator.repository.Usage;
import os.aiworkforce.orchestrator.voice.VoiceClipService;
import os.aiworkforce.platform.context.Actor;
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
 *
 * <p>No single database transaction spans a run: a model call or a tool call can take a minute,
 * and holding a connection open for that long, for every run in flight, is how a workspace runs
 * out of them. Instead every persistence unit - creating the run, recording one step, parking,
 * finishing - commits in its own transaction, and the loop re-reads the run's own status before
 * every model call so a cancellation made by another request is seen without a thread being
 * interrupted.
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
    private final AuditClient audit;
    private final Usage usage;
    private final TaskProgress progress;
    private final VoiceClipService voiceClips;
    private final TransactionTemplate newTransaction;

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
            ToolCredentialResolver toolCredentials,
            AuditClient audit,
            Usage usage,
            TaskProgress progress,
            VoiceClipService voiceClips,
            PlatformTransactionManager transactionManager) {
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
        this.audit = audit;
        this.usage = usage;
        this.progress = progress;
        this.voiceClips = voiceClips;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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

    /**
     * Starts a new run and drives it as far as it will go.
     *
     * <p>The only exceptions that leave this method are the checks below, made before anything is
     * written, so a caller's own transaction - the goal sweep claiming a task, for one - is never
     * marked rollback-only by a refusal here. Once the run has been saved, a refusal inside the
     * loop ends the run as failed instead (see {@link #driveOrFail}), so no run is ever left
     * behind as "running" by a start that failed.
     */
    public Outcome start(UUID orgId, UUID agentId, UUID taskId, String instruction, String trigger) {
        return start(orgId, agentId, taskId, instruction, trigger, List.of());
    }

    /**
     * As {@link #start(UUID, UUID, UUID, String, String)}, additionally recording a {@code handoff}
     * step per predecessor task the goal sweep found already completed for this one.
     *
     * @param handoffs one detail map per predecessor, in the shape the {@code handoff} step kind
     *     carries: {@code fromTaskId}, {@code fromAgentId}, {@code fromAgentName}, {@code toAgentId},
     *     {@code toAgentName}, {@code summary}
     */
    public Outcome start(
            UUID orgId, UUID agentId, UUID taskId, String instruction, String trigger,
            List<Map<String, Object>> handoffs) {
        Agent agent = agents.findByIdAndOrgId(agentId, orgId)
                .orElseThrow(() -> ApiException.notFound("agent", agentId));
        if (!agent.isActive()) {
            throw new ApiException(ErrorCode.POLICY_VIOLATION, "That agent is paused.");
        }

        AgentVersion version = Optional.ofNullable(agent.getCurrentVersionId())
                .flatMap(versions::findById)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.CONFLICT, "That agent has no configuration yet. Save its persona first."));

        Run run = createRun(orgId, agentId, taskId, trigger, version, instruction, handoffs);

        List<ChatMessage> conversation = new ArrayList<>();
        conversation.add(ChatMessage.system(buildSystemPrompt(agent, version)));
        conversation.add(ChatMessage.user(instruction));

        return driveOrFail(run, agent, version, conversation);
    }

    /**
     * Seals the version, saves the run, and records its first steps - the instruction and, when
     * the goal sweep supplied any, the handoffs from tasks already completed for it - all as one
     * unit of persistence.
     */
    private Run createRun(
            UUID orgId, UUID agentId, UUID taskId, String trigger, AgentVersion version,
            String instruction, List<Map<String, Object>> handoffs) {
        return inNewTransaction(() -> {
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
            saveRun(run);
            // The first step of every trace is what the agent was asked. It is also what a resumed
            // run rebuilds its conversation from, so it is stored whole; both callers already cap
            // an instruction at 10,000 characters. Written directly rather than through
            // saveStep(), which opens its own transaction - this is already inside one.
            writeStep(run, "note", Map.of("type", "instruction", "content", instruction == null ? "" : instruction));
            for (Map<String, Object> handoff : handoffs) {
                writeStep(run, "handoff", handoff);
            }
            return run;
        });
    }

    /**
     * Resumes a run that was parked waiting for an approval.
     *
     * <p>Before the loop continues, the call that was approved is actually invoked - the defect
     * this replaces told the model the action "was carried out" without ever making it happen. The
     * conversation is then rebuilt from the persisted steps rather than held in memory, which is
     * precisely what allows the approval to take a day and to be decided by a different instance
     * of the service from the one that raised it.
     */
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

        executeApprovedCall(run);

        inNewTransaction(() -> {
            run.setStatus("running");
            run.renewLease(WORKER_ID, LEASE);
            saveRun(run);
            progress.onRunResumed(run);
            return null;
        });

        return driveOrFail(run, agent, version, rebuildConversation(run, agent, version));
    }

    /**
     * Makes the tool call an approval was just granted for, exactly once.
     *
     * <p>The most recent {@code approval} step in the trace is the one this resume answers. Its
     * {@link Approval} row - not the step, which is written by the agent's own side and never
     * altered afterwards - is where the decision and the call to make live. Nothing happens when
     * that approval cannot be found or was not approved: a rejection already ended the run
     * elsewhere ({@link ApprovalService#decide}), and an approval this method cannot resolve is
     * left for a person to look into rather than guessed at.
     *
     * <p>Safe to call more than once for the same run: a call already answered by a recorded
     * {@code tool_call} step is not repeated.
     */
    private void executeApprovedCall(Run run) {
        List<RunStep> trace = steps.findByRunIdOrderByPosition(run.getId());
        RunStep latestApproval = null;
        for (int i = trace.size() - 1; i >= 0; i--) {
            if ("approval".equals(trace.get(i).getKind())) {
                latestApproval = trace.get(i);
                break;
            }
        }
        if (latestApproval == null) {
            return;
        }

        String toolCallId = toolCallIdOf(latestApproval.getDetail());
        boolean alreadyExecuted = trace.stream()
                .anyMatch(step -> "tool_call".equals(step.getKind()) && toolCallId.equals(toolCallIdOf(step.getDetail())));
        if (alreadyExecuted) {
            return;
        }

        Object approvalIdRaw = latestApproval.getDetail().get("approvalId");
        UUID approvalId = approvalIdRaw instanceof String text ? parseUuidOrNull(text) : null;
        if (approvalId == null) {
            return;
        }
        Approval approval = approvals.find(run.getOrgId(), approvalId).orElse(null);
        if (approval == null || !"approved".equals(approval.getStatus()) || approval.getTool() == null) {
            return;
        }

        String[] parts = approval.getTool().split("\\.", 2);
        if (parts.length != 2) {
            return;
        }

        ToolInvocation invocation = new ToolInvocation(
                run.getOrgId().toString(),
                run.getAgentId().toString(),
                run.getId().toString(),
                parts[0],
                parts[1],
                approval.getPayload(),
                run.getId() + ":" + toolCallId,
                Map.of("runId", run.getId().toString()));

        // The decision the model asked for has already been made by a person; this is not a fresh
        // evaluation, it is carrying out the one that was granted.
        String credential = toolCredentials.resolve(run.getOrgId(), parts[0]).orElse(null);
        Instant startedAt = Instant.now();
        ToolResult result = tools.invoke(invocation, ApprovalDecision.PROCEED, credential).block(Duration.ofSeconds(60));
        if (result == null) {
            result = ToolResult.indeterminate("The tool did not return a result.", Duration.ZERO);
        }
        long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
        String tool = approval.getTool();
        ToolResult finalResult = result;

        inNewTransaction(() -> {
            writeStep(run, "tool_call", Map.of(
                    "toolCallId", toolCallId,
                    "tool", tool,
                    "status", finalResult.status().name(),
                    "summary", finalResult.summary() == null ? "" : finalResult.summary(),
                    "durationMs", durationMs));
            return null;
        });
    }

    // ---- The loop ------------------------------------------------------------------------

    /**
     * Drives the loop, and ends the run as failed if something inside it refuses.
     *
     * <p>Routing policy resolution, the tool gateway and raising an approval can all refuse with
     * an {@link ApiException} after the run has been saved. Letting that escape would either leave
     * the run "running" until the reaper gives up on it (a new run commits what it wrote so far)
     * or put a resumed run back to "waiting for approval" with its approval already decided,
     * where nothing would ever pick it up again. Recording the refusal on the trace and finishing
     * the run reports it to the task like any other failure.
     */
    private Outcome driveOrFail(Run run, Agent agent, AgentVersion version, List<ChatMessage> conversation) {
        try {
            return drive(run, agent, version, conversation);
        } catch (ApiException e) {
            if (!run.isActive()) {
                // The run had already finished when the refusal came (from its own bookkeeping),
                // so there is nothing left to stop.
                throw e;
            }
            log.warn("Run {} stopped: {}", run.getId(), e.getMessage());
            saveStep(run, "error", Map.of("code", e.code().wire(), "detail", e.getMessage()));
            return finish(run, "failed", e.getMessage(), null);
        }
    }

    private Outcome drive(Run run, Agent agent, AgentVersion version, List<ChatMessage> conversation) {
        RoutingPolicy policy = policies.resolve(run.getOrgId(), agent.getId());
        List<ToolGrant> toolGrants = loadGrants(agent.getId());
        List<ToolDefinition> available = tools.availableTools(toolGrants);
        List<ToolSpec> specs = available.stream().map(ToolDefinition::toSpec).toList();

        ModelRouter.CallContext context = new ModelRouter.CallContext(
                run.getOrgId().toString(), agent.getId().toString(), run.getId().toString());

        while (run.getStepCount() < version.getMaxSteps()) {
            // Read fresh before every model call, not just once: a person can cancel this run from
            // another request while this loop is mid-flight, and the only way that is ever seen is
            // by asking again. Falling back to what this loop already believes when the run cannot
            // be found is what keeps a run started inside a caller's own still-open transaction
            // (nothing committed for another connection to see yet) working exactly as before.
            String currentStatus = runs.findById(run.getId()).map(Run::getStatus).orElse(run.getStatus());
            if (!"running".equals(currentStatus)) {
                log.info("Run {} stopped cooperatively: it is now {}", run.getId(), currentStatus);
                return new Outcome(currentStatus, null, run.getId());
            }

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
                saveStep(run, "error", Map.of("code", e.code().wire(), "detail", e.getMessage()));
                return finish(run, "failed", e.getMessage(), null);
            }

            recordModelStepAndAdvance(run, response);

            if (!response.hasToolCalls()) {
                // A truncated answer is not a finished one. Saying so lets a person ask for the
                // rest rather than acting on half of it.
                if (response.isTruncated()) {
                    return finish(run, "failed",
                            "The model's answer was cut short by the output limit.", response.content());
                }
                // A provider occasionally answers 200 with no content and no recognised finish
                // reason, most often a transient fault on a free-tier routed model. Completing
                // the run anyway would hand back a silent blank answer with no sign anything
                // went wrong; failing it lets the existing retry rule try again.
                if (response.content() == null || response.content().isBlank()) {
                    return finish(run, "failed",
                            "The model returned no answer, with no error explaining why. This is usually "
                                    + "transient; retrying the same instruction usually works.",
                            null);
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
            saveStep(run, "approval", Map.of(
                    "approvalId", approval.getId().toString(),
                    "toolCallId", call.id(),
                    "tool", call.name(),
                    "summary", await.reason()));
            return new ToolOutcome(ToolResult.blocked(await.reason()), true);
        }

        if (decision instanceof ApprovalDecision.Refuse refuse) {
            saveStep(run, "tool_call", Map.of(
                    "toolCallId", call.id(),
                    "tool", call.name(), "status", "BLOCKED", "reason", refuse.reason()));
            return new ToolOutcome(ToolResult.blocked(refuse.reason()), false);
        }

        String credential = toolCredentials.resolve(run.getOrgId(), parts[0]).orElse(null);
        Instant startedAt = Instant.now();
        ToolResult result = tools.invoke(invocation, decision, credential).block(Duration.ofSeconds(60));
        if (result == null) {
            result = ToolResult.indeterminate("The tool did not return a result.", Duration.ZERO);
        }

        Map<String, Object> detail = new LinkedHashMap<>(Map.of(
                "toolCallId", call.id(),
                "tool", call.name(),
                "status", result.status().name(),
                "summary", result.summary() == null ? "" : result.summary(),
                "durationMs", Duration.between(startedAt, Instant.now()).toMillis()));
        // A voice note is spoken, not just recorded, once the workspace has an ElevenLabs key.
        // Wrapped so a synthesis problem never fails a run that already succeeded at its own
        // tool - it only means this one clip carries no audio.
        if (result.isSuccess() && "voice".equals(parts[0]) && "create_voice_note".equals(parts[1])) {
            try {
                Optional<UUID> clipId = voiceClips.afterVoiceNote(run, agent, invocation.argumentsJson());
                if (clipId.isPresent()) {
                    detail.put("clipId", clipId.get().toString());
                } else if (!voiceClips.keyStored(run.getOrgId())) {
                    detail.put("summary", detail.get("summary")
                            + " No ElevenLabs key is stored, so no audio was created.");
                }
            } catch (RuntimeException e) {
                log.warn("Voice note could not be captured for run {}: {}", run.getId(), e.getMessage());
            }
        }
        saveStep(run, "tool_call", detail);

        return new ToolOutcome(result, false);
    }

    // ---- Persistence of the trace --------------------------------------------------------

    /** Records one model turn and, in the same unit of persistence, advances the run's counters. */
    private void recordModelStepAndAdvance(Run run, ChatResponse response) {
        inNewTransaction(() -> {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("provider", response.provider());
            detail.put("model", response.model());
            detail.put("finishReason", response.finishReason().name());
            detail.put("content", truncate(response.content()));
            detail.put("toolCalls", response.toolCalls().stream().map(ToolCall::name).toList());
            // Kept separately from the display-only "toolCalls" names above: this is what lets a
            // resumed run reconstruct the assistant's actual tool-call turn (see
            // rebuildConversation), rather than the model losing all memory of having already made
            // the call it was approved for, and simply calling it again.
            detail.put("toolCallRecords", response.toolCalls().stream()
                    .map(call -> (Object) Map.of(
                            "id", call.id(), "name", call.name(), "argumentsJson", call.argumentsJson()))
                    .toList());
            // The failed attempts are the useful part: a run that answered on the third provider is
            // only explicable if the first two are in the record.
            detail.put("attempts", response.attempts().stream().map(AttemptRecord::summary).toList());

            // The router has already written every attempt, with its price, before returning. The
            // step is charged the difference, so failed attempts before the answer are counted too.
            BigDecimal total = usage.costForRun(run.getId());
            BigDecimal cost = total.subtract(run.getTotalCost()).max(BigDecimal.ZERO);
            run.setTotalCost(total);
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
            run.setStepCount(run.getStepCount() + 1);
            // Renewed every step, so a long run is never reaped while it is genuinely working.
            run.renewLease(WORKER_ID, LEASE);
            saveRun(run);
            return null;
        });
    }

    /** Saves one trace step as its own unit of persistence. */
    private void saveStep(Run run, String kind, Map<String, Object> detail) {
        inNewTransaction(() -> {
            writeStep(run, kind, detail);
            return null;
        });
    }

    /** The write itself, for a caller that is already inside its own transaction. */
    private void writeStep(Run run, String kind, Map<String, Object> detail) {
        steps.save(RunStep.of(run.getOrgId(), run.getId(), nextPosition(run), kind, detail));
    }

    private int nextPosition(Run run) {
        return steps.highestPosition(run.getId()) + 1;
    }

    private Outcome park(Run run) {
        return inNewTransaction(() -> {
            run.setStatus("waiting_approval");
            run.renewLease(WORKER_ID, Duration.ofDays(7));
            run.setTotalCost(usage.costForRun(run.getId()));
            saveRun(run);
            progress.onRunParked(run);
            log.info("Run {} parked waiting for approval", run.getId());
            return new Outcome("waiting_approval", null, run.getId());
        });
    }

    private Outcome finish(Run run, String status, String failureReason, String answer) {
        Outcome outcome = inNewTransaction(() -> {
            run.finish(status, failureReason);
            // Refreshed here as well as per step, so a run that failed inside the router still
            // carries the cost of the attempts it made.
            run.setTotalCost(usage.costForRun(run.getId()));
            saveRun(run);
            progress.onRunFinished(run, status, answer, failureReason);
            return new Outcome(status, answer, run.getId());
        });
        tools.releaseRun(run.getId().toString());
        log.info("Run {} finished as {}", run.getId(), status);
        emitRunAudit(run, status, failureReason);
        return outcome;
    }

    /**
     * Records the run's terminal state in the audit projection.
     *
     * <p>The acting principal comes from the ambient request context when one is present - the
     * person or agent whose call is driving this loop - and falls back to the platform actor for
     * work started outside a request, such as the abandonment reaper.
     */
    private void emitRunAudit(Run run, String status, String failureReason) {
        boolean completed = "completed".equals(status);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("status", status);
        if (failureReason != null) {
            detail.put("failureReason", failureReason);
        }
        Actor actor = RequestContext.actor().orElse(Actor.SYSTEM);
        audit.record(
                run.getOrgId(),
                actor,
                completed ? "run.complete" : "run.fail",
                "run",
                run.getId().toString(),
                completed ? "succeeded" : "failed",
                detail);
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

        List<RunStep> trace = steps.findByRunIdOrderByPosition(run.getId());
        Set<String> answered = trace.stream()
                .filter(step -> "tool_call".equals(step.getKind()))
                .map(step -> toolCallIdOf(step.getDetail()))
                .collect(java.util.stream.Collectors.toSet());

        for (RunStep step : trace) {
            Map<String, Object> detail = step.getDetail();
            switch (step.getKind()) {
                case "model_call" -> {
                    Object content = detail.get("content");
                    String text = content instanceof String s && !s.isBlank() ? s : null;
                    List<ToolCall> calls = readToolCallRecords(detail.get("toolCallRecords"));
                    if (!calls.isEmpty()) {
                        // The assistant's tool-call turn itself, preserved so a resumed run still
                        // knows which call it made and with what arguments - not just that some
                        // call was approved.
                        conversation.add(ChatMessage.assistantToolCalls(text, calls));
                    } else if (text != null) {
                        conversation.add(ChatMessage.assistant(text));
                    }
                }
                case "tool_call" -> conversation.add(ChatMessage.toolResult(
                        toolCallIdOf(detail),
                        String.valueOf(detail.getOrDefault("tool", "tool")),
                        String.valueOf(detail.getOrDefault("summary", ""))));
                case "approval" -> {
                    // A tool_call step, wherever it falls in the trace, is the real result of this
                    // approval and is what the model is told; two messages for one call id would
                    // teach it there were two calls. This stub only stands in when no such step
                    // exists yet - an approval this method could not resolve back to a call.
                    String toolCallId = toolCallIdOf(detail);
                    if (!answered.contains(toolCallId)) {
                        conversation.add(ChatMessage.toolResult(
                                toolCallId,
                                String.valueOf(detail.getOrDefault("tool", "tool")),
                                "A person approved this action; its result follows."));
                    }
                }
                case "note" -> {
                    // The instruction is the first step, so it lands straight after the system
                    // prompt - where it was when the model first saw it.
                    Object content = detail.get("content");
                    if ("instruction".equals(detail.get("type")) && content instanceof String text && !text.isBlank()) {
                        conversation.add(ChatMessage.user(text));
                    }
                }
                default -> {
                    /* Handoffs, other notes, errors and memory steps carry no conversational turn. */
                }
            }
        }
        return conversation;
    }

    /**
     * The call a "tool_call" or "approval" step answers.
     *
     * <p>Falls back to the tool's name for a step written before this field existed, which keeps
     * an old parked run resumable rather than throwing - it will not link to a specific call in a
     * multi-call turn, but a single-call turn (the overwhelming majority) still resumes cleanly.
     */
    private static String toolCallIdOf(Map<String, Object> detail) {
        Object id = detail.get("toolCallId");
        return id instanceof String text && !text.isBlank() ? text : String.valueOf(detail.getOrDefault("tool", "tool"));
    }

    private static UUID parseUuidOrNull(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<ToolCall> readToolCallRecords(Object raw) {
        if (!(raw instanceof List<?> records) || records.isEmpty()) {
            return List.of();
        }
        List<ToolCall> calls = new ArrayList<>();
        for (Object entry : records) {
            if (entry instanceof Map<?, ?> record
                    && record.get("id") instanceof String id
                    && record.get("name") instanceof String name) {
                Object args = record.get("argumentsJson");
                calls.add(new ToolCall(id, name, args instanceof String text ? text : "{}"));
            }
        }
        return calls;
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

    private <T> T inNewTransaction(Supplier<T> body) {
        return newTransaction.execute(status -> body.get());
    }

    /**
     * Saves the run this loop holds across several short transactions.
     *
     * <p>That copy is detached between steps, so a save merges it and returns a different copy
     * with the new version; adopting that version is what lets the next step's save succeed
     * instead of failing an optimistic-lock check against this loop's own previous write.
     */
    private void saveRun(Run run) {
        run.adoptVersion(runs.saveAndFlush(run));
    }

    /**
     * Picks up runs whose worker stopped renewing the lease.
     *
     * <p>Marked abandoned rather than restarted. The run may have completed a side effect before
     * dying, and repeating it automatically is how one approved email becomes two.
     */
    public int reapAbandoned(int limit) {
        return inNewTransaction(() -> {
            List<Run> abandoned = runs.findAbandoned(
                    Instant.now(), org.springframework.data.domain.PageRequest.of(0, limit));
            for (Run run : abandoned) {
                Instant leaseExpiredAt = run.getLeaseExpiresAt();
                String reason = "The worker running this task stopped responding.";
                run.finish("abandoned", reason);
                run.setTotalCost(usage.costForRun(run.getId()));
                saveRun(run);
                progress.onRunFinished(run, "abandoned", null, reason);
                log.warn("Run {} abandoned: lease expired at {}", run.getId(), leaseExpiredAt);
            }
            return abandoned.size();
        });
    }

    static String workerId() {
        return WORKER_ID;
    }

    static String currentActor() {
        return RequestContext.actor().map(actor -> actor.id()).orElse("system");
    }
}
